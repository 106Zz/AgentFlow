package com.agenthub.api.ai.utils;

import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.knowledge.domain.DeleteResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.mapper.KnowledgeBaseMapper;
import com.agenthub.api.search.mapper.VectorStoreDocMapper;
import com.agenthub.api.search.service.IBm25IndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 知识库文档处理核心工具类
 *
 * <p>功能：</p>
 * <ul>
 *   <li>文档解析（PDF混合解析 / Tika通用解析）</li>
 *   <li>中文智能分块</li>
 *   <li>向量存储（PGVector）</li>
 *   <li>BM25索引构建（异步）</li>
 *   <li>向量删除</li>
 *   <li>权限检索</li>
 * </ul>
 *
 * <p>版本历史：</p>
 * <ul>
 *   <li>v1.0 - 基础文档解析和向量存储</li>
 *   <li>v2.0 - 自定义中文分块器，支持chunk重叠</li>
 *   <li>v3.0 - Metadata null值防御</li>
 *   <li>v4.0 - 自生成UUID关联BM25索引</li>
 *   <li>v4.1 - 集成BM25索引服务</li>
 *   <li>v4.2 - 向量删除改用直接SQL，移除DashScope API调用和重试逻辑</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VectorStoreHelper {

    // ==================== 依赖注入 ====================

    private final PgVectorStore vectorStore;
    private final QwenOcrDocumentReader ocrReader;
    private final IBm25IndexService bm25IndexService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorStoreDocMapper vectorStoreDocMapper;
    private final OssUtils ossUtils;

    @Value("${ocr.trigger.min-length:30}")
    private int ocrMinLength;

    private final Tika tika = new Tika();

    // ==================== 常量定义 ====================

    /** 向量存储批次大小 */
    private static final int VECTOR_BATCH_SIZE = 10;

    /** Chunk最小长度（字符） */
    private static final int MIN_CHUNK_LENGTH = 50;

    /** Chunk目标最小长度（字符） */
    private static final int TARGET_CHUNK_LENGTH = 450;

    /** Chunk目标最大长度（字符） */
    private static final int MAX_CHUNK_LENGTH = 600;

    /** Chunk重叠长度（字符） */
    private static final int OVERLAP_LENGTH = 150;

    /** 文档类别 */
    private static final class Category {
        private static final String BUSINESS = "BUSINESS";      // 商业/价格
        private static final String TECHNICAL = "TECHNICAL";    // 技术/运行
        private static final String REGULATION = "REGULATION";    // 规则/合规（默认）
    }

    // ==================== 中文分块器 ====================

    /**
     * 智能中文分块器
     *
     * <p>策略：按段落 → 句子 → 标点符号 → 空格 递归分割</p>
     * <p>大小：450-600字符/块</p>
     * <p>重叠：相邻块有150字符重叠</p>
     */
    private final TextSplitter chineseTextSplitter = new TextSplitter() {

        private final List<String> separators = List.of(
                "\n\n", "\n", "。", "！", "？", "；", "，", " ", ""
        );

        @Override
        public List<String> splitText(String text) {
            List<String> chunks = new ArrayList<>();
            splitRecursive(text, 0, chunks);
            return chunks;
        }

        @Override
        public List<Document> split(List<Document> documents) {
            List<Document> allChunks = new ArrayList<>();
            for (Document doc : documents) {
                List<String> texts = splitText(doc.getText());
                for (int i = 0; i < texts.size(); i++) {
                    Document chunk = new Document(texts.get(i));
                    // 复制原始 metadata（过滤null值）
                    doc.getMetadata().forEach((k, v) -> {
                        if (v != null) {
                            chunk.getMetadata().put(k, v);
                        }
                    });
                    chunk.getMetadata().put("chunkIndex", i);
                    chunk.getMetadata().put("totalChunks", texts.size());
                    allChunks.add(chunk);
                }
            }
            return allChunks;
        }

        private void splitRecursive(String text, int sepIndex, List<String> chunks) {
            if (sepIndex >= separators.size() - 1) {
                splitByLength(text, chunks);
                return;
            }

            String sep = separators.get(sepIndex);
            String[] parts = text.split(Pattern.quote(sep));

            if (parts.length <= 1) {
                splitRecursive(text, sepIndex + 1, chunks);
                return;
            }

            StringBuilder current = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (i > 0) {
                    part = sep + part;
                }

                if (current.length() + part.length() > MAX_CHUNK_LENGTH) {
                    if (current.length() > MIN_CHUNK_LENGTH) {
                        chunks.add(current.toString().trim());
                    }
                    current.setLength(0);
                }

                current.append(part);

                if (current.length() >= TARGET_CHUNK_LENGTH && i < parts.length - 1) {
                    chunks.add(current.toString().trim());
                    String overlap = current.length() > OVERLAP_LENGTH
                            ? current.substring(current.length() - OVERLAP_LENGTH)
                            : current.toString();
                    current = new StringBuilder(overlap);
                }
            }

            if (current.length() > MIN_CHUNK_LENGTH) {
                chunks.add(current.toString().trim());
            }
        }

        private void splitByLength(String text, List<String> chunks) {
            for (int i = 0; i < text.length(); i += 500) {
                int end = Math.min(i + 600, text.length());
                String chunk = text.substring(i, end).trim();
                if (chunk.length() > MIN_CHUNK_LENGTH) {
                    chunks.add(chunk);
                }
            }
        }
    };

    // ==================== 文档入库方法 ====================

    /**
     * 处理用户上传的文件
     *
     * @param file          上传的文件
     * @param knowledgeId   知识库ID
     * @param userId        用户ID
     * @param isPublic      是否公开 ("0"=私有, "1"=公开)
     * @param extraMetadata 额外的元数据
     * @return 切片数量
     */
    public int processAndStoreDocument(MultipartFile file, Long knowledgeId, Long userId,
                                       String isPublic, Map<String, Object> extraMetadata)
            throws IOException {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();
        byte[] fileBytes = file.getBytes();
        return processAndStore(fileBytes, filename, fileSize, knowledgeId, userId, isPublic, extraMetadata);
    }

    /**
     * 处理系统预加载的文件
     *
     * @param resource      Spring Resource对象
     * @param knowledgeId   知识库ID
     * @param userId        用户ID
     * @param isPublic      是否公开
     * @param extraMetadata 额外的元数据
     * @return 切片数量
     */
    public int processAndStoreDocument(Resource resource, Long knowledgeId, Long userId,
                                       String isPublic, Map<String, Object> extraMetadata)
            throws IOException {
        String filename = resource.getFilename();
        long fileSize = resource.contentLength();
        if (filename == null) {
            throw new IllegalArgumentException("Resource 缺少文件名");
        }
        byte[] fileBytes = resource.getInputStream().readAllBytes();
        return processAndStore(fileBytes, filename, fileSize, knowledgeId, userId, isPublic, extraMetadata);
    }

    /**
     * 核心处理方法（统一入口）
     *
     * @param fileBytes      文件字节数组
     * @param filename       文件名
     * @param fileSize       文件大小
     * @param knowledgeId    知识库ID
     * @param userId         用户ID
     * @param isPublic       是否公开
     * @param extraMetadata  额外的元数据
     * @return 切片数量
     */
    public int processAndStore(byte[] fileBytes, String filename, long fileSize,
                                Long knowledgeId, Long userId, String isPublic,
                                Map<String, Object> extraMetadata) throws IOException {

        log.info("【开始处理文档】{} ({}KB)，用户ID: {}", filename, fileSize / 1024, userId);

        List<Document> documents = new ArrayList<>();

        // 1. 文件解析
        if (filename.toLowerCase().endsWith(".pdf")) {
            documents = parsePdf(fileBytes);
        } else {
            documents = parseNonPdf(fileBytes, filename);
        }

        if (documents.isEmpty() || documents.get(0).getText().trim().isEmpty()) {
            throw new IllegalArgumentException("文档解析后内容为空: " + filename);
        }

        // 2. 中文智能分块
        List<Document> chunks = chineseTextSplitter.split(documents);
        log.info("【智能分块完成】总块数: {}", chunks.size());

        // 3. 添加 Metadata（含 UUID 生成）
        enrichMetadata(chunks, filename, fileSize, knowledgeId, userId, isPublic, extraMetadata);

        // 4. 存储向量和构建索引
        storeWithIndex(chunks, knowledgeId, userId);

        return chunks.size();
    }

    /**
     * 解析PDF文件（混合模式：文本提取 + OCR）
     */
    private List<Document> parsePdf(byte[] fileBytes) throws IOException {
        List<Document> documents = new ArrayList<>();

        try (PDDocument pdfDoc = Loader.loadPDF(fileBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int totalPages = pdfDoc.getNumberOfPages();
            log.info("PDF共 {} 页，开始页级混合解析...", totalPages);

            for (int i = 0; i < totalPages; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(pdfDoc).trim();

                if (pageText.length() > ocrMinLength) {
                    Document doc = new Document(pageText);
                    doc.getMetadata().put("page_index", i + 1);
                    doc.getMetadata().put("ocr_used", false);
                    documents.add(doc);
                } else {
                    log.info("第 {} 页：文本不足，启用 Qwen-VL OCR...", i + 1);
                    BufferedImage image = renderer.renderImage(i, 2.0f);
                    String ocrText = ocrReader.processSingleImage(image);

                    if (ocrText != null && !ocrText.isEmpty()) {
                        Document doc = new Document(ocrText);
                        doc.getMetadata().put("page_index", i + 1);
                        doc.getMetadata().put("ocr_used", true);
                        documents.add(doc);
                    }
                }
            }
        }

        return documents;
    }

    /**
     * 解析非PDF文件（使用Tika）
     */
    private List<Document> parseNonPdf(byte[] fileBytes, String filename) throws IOException {
        log.info("使用 Tika 解析非 PDF 文件: {}", filename);

        String text;
        try {
            text = tika.parseToString(new ByteArrayInputStream(fileBytes));
        } catch (TikaException e) {
            throw new RuntimeException("Tika 解析失败: " + filename, e);
        }

        if (text.trim().isEmpty()) {
            throw new IllegalArgumentException("Tika 解析后内容为空: " + filename);
        }

        return List.of(new Document(text));
    }

    /**
     * 为chunk添加Metadata（包含UUID生成）
     */
    private void enrichMetadata(List<Document> chunks, String filename, long fileSize,
                                Long knowledgeId, Long userId, String isPublic,
                                Map<String, Object> extraMetadata) {
        Instant now = Instant.now();

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String text = chunk.getText();

            // ===== 关键：自己生成UUID，用于关联BM25索引 =====
            String vectorId = UUID.randomUUID().toString();
            chunk.getMetadata().put("internal_id", vectorId);
            // ===========================================

            // 用户隔离元数据
            chunk.getMetadata().put("knowledge_id", String.valueOf(knowledgeId));
            chunk.getMetadata().put("user_id", String.valueOf(userId));

            // 公开标识
            chunk.getMetadata().put("is_public", isPublic != null ? isPublic : "0");

            // 文件信息
            chunk.getMetadata().put("filename", filename != null ? filename : "unknown");
            chunk.getMetadata().put("fileSize", fileSize);
            chunk.getMetadata().put("category", inferCategoryFromFilename(filename));
            chunk.getMetadata().put("uploadTime", now.toEpochMilli());

            // Chunk信息
            chunk.getMetadata().put("chunkIndex", i);
            chunk.getMetadata().put("totalChunks", chunks.size());

            // 内容预览
            String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
            chunk.getMetadata().put("preview", preview);

            // 额外元数据
            if (extraMetadata != null) {
                extraMetadata.forEach((k, v) -> {
                    if (v != null) {
                        chunk.getMetadata().put(k, v);
                    }
                });
            }
        }
    }

    /**
     * 存储向量并同步批量构建BM25索引
     */
    private void storeWithIndex(List<Document> chunks, Long knowledgeId, Long userId) {
        for (int i = 0; i < chunks.size(); i += VECTOR_BATCH_SIZE) {
            int end = Math.min(i + VECTOR_BATCH_SIZE, chunks.size());
            List<Document> batch = chunks.subList(i, end);

            // 存储向量
            vectorStore.add(batch);


            bm25IndexService.batchIndexDocuments(batch, knowledgeId, userId);

            log.info("【写入向量库】已存储 {}-{} / {} 块", i + 1, end, chunks.size());
        }
    }

    // ==================== 删除方法 ====================

    /**
     * 批量删除知识库核心数据（向量 + BM25 + 数据库记录）
     *
     * 删除顺序（在事务中执行）：
     * 1. 批量删除向量数据（直接 SQL，不调用 DashScope API）
     * 2. 批量删除 BM25 索引
     * 3. 批量删除 knowledge_base 记录
     *
     * @param knowledgeIds 知识库ID列表
     * @return 删除结果
     */
    public DeleteResult deleteKnowledgeData(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return new DeleteResult(0, List.of(), List.of());
        }

        log.info("【删除知识库】开始删除 {} 个知识库的核心数据", knowledgeIds.size());

        List<Long> failedIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            // 在事务中批量删除所有数据
            int deletedCount = deleteDatabaseRecords(knowledgeIds);
            log.info("【删除知识库】完成，成功删除 {} 个知识库", deletedCount);
            return new DeleteResult(deletedCount, failedIds, errors);
        } catch (Exception e) {
            log.error("【删除知识库】删除失败", e);
            failedIds.addAll(knowledgeIds);
            errors.add(e.getMessage());
            return new DeleteResult(0, failedIds, errors);
        }
    }

    /**
     * 在事务中删除数据库记录（向量 + BM25 索引 + knowledge_base 记录）
     *
     * @param knowledgeIds 知识库ID列表
     * @return 删除的记录数
     */
    @Transactional(rollbackFor = Exception.class)
    protected int deleteDatabaseRecords(List<Long> knowledgeIds) {
        // 1. 批量删除向量数据（直接 SQL，不调用 DashScope API）
        int vectorDeleted = vectorStoreDocMapper.deleteByKnowledgeIds(knowledgeIds);
        log.info("【删除知识库】向量数据批量删除成功，删除 {} 条", vectorDeleted);

        // 2. 批量删除 BM25 索引（一条 SQL）
        bm25IndexService.deleteByKnowledgeIds(knowledgeIds);
        log.info("【删除知识库】BM25 索引批量删除成功，删除 {} 个知识库", knowledgeIds.size());

        // 3. 批量删除 knowledge_base 记录（一条 SQL）
        int dbDeleted = knowledgeBaseMapper.deleteBatchIds(knowledgeIds);
        log.info("【删除知识库】数据库记录批量删除成功，删除 {} 条", dbDeleted);

        // 4. 异步重建文档频率表（失败不影响删除结果）
        bm25IndexService.asyncRebuildDocFreqAndStats();

        return dbDeleted;
    }

    /**
     * 清理 OSS 文件（可异步调用，失败不影响核心业务）
     *
     * @param knowledgeIds 知识库ID列表
     * @return 成功删除的文件数
     */
    public int cleanupOssFiles(List<Long> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return 0;
        }

        log.info("【清理OSS】开始清理 {} 个知识库的文件", knowledgeIds.size());
        int cleanedCount = 0;

        for (Long knowledgeId : knowledgeIds) {
            try {
                // 注意：此时记录可能已被软删除，需要直接查询数据库
                KnowledgeBase knowledge = knowledgeBaseMapper.selectById(knowledgeId);
                if (knowledge == null) {
                    log.debug("【清理OSS】知识库不存在，跳过: {}", knowledgeId);
                    continue;
                }

                if (knowledge.getFilePath() != null) {
                    ossUtils.deleteFile(knowledge.getFilePath());
                    cleanedCount++;
                    log.info("【清理OSS】文件删除成功: {}", knowledge.getFilePath());
                }
            } catch (Exception e) {
                log.error("【清理OSS】文件删除失败，knowledgeId={}", knowledgeId, e);
            }
        }

        return cleanedCount;
    }

    // ==================== 检索方法 ====================

    /**
     * 根据用户权限搜索向量（支持类别过滤）
     *
     * @param query     查询文本
     * @param userId    用户ID
     * @param isAdmin   是否管理员
     * @param topK      返回数量
     * @param threshold 相似度阈值
     * @param category  类别过滤（可选）
     * @return 匹配的文档列表
     */
    public List<Document> searchWithUserFilter(String query, Long userId, boolean isAdmin,
                                               int topK, double threshold, String category) {
        String filterExpression = buildFilterExpression(userId, isAdmin, category);

        try {
            var builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);

            if (!filterExpression.isEmpty()) {
                builder.filterExpression(filterExpression);
            }

            return vectorStore.similaritySearch(builder.build());

        } catch (IllegalArgumentException e) {
            log.error("检索向量时遇到脏数据（Metadata为Null），已跳过错误。Query: {}", query, e);
            return new ArrayList<>();
        }
    }

    /**
     * 构建过滤表达式
     */
    private String buildFilterExpression(Long userId, boolean isAdmin, String category) {
        StringBuilder filter = new StringBuilder();

        // 1. 权限过滤
        if (!isAdmin) {
            filter.append("(user_id == '0' && is_public == '1') || user_id == '")
                  .append(userId)
                  .append("'");
        }

        // 2. 类别过滤
        if (category != null && !category.isBlank()) {
            if (!filter.isEmpty()) {
                filter.append(" && ");
            }
            filter.append("category == '").append(category).append("'");
        }

        return filter.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 根据文件名推断文档类别
     *
     * <p>分类优先级：</p>
     * <ol>
     *   <li>BUSINESS - 商业/价格（结算、电价、费用、补偿）</li>
     *   <li>TECHNICAL - 技术/运行（技术、参数、标准、设备）</li>
     *   <li>REGULATION - 规则/合规（默认，兜底）</li>
     * </ol>
     *
     * @param filename 文件名
     * @return 类别代码
     */
    private String inferCategoryFromFilename(String filename) {
        if (filename == null) {
            return Category.REGULATION;
        }

        String name = filename.toLowerCase();

        // 1. BUSINESS - 商业/价格
        if (name.contains("结算") || name.contains("价格") || name.contains("电价") ||
                name.contains("费用") || name.contains("补偿") || name.contains("合约") ||
                name.contains("零售")) {
            return Category.BUSINESS;
        }

        // 2. TECHNICAL - 技术/运行
        if (name.contains("技术") || name.contains("参数") || name.contains("标准") ||
                name.contains("接入") || name.contains("负荷") || name.contains("曲线") ||
                name.contains("储能") || name.contains("虚拟电厂") || name.contains("光伏") ||
                name.contains("新能源") || name.contains("调频")) {
            return Category.TECHNICAL;
        }

        // 3. REGULATION - 默认
        return Category.REGULATION;
    }
}

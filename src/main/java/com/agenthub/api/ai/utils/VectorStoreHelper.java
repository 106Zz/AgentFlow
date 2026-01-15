package com.agenthub.api.ai.utils;

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
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;

/**
 * 知识库文档处理核心工具类（已完成最强中文升级 + Metadata防御增强）
 * 当前版本解决了:
 * 1. TokenTextSplitter 切分不准确的问题
 * 2. 入库时 metadata 为 null 导致的查询/删除异常
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VectorStoreHelper {

    private final PgVectorStore vectorStore;
    private final QwenOcrDocumentReader ocrReader;

    @Value("${ocr.trigger.min-length:30}")
    private int ocrMinLength;

    private final Tika tika = new Tika();

    /**
     * 智能中文分块器
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
                    // 复制原始 metadata，但要小心 null 值
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
                if (i > 0) part = sep + part;

                if (current.length() + part.length() > 600) {
                    if (current.length() > 100) {
                        chunks.add(current.toString().trim());
                    }
                    current.setLength(0);
                }

                current.append(part);

                if (current.length() >= 450 && i < parts.length - 1) {
                    chunks.add(current.toString().trim());
                    String overlap = current.length() > 150
                            ? current.substring(current.length() - 150)
                            : current.toString();
                    current = new StringBuilder(overlap);
                }
            }

            if (current.length() > 50) {
                chunks.add(current.toString().trim());
            }
        }

        private void splitByLength(String text, List<String> chunks) {
            for (int i = 0; i < text.length(); i += 500) {
                int end = Math.min(i + 600, text.length());
                String chunk = text.substring(i, end).trim();
                if (chunk.length() > 50) {
                    chunks.add(chunk);
                }
            }
        }
    };

    // ========== 公共方法 1：用户上传（MultipartFile）==========
    public int processAndStoreDocument(MultipartFile file, Long knowledgeId, Long userId, 
                                       String isPublic, Map<String, Object> extraMetadata)
            throws IOException {
        String filename = file.getOriginalFilename();
        long fileSize = file.getSize();
        byte[] fileBytes = file.getBytes();
        return processAndStore(fileBytes, filename, fileSize, knowledgeId, userId, isPublic, extraMetadata);
    }

    // ========== 公共方法 2：系统预加载（Resource）==========
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

    // ========== 核心方法（统一处理）==========
    public int processAndStore(byte[] fileBytes, String filename, long fileSize,
                                Long knowledgeId, Long userId, String isPublic,
                                Map<String, Object> extraMetadata) throws IOException {

        log.info("【开始处理文档】{}（{}KB），用户ID： প্রশিক্ষ", filename, fileSize / 1024, userId);

        List<Document> documents = new ArrayList<>();

        // 1. 判断文件类型
        if (filename.toLowerCase().endsWith(".pdf")) {
            // === PDF 混合解析逻辑 ===
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
        } else {
            // 非 PDF 文件
            log.info("使用 Tika 解析非 PDF 文件： প্রশিক্ষ", filename);
            String text = null;
            try {
                text = tika.parseToString(new ByteArrayInputStream(fileBytes));
                if (!text.trim().isEmpty()) {
                    documents.add(new Document(text));
                }
            } catch (TikaException e) {
                throw new RuntimeException(e);
            }
            if (text.trim().isEmpty()) {
                throw new IllegalArgumentException("Tika 解析后内容为空: " + filename);
            }
            documents.add(new Document(text));
        }

        if (documents.isEmpty() || documents.get(0).getText().trim().isEmpty()) {
            throw new IllegalArgumentException("文档解析后内容为空: " + filename);
        }

        // 3. 中文智能分块
        List<Document> chunks = chineseTextSplitter.split(documents);
        log.info("【智能分块完成】总块数： প্রশিক্ষ", chunks.size());

        // 4. 添加 metadata（核心修复：增加 null 值防御）
        Instant now = Instant.now();
        
        // 定义一个安全的 put 方法
        BiConsumer<Document, Map.Entry<String, Object>> safePut = (doc, entry) -> {
            if (entry.getValue() != null) {
                doc.getMetadata().put(entry.getKey(), entry.getValue());
            }
        };

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String text = chunk.getText();

            // ===== 核心：用户隔离元数据 =====
            // 这里强制转换 String 防止 Long 类型可能的 null 问题
            chunk.getMetadata().put("knowledge_id", String.valueOf(knowledgeId)); 
            chunk.getMetadata().put("user_id", String.valueOf(userId));
            
            // 防御性添加 is_public
            if (isPublic != null) {
                chunk.getMetadata().put("is_public", isPublic);
            } else {
                chunk.getMetadata().put("is_public", "0"); // 默认私有
            }
            
            chunk.getMetadata().put("filename", filename != null ? filename : "unknown");
            String category = inferCategoryFromFilename(filename);
            chunk.getMetadata().put("category", category);
            if (i == 0) {
                log.info("文件 [{}] 被自动归类为: [{}]", filename, category);
            }
            chunk.getMetadata().put("fileSize", fileSize);
            chunk.getMetadata().put("uploadTime", now.toEpochMilli());
            chunk.getMetadata().put("chunkIndex", i);
            chunk.getMetadata().put("totalChunks", chunks.size());

            String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
            chunk.getMetadata().put("preview", preview);

            if (extraMetadata != null) {
                extraMetadata.forEach((k, v) -> {
                    if (v != null) {
                        chunk.getMetadata().put(k, v);
                    }
                });
            }
        }

        // 5. 分批存储到向量库
        int batchSize = 10;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<Document> batch = chunks.subList(i, end);
            vectorStore.add(batch);
            log.info("【写入向量库】已存储 {}-{} / {} 块", i + 1, end, chunks.size());
        }

        return chunks.size();
    }


        /**
         * 根据 sessionId + filename 删除整个文档的所有向量块
         */
        public int deleteDocumentVectors(String sessionId, String filename) {
            // ... (保持不变，或根据需要优化) ...
            // 如果数据已清洗，这里的 similaritySearch 就不会报错了
            try {
                List<Document> matched = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query("delete-placeholder-anything")
                                .topK(9999)
                                .filterExpression("sessionId == '%s' && filename == '%s'".formatted(sessionId, filename))
                                .build()
                );

                if (matched.isEmpty()) return 0;

                List<String> ids = matched.stream().map(Document::getId).toList();
                vectorStore.delete(ids);
                return ids.size();
            } catch (IllegalArgumentException e) {
                log.error("删除向量失败（Metadata包含Null），这通常是脏数据导致的。建议手动清理向量表。错误信息: {}", e.getMessage());
                // 如果是因为 Metadata Null 导致查不出来，那也没法删，只能吞掉异常防止卡死业务流程
                return 0;
            }
        }

    /**
     * 根据 knowledge_id 删除整个文档的所有向量块
     */
    public int deleteDocumentVectors(Long knowledgeId) {
        try {
            List<Document> matched = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("delete-placeholder-anything")
                            .topK(9999)
                            .filterExpression("knowledge_id == '%s'".formatted(knowledgeId.toString()))
                            .build()
            );

            if (matched.isEmpty()) {
                log.warn("未找到需要删除的向量：knowledge_id={}", knowledgeId);
                return 0;
            }

            List<String> ids = matched.stream().map(Document::getId).toList();
            vectorStore.delete(ids);
            log.info("【删除成功】已删除知识库 {} 的 {} 条向量记录", knowledgeId, ids.size());
            return ids.size();
            
        } catch (IllegalArgumentException e) {
            log.error("严重错误：删除向量时发现 Metadata 包含 Null 值，导致无法反序列化。knowledge_id={}。错误: {}", knowledgeId, e.getMessage());
            // 这是一个棘手的情况：因为查都查不出来，怎么删 ID？
            // 此时只能依赖外部手动清理，或者忽略错误继续
            return 0;
        }
    }

//    /**
//     * 根据用户权限搜索向量（带数据隔离）
//     */
//    public List<Document> searchWithUserFilter(String query, Long userId, boolean isAdmin,
//                                               int topK, double threshold) {
//        String filterExpression;
//
//        if (!isAdmin) {
//            filterExpression = String.format(
//                    "(user_id == '0' && is_public == '1') || user_id == '%s'",
//                    userId.toString()
//            );
//        } else {
//            filterExpression = null; // Admin 查所有
//        }
//
//        try {
//            return vectorStore.similaritySearch(
//                    SearchRequest.builder()
//                            .query(query)
//                            .topK(topK)
//                            .similarityThreshold(threshold)
//                            .filterExpression(filterExpression)
//                            .build()
//            );
//        } catch (IllegalArgumentException e) {
//            log.error("检索向量时遇到脏数据（Metadata为Null），已跳过错误。Query: {}", query, e);
//            return new ArrayList<>(); // 降级返回空列表，防止整个 RAG 挂掉
//        }
//    }

    /**
     * 根据用户权限搜索向量（带数据隔离 + 类别过滤）
     * [v4.0 Upgrade] 新增 category 参数
     */
    public List<Document> searchWithUserFilter(String query, Long userId, boolean isAdmin,
                                               int topK, double threshold, String category) {
        String filterExpression = "";

        // 1. 构建基础权限过滤 (Permission Filter)
        if (!isAdmin) {
            filterExpression = String.format(
                    "(user_id == '0' && is_public == '1') || user_id == '%s'",
                    userId.toString()
            );
        }

        // 2. 叠加类别过滤 (Category Filter)
        if (category != null && !category.isBlank()) {
            String categoryFilter = String.format("category == '%s'", category);

            if (filterExpression.isEmpty()) {
                filterExpression = categoryFilter;
            } else {
                // 如果已有权限过滤，则用 AND 拼接: (权限逻辑) && category == 'xxx'
                filterExpression = String.format("(%s) && %s", filterExpression, categoryFilter);
            }
        }

        try {
            var builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);

            // 只有当表达式不为空时才设置 filter
            if (!filterExpression.isEmpty()) {
                builder.filterExpression(filterExpression);
            }

            return vectorStore.similaritySearch(builder.build());

        } catch (IllegalArgumentException e) {
            log.error("检索向量时遇到脏数据（Metadata为Null），已跳过错误。Query: {}", query, e);
            return new ArrayList<>();
        }
    }


    private String inferCategoryFromFilename(String filename) {
        if (filename == null) return "REGULATION"; // 默认兜底
        String name = filename.toLowerCase();

        // 1. 第一优先级：商务/价格 (钱是最敏感的，涉及结算公式和电价)
        if (name.contains("结算") || name.contains("价格") || name.contains("电价") ||
                name.contains("费用") || name.contains("补偿") || name.contains("合约") ||
                name.contains("零售")) {
            return "BUSINESS";
        }

        // 2. 第二优先级：技术/运行 (涉及具体物理参数、设备、曲线)
        if (name.contains("技术") || name.contains("参数") || name.contains("标准") ||
                name.contains("接入") || name.contains("负荷") || name.contains("曲线") ||
                name.contains("储能") || name.contains("虚拟电厂") || name.contains("光伏") ||
                name.contains("新能源") || name.contains("调频")) {
            return "TECHNICAL";
        }

        // 3. 第三优先级：规则/合规 (兜底，涉及管理办法、考核、信用)
        // 包含：规则、细则(非结算类)、办法、通知、指引、监管、评价
        return "REGULATION";
    }

}
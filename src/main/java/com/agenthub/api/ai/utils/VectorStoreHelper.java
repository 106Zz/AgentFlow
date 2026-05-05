package com.agenthub.api.ai.utils;

import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.knowledge.domain.DeleteResult;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.domain.ProcessResult;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Value("${agenthub.pdf.fallback.enabled:true}")
    private boolean pdfFallbackEnabled;

    @Value("${agenthub.pdf.fallback.ghostscript-path:gswin64c}")
    private String ghostscriptPath;

    @Value("${agenthub.pdf.fallback.dpi:200}")
    private int pdfFallbackDpi;

    @Value("${agenthub.pdf.fallback.timeout-seconds:60}")
    private long pdfFallbackTimeoutSeconds;

    // ==================== 常量定义 ====================

    /** 向量存储批次大小（DashScope API 限制最大为 10）*/
    private static final int VECTOR_BATCH_SIZE = 10;

    /** OCR 并发控制：最大并行数 */
    private static final int MAX_PARALLEL_OCR = 4;

    /** OCR 并发控制信号量 */
    private final Semaphore ocrSemaphore = new Semaphore(MAX_PARALLEL_OCR);

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

    /**
     * PDF 解析结果（内部使用）
     * 封装文档列表和页面级统计
     */
    private static class PdfParseResult {
        final List<Document> documents;
        final int totalPages;
        final int successPages;
        final int failedPages;
        final List<Integer> failedPageNums;

        PdfParseResult(List<Document> documents, int totalPages, int successPages,
                       int failedPages, List<Integer> failedPageNums) {
            this.documents = documents;
            this.totalPages = totalPages;
            this.successPages = successPages;
            this.failedPages = failedPages;
            this.failedPageNums = failedPageNums;
        }
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
     * @return 处理结果
     */
    public ProcessResult processAndStoreDocument(MultipartFile file, Long knowledgeId, Long userId,
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
     * @return 处理结果
     */
    public ProcessResult processAndStoreDocument(Resource resource, Long knowledgeId, Long userId,
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
     * @return 处理结果（含页面级统计）
     */
    public ProcessResult processAndStore(byte[] fileBytes, String filename, long fileSize,
                                Long knowledgeId, Long userId, String isPublic,
                                Map<String, Object> extraMetadata) throws IOException {

        log.info("【开始处理文档】{} ({}KB)，用户ID: {}", filename, fileSize / 1024, userId);

        List<Document> documents = new ArrayList<>();
        PdfParseResult pdfResult = null;

        // 1. 文件解析
        if (filename.toLowerCase().endsWith(".pdf")) {
            pdfResult = parsePdf(fileBytes);
            documents = pdfResult.documents;
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

        // 5. 构建处理结果
        ProcessResult result = new ProcessResult();
        result.setChunkCount(chunks.size());

        if (pdfResult != null) {
            result.setTotalPages(pdfResult.totalPages);
            result.setSuccessPages(pdfResult.successPages);
            result.setFailedPages(pdfResult.failedPages);
            result.setFailedPageNums(pdfResult.failedPageNums);

            if (!pdfResult.failedPageNums.isEmpty()) {
                result.setFailureSummary(
                        "PDF 页面资源不完整，PDFBox 渲染失败，失败页码: " + pdfResult.failedPageNums);
            }
        }

        return result;
    }

    /**
     * 解析PDF文件（混合模式：文本提取 + OCR）
     * v5.0 - 返回 PdfParseResult，包含页面级统计
     */
    private PdfParseResult parsePdf(byte[] fileBytes) throws IOException {
        List<Document> documents = new ArrayList<>();
        List<Integer> failedPageNums = Collections.synchronizedList(new ArrayList<>());

        try (PDDocument pdfDoc = Loader.loadPDF(fileBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int totalPages = pdfDoc.getNumberOfPages();
            log.info("PDF共 {} 页，开始页级混合解析...", totalPages);

            // 第一遍：快速文本提取（串行，很快）
            List<Integer> needOcrPages = new ArrayList<>();
            int textExtractSuccess = 0;
            for (int i = 0; i < totalPages; i++) {
                try {
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String pageText = stripper.getText(pdfDoc).trim();

                    if (pageText.length() > ocrMinLength) {
                        Document doc = new Document(pageText);
                        doc.getMetadata().put("page_index", i + 1);
                        doc.getMetadata().put("ocr_used", false);
                        documents.add(doc);
                        textExtractSuccess++;
                    } else {
                        needOcrPages.add(i);
                    }
                } catch (Exception e) {
                    // 文本提取失败，加入 OCR 队列
                    log.warn("第 {} 页文本提取失败，将尝试OCR: {}", i + 1, e.getMessage());
                    needOcrPages.add(i);
                }
            }

            // 第二遍：并行OCR处理（慢操作并行化）
            int ocrSuccess = 0;
            if (!needOcrPages.isEmpty()) {
                log.info("需要OCR的页面: {} 页，开始并行处理...", needOcrPages.size());
                OcrBatchResult ocrResult = processOcrPagesParallel(fileBytes, renderer, needOcrPages);
                documents.addAll(ocrResult.documents);
                ocrSuccess = ocrResult.successCount;
                failedPageNums.addAll(ocrResult.failedPageNums);
            }

            int successPages = textExtractSuccess + ocrSuccess;
            int failedPages = failedPageNums.size();

            if (failedPages > 0) {
                log.warn("【PDF解析统计】总页数: {}, 成功: {}, 失败: {}, 失败页码: {}",
                        totalPages, successPages, failedPages, failedPageNums);
            } else {
                log.info("【PDF解析统计】总页数: {}, 全部成功", totalPages);
            }

            return new PdfParseResult(documents, totalPages, successPages, failedPages, failedPageNums);
        }
    }

    /**
     * OCR 批次处理结果（内部使用）
     */
    private static class OcrBatchResult {
        final List<Document> documents;
        final int successCount;
        final List<Integer> failedPageNums;

        OcrBatchResult(List<Document> documents, int successCount, List<Integer> failedPageNums) {
            this.documents = documents;
            this.successCount = successCount;
            this.failedPageNums = failedPageNums;
        }
    }

    /**
     * 并行OCR处理多个页面
     * v5.0 - 记录失败页码，返回 OcrBatchResult
     */
    private OcrBatchResult processOcrPagesParallel(byte[] pdfBytes, PDFRenderer renderer, List<Integer> pages) {
        log.info("开始OCR处理，共 {} 页，最大并行数: {}", pages.size(), MAX_PARALLEL_OCR);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> failedPageNums = Collections.synchronizedList(new ArrayList<>());

        List<Document> results = pages.parallelStream()
                .map(pageIndex -> {
                    try {
                        // 获取信号量许可，阻塞直到可用
                        ocrSemaphore.acquire();
                        try {
                            log.debug("开始处理第 {} 页 (当前并发: {}/{})",
                                pageIndex + 1,
                                MAX_PARALLEL_OCR - ocrSemaphore.availablePermits(),
                                MAX_PARALLEL_OCR);

                            BufferedImage image = renderPageWithFallback(pdfBytes, renderer, pageIndex);
                            String ocrText = ocrReader.processSingleImage(image);

                            if (ocrText != null && !ocrText.isEmpty()) {
                                Document doc = new Document(ocrText);
                                doc.getMetadata().put("page_index", pageIndex + 1);
                                doc.getMetadata().put("ocr_used", true);
                                successCount.incrementAndGet();
                                return doc;
                            } else {
                                // OCR 返回空文本
                                failedPageNums.add(pageIndex + 1);
                            }
                        } finally {
                            // 无论成功失败都要释放许可
                            ocrSemaphore.release();
                        }
                    } catch (Exception e) {
                        log.error("第 {} 页OCR失败: {}", pageIndex + 1, e.getMessage());
                        failedPageNums.add(pageIndex + 1);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();

        int failCount = failedPageNums.size();
        log.info("OCR处理完成: 成功 {} 页, 失败 {} 页, 失败页码: {}",
                successCount.get(), failCount, failedPageNums);

        return new OcrBatchResult(results, successCount.get(), failedPageNums);
    }

    /**
     * 渲染 PDF 页面。优先使用 PDFBox，失败后尝试 Ghostscript 单页渲染。
     */
    private BufferedImage renderPageWithFallback(byte[] pdfBytes, PDFRenderer renderer, int pageIndex) throws IOException {
        try {
            return renderer.renderImage(pageIndex, 2.0f);
        } catch (Exception pdfBoxError) {
            if (!pdfFallbackEnabled) {
                throw toIOException(pdfBoxError);
            }

            log.warn("PDFBox 渲染第 {} 页失败，尝试 Ghostscript fallback: {}",
                    pageIndex + 1, pdfBoxError.getMessage());

            try {
                return renderPageWithGhostscript(pdfBytes, pageIndex);
            } catch (Exception fallbackError) {
                log.warn("Ghostscript fallback 渲染第 {} 页失败: {}", pageIndex + 1, fallbackError.getMessage());
                throw toIOException(pdfBoxError);
            }
        }
    }

    /**
     * 使用 Ghostscript 将单页 PDF 渲染为 PNG，再读取为 BufferedImage。
     * 使用 ProcessBuilder 参数数组，避免 shell 拼接用户输入。
     */
    private BufferedImage renderPageWithGhostscript(byte[] pdfBytes, int pageIndex) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("agenthub-pdf-fallback-");
        Path inputPdf = tempDir.resolve("input.pdf");
        Path outputPng = tempDir.resolve("page-" + (pageIndex + 1) + ".png");

        try {
            Files.write(inputPdf, pdfBytes);

            List<String> command = new ArrayList<>();
            command.add(ghostscriptPath);
            command.add("-dSAFER");
            command.add("-dBATCH");
            command.add("-dNOPAUSE");
            command.add("-sDEVICE=png16m");
            command.add("-r" + pdfFallbackDpi);
            command.add("-dFirstPage=" + (pageIndex + 1));
            command.add("-dLastPage=" + (pageIndex + 1));
            command.add("-sOutputFile=" + outputPng.toAbsolutePath());
            command.add(inputPdf.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            boolean finished = process.waitFor(pdfFallbackTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Ghostscript 渲染超时");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Ghostscript 退出码: " + process.exitValue());
            }

            BufferedImage image = ImageIO.read(outputPng.toFile());
            if (image == null) {
                throw new IOException("Ghostscript 未生成可读取图片");
            }
            return image;
        } finally {
            deleteQuietly(outputPng);
            deleteQuietly(inputPdf);
            deleteQuietly(tempDir);
        }
    }

    private IOException toIOException(Exception e) {
        if (e instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(e.getMessage(), e);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时文件清理失败: {}", path);
        }
    }

    /**
     * 解析非PDF文件（使用Tika）
     * 注意：每次创建新的 Tika 实例以保证线程安全
     */
    private List<Document> parseNonPdf(byte[] fileBytes, String filename) throws IOException {
        log.info("使用 Tika 解析非 PDF 文件: {}", filename);

        String text;
        try {
            // 每次创建新实例，避免线程安全问题
            Tika tika = new Tika();
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
     * v4.4 - 添加 DashScope 嵌入 API 调用重试机制
     */
    private void storeWithIndex(List<Document> chunks, Long knowledgeId, Long userId) {
        for (int i = 0; i < chunks.size(); i += VECTOR_BATCH_SIZE) {
            int end = Math.min(i + VECTOR_BATCH_SIZE, chunks.size());
            List<Document> batch = chunks.subList(i, end);

            // 存储向量（带重试）
            storeVectorWithRetry(batch, i + 1, end, chunks.size());

            bm25IndexService.batchIndexDocuments(batch, knowledgeId, userId);
        }
    }

    /**
     * 存储向量批次（带指数退避重试）
     * <p>处理 DashScope API 临时网络错误、限流等问题</p>
     *
     * @param batch    当前批次文档
     * @param startNum 起始编号（用于日志）
     * @param endNum   结束编号
     * @param total    总数量
     */
    private void storeVectorWithRetry(List<Document> batch, int startNum, int endNum, int total) {
        final int maxRetries = 3;
        int attempt = 0;
        boolean success = false;

        while (attempt < maxRetries && !success) {
            attempt++;
            try {
                vectorStore.add(batch);
                log.info("【写入向量库】已存储 {}-{} / {} 块 (尝试 {} 次)",
                        startNum, endNum, total, attempt);
                success = true;
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRetryable = isRetryableError(errorMsg);

                log.warn("【写入向量库失败】批次 {}-{}，尝试 {}/{}，错误: {}，可重试: {}",
                        startNum, endNum, attempt, maxRetries,
                        errorMsg != null ? errorMsg.substring(0, Math.min(100, errorMsg.length())) : "unknown",
                        isRetryable);

                if (!isRetryable || attempt >= maxRetries) {
                    // 不可重试的错误或已达最大重试次数
                    throw new RuntimeException(
                            String.format("向量存储失败（批次 %d-%d，尝试 %d 次）: %s",
                                    startNum, endNum, attempt, errorMsg),
                            e
                    );
                }

                // 指数退避：第1次等1秒，第2次等2秒，第3次等4秒
                long backoffMs = 1000L * (1L << (attempt - 1));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("向量存储重试被中断", ie);
                }
            }
        }
    }

    /**
     * 判断错误是否可重试
     */
    private boolean isRetryableError(String errorMsg) {
        if (errorMsg == null) {
            return false;
        }

        String lowerMsg = errorMsg.toLowerCase();

        // 网络相关错误
        if (lowerMsg.contains("failed to respond") ||
                lowerMsg.contains("connection") ||
                lowerMsg.contains("timeout") ||
                lowerMsg.contains("network")) {
            return true;
        }

        // 限流错误
        if (lowerMsg.contains("rate limit") ||
                lowerMsg.contains("429") ||
                lowerMsg.contains("too many requests")) {
            return true;
        }

        // DashScope 服务错误
        if (lowerMsg.contains("dashscope") &&
                (lowerMsg.contains("503") || lowerMsg.contains("502"))) {
            return true;
        }

        return false;
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

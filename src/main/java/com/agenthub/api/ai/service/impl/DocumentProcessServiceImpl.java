package com.agenthub.api.ai.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.agenthub.api.ai.config.LangChain4jConfig;
import com.agenthub.api.ai.utils.QwenOcrDocumentReader;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.common.utils.OssUtils;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.service.impl.KnowledgeBaseServiceImpl;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DocumentProcessServiceImpl {

    private final EmbeddingModel embeddingModel;
    private final OssUtils ossUtils;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QwenOcrDocumentReader ocrReader;
    
    // 使用 Setter 注入 + @Lazy 打破循环依赖
    private KnowledgeBaseServiceImpl knowledgeBaseService;

    // 构造函数注入其他依赖
    public DocumentProcessServiceImpl(
            EmbeddingModel embeddingModel,
            OssUtils ossUtils,
            EmbeddingStore<TextSegment> embeddingStore,
            QwenOcrDocumentReader ocrReader) {
        this.embeddingModel = embeddingModel;
        this.ossUtils = ossUtils;
        this.embeddingStore = embeddingStore;
        this.ocrReader = ocrReader;
    }

    // Setter 注入 + @Lazy
    @Autowired
    @Lazy
    public void setKnowledgeBaseService(KnowledgeBaseServiceImpl knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    private static final int MAX_CHUNK_CHARS = 600;
    private static final int OVERLAP_CHARS = 150;
    private static final int MIN_CHUNK_CHARS = 50;

  @Async("fileProcessExecutor")
  public void processKnowledgeAsync(Long knowledgeId) {
    log.info("【异步向量化任务】开始处理知识库 ID: {}", knowledgeId);

    KnowledgeBase knowledge = null;
    String localPath = null;
    File localFile = null;

    try {
      knowledge = knowledgeBaseService.getById(knowledgeId);
      if (knowledge == null) throw new RuntimeException("知识库记录不存在");

      knowledge.setVectorStatus("1");
      knowledgeBaseService.updateById(knowledge);

      localPath = ossUtils.downloadToTemp(knowledge.getFilePath());
      localFile = new File(localPath);
      Path filePath = Paths.get(localPath);

      List<TextSegment> allSegments = new ArrayList<>();

      String filenameLower = knowledge.getFileName().toLowerCase();

      if (filenameLower.endsWith(".pdf")) {
        handlePdfFile(localFile, knowledge.getFileName(), allSegments);
      } else {
        handleNonPdfFile(filePath, knowledge.getFileName(), allSegments);
      }

      if (allSegments.isEmpty()) {
        throw new RuntimeException("文档解析后无有效内容");
      }

      // 添加 metadata（适配新版 API）
      addCommonMetadataNew(allSegments, knowledge);

      // 向量化 + 分批存储
      List<Embedding> embeddings = embeddingModel.embedAll(allSegments).content();

      int batchSize = 20;
      for (int i = 0; i < allSegments.size(); i += batchSize) {
        int end = Math.min(i + batchSize, allSegments.size());
        List<Embedding> batchEmb = embeddings.subList(i, end);
        List<TextSegment> batchSeg = allSegments.subList(i, end);
        embeddingStore.addAll(batchEmb, batchSeg);
        log.info("【向量入库】{}-{} / {} 块已存储", i + 1, end, allSegments.size());
      }

      knowledge.setVectorStatus("2");
      knowledge.setVectorCount(allSegments.size());
      knowledgeBaseService.updateById(knowledge);

      log.info("【向量化成功】知识库 ID: {}, 文件: {}, 向量段数: {}", knowledgeId, knowledge.getFileName(), allSegments.size());

    } catch (Exception e) {
      log.error("【向量化失败】知识库 ID: {}", knowledgeId, e);
      if (knowledge == null) knowledge = knowledgeBaseService.getById(knowledgeId);
      if (knowledge != null) {
        knowledge.setVectorStatus("3");
        knowledge.setRemark("向量化失败: " + e.getMessage());
        knowledgeBaseService.updateById(knowledge);
      }
    } finally {
      if (localFile != null && localFile.exists()) {
        try {
          localFile.delete();
          log.debug("临时文件删除成功: {}", localPath);
        } catch (Exception ex) {
          log.warn("临时文件删除失败: {}", localPath, ex);
        }
      }
    }
  }

  private void handlePdfFile(File localFile, String filename, List<TextSegment> allSegments) throws Exception {
    try (PDDocument pdfDoc = Loader.loadPDF(localFile)) {
      PDFRenderer renderer = new PDFRenderer(pdfDoc);
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      int totalPages = pdfDoc.getNumberOfPages();
      log.info("PDF 文件 {}，共 {} 页，开始混合解析", filename, totalPages);

      for (int i = 0; i < totalPages; i++) {
        stripper.setStartPage(i + 1);
        stripper.setEndPage(i + 1);
        String pageText = stripper.getText(pdfDoc).trim();

        String extractedText;
        boolean usedOcr = false;

        if (pageText.length() > 30) {
          extractedText = pageText;
        } else {
          log.info("第 {} 页启用 OCR", i + 1);
          BufferedImage image = renderer.renderImage(i, 2.0f);
          extractedText = ocrReader.processSingleImage(image);
          usedOcr = true;
          if (extractedText == null || extractedText.trim().isEmpty()) continue;
        }

        Document pageDoc = Document.from(extractedText);
        pageDoc.metadata()
                .put("page_index", i + 1)
                .put("ocr_used", usedOcr ? "true" : "false");

        allSegments.addAll(chineseSmartSplit(pageDoc));
      }
    }
  }

  private void handleNonPdfFile(Path filePath, String filename, List<TextSegment> allSegments) throws Exception {
    // 使用 TextDocumentParser 替代 ApacheTikaDocumentParser
    Document document = FileSystemDocumentLoader.loadDocument(filePath, new TextDocumentParser());
    if (document.text().trim().isEmpty()) throw new RuntimeException("解析内容为空");
    allSegments.addAll(chineseSmartSplit(document));
  }

  /** 适配 LangChain4j 0.36.2 的 metadata 添加方式（所有值转为 String） */
  private void addCommonMetadataNew(List<TextSegment> segments, KnowledgeBase knowledge) {
    Instant now = Instant.now();
    long uploadTimeMillis = now.toEpochMilli();

    for (int i = 0; i < segments.size(); i++) {
      TextSegment seg = segments.get(i);

      // LangChain4j 0.36.2 的 Metadata.put() 只接受 String 类型
      seg.metadata()
              .put("knowledge_id", String.valueOf(knowledge.getId()))
              .put("user_id", String.valueOf(knowledge.getUserId()))
              .put("is_public", knowledge.getIsPublic())  // 已经是 String
              .put("filename", knowledge.getFileName())
              .put("title", knowledge.getTitle())
              .put("category", knowledge.getCategory())
              .put("tags", knowledge.getTags())
              .put("upload_time", String.valueOf(uploadTimeMillis))
              .put("chunk_index", String.valueOf(i))
              .put("total_chunks", String.valueOf(segments.size()));

      String preview = seg.text().length() > 100
              ? seg.text().substring(0, 100) + "..."
              : seg.text();
      seg.metadata().put("preview", preview);
    }
  }

  // ========== 中文智能分块（适配 LangChain4j 0.36.2）==========
  private List<TextSegment> chineseSmartSplit(Document document) {
    String text = document.text();
    List<String> chunks = new ArrayList<>();
    List<String> separators = List.of("\n\n", "\n", "。", "！", "？", "；", "，", " ", "");
    splitRecursive(text, 0, separators, chunks);

    List<TextSegment> segments = new ArrayList<>();
    for (String chunk : chunks) {
      if (chunk.trim().length() >= MIN_CHUNK_CHARS) {
        TextSegment seg = TextSegment.from(chunk.trim());
        // 手动复制 document 的 metadata（所有值转为 String）
        document.metadata().toMap().forEach((key, value) -> {
          seg.metadata().put(key, String.valueOf(value));
        });
        segments.add(seg);
      }
    }
    return segments;
  }

  private void splitRecursive(String text, int sepIndex, List<String> separators, List<String> chunks) {
    if (sepIndex >= separators.size() - 1) {
      splitByLength(text, chunks);
      return;
    }
    String sep = separators.get(sepIndex);
    String[] parts = text.split(Pattern.quote(sep));
    if (parts.length <= 1) {
      splitRecursive(text, sepIndex + 1, separators, chunks);
      return;
    }
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (i > 0) part = sep + part;
      if (current.length() + part.length() > MAX_CHUNK_CHARS) {
        if (current.length() > MIN_CHUNK_CHARS) {
          chunks.add(current.toString().trim());
        }
        current = new StringBuilder();
      }
      current.append(part);
      if (current.length() >= MAX_CHUNK_CHARS - 100 && i < parts.length - 1) {
        chunks.add(current.toString().trim());
        String overlap = current.length() > OVERLAP_CHARS ? current.substring(current.length() - OVERLAP_CHARS) : current.toString();
        current = new StringBuilder(overlap);
      }
    }
    if (current.length() > MIN_CHUNK_CHARS) {
      chunks.add(current.toString().trim());
    }
  }

  private void splitByLength(String text, List<String> chunks) {
    for (int i = 0; i < text.length(); i += MAX_CHUNK_CHARS - OVERLAP_CHARS) {
      int end = Math.min(i + MAX_CHUNK_CHARS, text.length());
      String chunk = text.substring(i, end).trim();
      if (chunk.length() >= MIN_CHUNK_CHARS) {
        chunks.add(chunk);
      }
    }
  }

  public int deleteKnowledgeVectors(Long knowledgeId) {
    // 新版 Filter API
    Filter filter = new IsEqualTo("knowledge_id", knowledgeId);
    embeddingStore.removeAll(filter);
    log.info("已删除知识库 {} 的所有向量段", knowledgeId);
    return 1;
  }

  @Async("fileProcessExecutor")
  public void batchProcessKnowledge(Long[] knowledgeIds) {
    for (Long id : knowledgeIds) {
      processKnowledgeAsync(id);
    }
  }



}

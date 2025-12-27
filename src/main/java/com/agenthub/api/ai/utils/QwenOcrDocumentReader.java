package com.agenthub.api.ai.utils;


import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import dev.langchain4j.data.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class QwenOcrDocumentReader{


  @Value("${langchain4j.dashscope.api-key}")
  private String apiKey;

  // 统计总 token 消耗
  private int totalInputTokens = 0;
  private int totalOutputTokens = 0;


  public List<Document> read(InputStream inputStream, String filename) {
    List<Document> documents = new ArrayList<>();
    long startTime = System.currentTimeMillis();

    log.info("【OCR/多模态】开始使用 Qwen-VL-Plus 处理扫描版文档: {}", filename);

    // 本文件统计
    int fileInputTokens = 0;
    int fileOutputTokens = 0;

    try {
      byte[] pdfBytes = inputStream.readAllBytes();
      try (PDDocument document = Loader.loadPDF(pdfBytes)) {
        PDFRenderer renderer = new PDFRenderer(document);
        int totalPages = document.getNumberOfPages();

        for (int i = 0; i < totalPages; i++) {
          long pageStartTime = System.currentTimeMillis();
          log.info("正在处理第 {}/{} 页...", i + 1, totalPages);

          // 1. 渲染页面为图片
          BufferedImage image = renderer.renderImageWithDPI(i, 300);

          // 2. 转成 base64
          String base64Image = imageToBase64(image);

          // 3. 调用 DashScope 原生 SDK（返回完整结果）
          MultiModalConversationResult result = callQwenVL(base64Image);

          if (result != null) {
            // 提取文本
            String ocrText = extractText(result);

            // 提取 token 使用情况
            MultiModalConversationUsage usage = result.getUsage();
            if (usage != null) {
              int inputTokens = usage.getInputTokens();
              int outputTokens = usage.getOutputTokens();

              fileInputTokens += inputTokens;
              fileOutputTokens += outputTokens;

              long pageTime = System.currentTimeMillis() - pageStartTime;
              log.info("第 {} 页完成 - Token[输入:{}, 输出:{}, 总计:{}] 耗时:{}ms",
                      i + 1, inputTokens, outputTokens, inputTokens + outputTokens, pageTime);
            }

            if (ocrText != null && !ocrText.trim().isEmpty()) {
              Document doc = new Document(ocrText);
              doc.metadata().put("ocr_source_file", filename);
              doc.metadata().put("ocr_page_index", i + 1);
              doc.metadata().put("ocr_model", "qwen-vl-plus");
              documents.add(doc);
              log.info("第 {} 页 OCR 成功，提取文本长度: {} 字符", i + 1, ocrText.length());
            } else {
              log.warn("第 {} 页 OCR 结果为空", i + 1);
            }
          }
        }

        // 打印文件总计
        totalInputTokens += fileInputTokens;
        totalOutputTokens += fileOutputTokens;
        long totalTime = System.currentTimeMillis() - startTime;

        log.info("========================================");
        log.info("📄 文档: {}", filename);
        log.info("📊 Token 消耗 - 输入:{}, 输出:{}, 总计:{}",
                fileInputTokens, fileOutputTokens, fileInputTokens + fileOutputTokens);
        log.info("⏱️  处理耗时: {}ms ({}秒)", totalTime, totalTime / 1000.0);
        log.info("📈 累计 Token - 输入:{}, 输出:{}, 总计:{}",
                totalInputTokens, totalOutputTokens, totalInputTokens + totalOutputTokens);
        log.info("========================================");
      }
    } catch (Exception e) {
      log.error("Qwen OCR 处理文档 {} 失败", filename, e);
    }
    return documents;
  }

  /**
   * 调用 DashScope 原生 SDK 的 Qwen-VL-Plus（返回完整结果对象）
   */
  private MultiModalConversationResult callQwenVL(String base64Image) {
    try {
      // 构造消息
      MultiModalMessage userMessage = MultiModalMessage.builder()
              .role(Role.USER.getValue())
              .content(Arrays.asList(
                      Map.of("text", "请对该图片进行高精度OCR识别，提取并返回图片中所有的文本内容，保持原文格式、段落、标题和表格结构。输出纯文本，不要添加额外解释。"),
                      Map.of("image", "data:image/jpeg;base64," + base64Image)
              ))
              .build();

      // 构造请求参数
      MultiModalConversationParam param = MultiModalConversationParam.builder()
              .apiKey(apiKey)
              .model("qwen-vl-plus")
              .message(userMessage)
              .topP(0.1)
              .build();

      // 调用 API，返回完整结果
      MultiModalConversation conv = new MultiModalConversation();
      return conv.call(param);

    } catch (ApiException | NoApiKeyException | UploadFileException e) {
      log.error("调用 Qwen-VL API 失败", e);
      return null;
    }
  }

  /**
   * 从结果中提取文本
   */
  private String extractText(MultiModalConversationResult result) {
    if (result != null && result.getOutput() != null && result.getOutput().getChoices() != null) {
      Object textObj = result.getOutput().getChoices().get(0).getMessage().getContent().get(0).get("text");
      return textObj != null ? textObj.toString() : null;
    }
    return null;
  }

  /**
   * BufferedImage 转 base64
   */
  private String imageToBase64(BufferedImage image) throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", baos);
      return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
  }


  /**
   * 处理单张图片 OCR (核心省钱点：图片压缩)
   */
  public String processSingleImage(BufferedImage image) {
    long pageStartTime = System.currentTimeMillis();

    try {
      // 1. 关键优化：Resize 图片
      BufferedImage resizedImage = resizeImageProperly(image, 1536);

      // 2. 转 Base64
      String base64 = imageToBase64(resizedImage);

      // 3. 调用 API (返回完整结果)
      MultiModalConversationResult result = callQwenVL(base64);

      if (result != null) {
        // 4. 提取文本
        String ocrText = extractText(result);

        // 5. 【新增逻辑】：统计 Token 并打印日志
        MultiModalConversationUsage usage = result.getUsage();
        if (usage != null) {
          int inputTokens = usage.getInputTokens();
          int outputTokens = usage.getOutputTokens();

          // ⚠️ 累加到类的全局统计变量中 ⚠️
          this.totalInputTokens += inputTokens;
          this.totalOutputTokens += outputTokens;

          long pageTime = System.currentTimeMillis() - pageStartTime;
          log.debug("单页混合 OCR 完成 - Token[输入:{}, 输出:{}, 总计:{}] 耗时:{}ms",
                  inputTokens, outputTokens, inputTokens + outputTokens, pageTime);
        }

        return ocrText;
      }
      return "";
    } catch (Exception e) {
      log.error("单页 OCR 失败", e);
      return "";
    }
  }

  /**
   * 智能缩放图片：保持比例，长边不超过 maxSide
   */
  private BufferedImage resizeImageProperly(BufferedImage original, int maxSide) {
    int width = original.getWidth();
    int height = original.getHeight();

    // 如果原图本身就很小，直接返回
    if (width <= maxSide && height <= maxSide) {
      return original;
    }

    // 计算目标尺寸
    int newWidth, newHeight;
    if (width > height) {
      newWidth = maxSide;
      newHeight = (int) (height * ((double) maxSide / width));
    } else {
      newHeight = maxSide;
      newWidth = (int) (width * ((double) maxSide / height));
    }

    // 高质量缩放
    BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = resized.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(original, 0, 0, newWidth, newHeight, null);
    g.dispose();

    return resized;
  }

  /**
   * 【新增方法】在所有文档处理结束后调用，打印最终累计值并重置
   */
  public void printFinalTokenSummaryAndReset() {
    // 1. 打印最终总计
    log.info("========================================");
    log.info("🎉🎉🎉 所有文档处理结束 🎉🎉🎉");
    log.info("FINAL TOKEN SUMMARY:");
    log.info("累计总输入 Token: {}", this.totalInputTokens);
    log.info("累计总输出 Token: {}", this.totalOutputTokens);
    log.info("累计总计 Token: {}", this.totalInputTokens + this.totalOutputTokens);
    log.info("========================================");

    // 2. 可选：重置计数器，以便下次启动应用时重新开始统计
    this.totalInputTokens = 0;
    this.totalOutputTokens = 0;
  }




}

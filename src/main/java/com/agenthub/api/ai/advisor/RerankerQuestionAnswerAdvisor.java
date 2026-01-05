package com.agenthub.api.ai.advisor;



import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Data
public class RerankerQuestionAnswerAdvisor implements CallAdvisor, StreamAdvisor {

  private final VectorStore vectorStore;
  private final DashScopeRerankerConfig rerankerService;
  private final int recallTopK;
  private final int finalTopN;
  private final double similarityThreshold;
  private final String filterExpression;

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
    //1.获取用户问题
    Prompt prompt = chatClientRequest.prompt();
    UserMessage userMessage = prompt.getInstructions().stream()
            .filter(m -> m instanceof UserMessage)
            .map(m -> (UserMessage) m)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No UserMessage found in prompt"));
    String userText = userMessage.getText();

    log.info("【Reranker Advisor】开始处理问题: {}", userText);

    //2.获取 adviseContext 中的参数
    Map<String, Object> adviseContext = chatClientRequest.context();
    String currentUser = (String) adviseContext.getOrDefault("current_user", "default");

    // 3. 构建过滤表达式
    String actualFilter = filterExpression.replace("{current_user}", currentUser);

    //4.向量检索
    SearchRequest searchRequest = SearchRequest.builder()
            .query(userText)
            .topK(recallTopK)
            .similarityThreshold(similarityThreshold)
            .filterExpression(actualFilter)
            .build();

    List<Document> candidates = vectorStore.similaritySearch(searchRequest);
    log.info("【Reranker Advisor】向量检索完成，召回 {} 个候选文档", candidates.size());

    //5.Reranker重排序
    List<Document> rerankedDocs = rerankerService.rerank(userText, candidates, finalTopN);
    log.info("【Reranker Advisor】Rerank 完成，保留 {} 个高质量文档", rerankedDocs.size());

    // 6. 构建上下文
    String context = buildContext(rerankedDocs);

    // 7. 修改 Prompt，注入检索到的文档
    Prompt enhancedPrompt = enhancePrompt(prompt, context);

    // 创建新的 Request
    ChatClientRequest newRequest = chatClientRequest.mutate()
            .prompt(enhancedPrompt)
            .build();

    // 继续执行
    return callAdvisorChain.nextCall(newRequest);
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
    // 流式处理逻辑与 adviseCall 相同
    Prompt prompt = chatClientRequest.prompt();
    UserMessage userMessage = prompt.getInstructions().stream()
            .filter(m -> m instanceof UserMessage)
            .map(m -> (UserMessage) m)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No UserMessage found in prompt"));
    String userText = userMessage.getText();

    log.info("【Reranker Advisor Stream】开始处理问题: {}", userText);

    // 获取参数
    Map<String, Object> adviseContext = chatClientRequest.context();
    String currentUser = (String) adviseContext.getOrDefault("current_user", "default");
    String actualFilter = filterExpression.replace("{current_user}", currentUser);

    // 向量检索
    SearchRequest searchRequest = SearchRequest.builder()
            .query(userText)
            .topK(recallTopK)
            .similarityThreshold(similarityThreshold)
            .filterExpression(actualFilter)
            .build();

    List<Document> candidates = vectorStore.similaritySearch(searchRequest);
    log.info("【Reranker Advisor Stream】向量检索完成，召回 {} 个候选文档", candidates.size());

    // Reranker 重排
    List<Document> rerankedDocs = rerankerService.rerank(userText, candidates, finalTopN);
    log.info("【Reranker Advisor Stream】Rerank 完成，保留 {} 个高质量文档", rerankedDocs.size());

    // 构建上下文
    String context = buildContext(rerankedDocs);

    // 增强 Prompt
    Prompt enhancedPrompt = enhancePrompt(prompt, context);

    // 创建新的 Request
    ChatClientRequest newRequest = chatClientRequest.mutate()
            .prompt(enhancedPrompt)
            .build();

    // 继续执行
    return streamAdvisorChain.nextStream(newRequest);
  }

  /**
   * 构建文档上下文（手写方法）
   * 将检索到的文档转换为文本格式
   */
  private String buildContext(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return "（未检索到相关文档）";
    }

    return documents.stream()
            .map(doc -> {
              // 获取文件名
              String filename = (String) doc.getMetadata().getOrDefault("filename", "未知文件");

              // ✅ 过滤掉 URL（如果 filename 是 URL，则使用"未知文件"）
              if (filename.startsWith("http://") || filename.startsWith("https://")) {
                filename = "未知文件";
              }

              // 获取 Rerank 分数（如果有）
              Double rerankScore = (Double) doc.getMetadata().get("rerank_score");
              String scoreInfo = rerankScore != null
                      ? String.format("（相关度: %.2f）", rerankScore)
                      : "";

              // 获取文档内容
              String content = doc.getText();

              // 格式化输出
              return String.format("【文件：%s】%s\n%s", filename, scoreInfo, content);
            })
            .collect(Collectors.joining("\n\n---\n\n"));
  }

  /**
   * 增强 Prompt（手写方法）
   * 将检索到的文档注入到 SystemMessage 中
   */
  private Prompt enhancePrompt(Prompt originalPrompt, String context) {
    // 获取原始的所有消息
    List<Message> originalMessages = originalPrompt.getInstructions();
    List<Message> newMessages = new ArrayList<>();

    boolean systemMessageFound = false;

    // 遍历所有消息，找到 SystemMessage 并增强
    for (Message msg : originalMessages) {
      if (msg instanceof SystemMessage systemMsg) {
        // 增强 SystemMessage，注入检索到的文档
        String enhancedContent = systemMsg.getText() +
                "\n\n## 检索到的知识库内容：\n\n" + context;

        newMessages.add(new SystemMessage(enhancedContent));
        systemMessageFound = true;
      } else {
        // 其他消息保持不变
        newMessages.add(msg);
      }
    }

    // 如果没有找到 SystemMessage，在开头添加一个
    if (!systemMessageFound) {
      newMessages.add(0, new SystemMessage("## 检索到的知识库内容：\n\n" + context));
    }

    // 创建新的 Prompt（保留原始的 options）
    return new Prompt(newMessages, originalPrompt.getOptions());
  }

  @Override
  public String getName() {
    return "RerankerQuestionAnswerAdvisor";
  }

  @Override
  public int getOrder() {
    return 0;
  }
}

package com.agenthub.api.ai.service.impl;

import com.agenthub.api.ai.advisor.RerankerQuestionAnswerAdvisor;
import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import com.agenthub.api.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RAG聊天服务实现
 * 负责根据用户权限动态构建ChatClient
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RagChatServiceImpl {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final ChatMemoryRepository chatMemoryRepository;
    private final DashScopeRerankerConfig rerankerService;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    //    private static final String SYSTEM_PROMPT = """
//             # 角色
//                                                                        你是一位专业的知识库分析师，能够根据提供的知识库数据信息，提供专业级的回复解答。
//                                                                       \s
//                                                                        ## 技能
//                                                                       \s
//                                                                        ### 技能 1: 数据检索与分析
//                                                                        - 从知识库中检索相关信息
//                                                                        - 分析检索到的数据，确保其准确性和相关性
//                                                                        - 优先使用知识库中的信息回答问题
//                                                                       \s
//                                                                        ### 技能 2: 专业级回复
//                                                                        - 基于检索到的数据，提供专业、准确、详细的解答
//                                                                        - 确保回答内容符合用户的提问意图
//                                                                        - 使用清晰、简洁的语言，确保用户易于理解
//                                                                       \s
//                                                                        ### 技能 3: 规范的输出格式
//                                                                        - 回答内容应包括：问题概述、详细解答、引用来源
//                                                                        - 引用来源格式：在回答中用 [1]、[2] 等数字标记引用的文档
//                                                                        - 在回答开头列出所有引用的文件名，格式：
//                                                                         \s
//                                                                          📄 参考文档：
//                                                                          [1] 文件名1
//                                                                          [2] 文件名2
//                                                                       \s
//                                                                        ## 限制
//                                                                        - 所有回答必须基于知识库数据信息
//                                                                        - 如果知识库中没有相关信息，可以使用通用知识，但需明确标注：「🌐 来源：通用知识」
//                                                                        - 如果完全不确定，诚实告知用户
//                                                                        - 输出格式必须规范，包括问题概述、详细解答和引用来源
//                                                                        - 请确保提供详细、有深度的回答，同时尽量使用自然段落而不是 Markdown 格式。
//                                                                       \s
//                                                                        请确保在回答中始终遵循上述要求，以提供高质量的专业级回复。
//        """;

    private static final String SYSTEM_PROMPT = """
        # 角色定位
        你是一位面向企业客户的专业知识库分析师，能够基于知识库数据提供高质量、专业级的解答。你的回答需要详细、准确、有深度。
        
        # 核心要求
        
        1. 内容要求（最重要）
           - 提供详细、完整的分析
           - 包含所有相关数据和信息
           - 进行必要的推理和计算
           - 给出明确的结论
        
        2. 格式要求（次要）
           - 使用自然段落，不使用 Markdown 格式符号
           - 用空行分隔段落
           - 用 [1] [2] 标记引用来源
        
        # 输出结构
        
        第一部分：参考文档
        📄 参考文档：
        [1] 完整文件名1.pdf
        [2] 完整文件名2.pdf
        
        第二部分：详细回答
        用自然段落提供完整、详细的回答。包括：
        - 直接回答用户问题
        - 提供相关的数据和事实
        - 进行必要的分析和推理
        - 说明数据来源和依据
        - 给出明确的结论
        
        # 格式规范
        
        允许使用：
        - 自然段落（用空行分隔）
        - 引用标记：[1] [2]
        - 表情符号：📄 🌐
        
        不要使用：
        - Markdown 标题（### 等）
        - 粗体斜体（** * 等）
        - 项目符号（- * 等）
        - 数字列表（1. 2. 等）
        
        # 重要提示
        
        内容的完整性和准确性比格式更重要。请确保提供详细、有深度的回答，同时尽量使用自然段落而不是 Markdown 格式。
        
        如果知识库完全没有相关信息，在最终答案中写：抱歉，当前知识库暂未收录该内容。
        
        现在开始回答用户问题。
        """;

    /**
     * 根据当前用户权限创建ChatClient
     * 
     * @param sessionId 会话ID（用于对话记忆）
     * @return 配置好的ChatClient
     */
    public ChatClient createUserChatClient(String sessionId) {
        // 获取当前用户信息
        Long userId = SecurityUtils.getUserId();
        boolean isAdmin = SecurityUtils.isAdmin();
        
        log.debug("创建ChatClient - 用户ID: {}, 是否管理员: {}, 会话ID: {}", userId, isAdmin, sessionId);
        
        // 构建过滤表达式
        String filterExpression = buildFilterExpression(userId, isAdmin);
        
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        // RAG 检索 + Reranker（带用户权限过滤）
                        new RerankerQuestionAnswerAdvisor(
                                vectorStore,
                                rerankerService,
                                50,  // 召回 50 个候选
                                5,   // Rerank 后保留 5 个
                                0.5, // 相似度阈值
                                filterExpression  // 动态过滤表达式
                        ),
                        
                        // 对话记忆（基于sessionId）
                        MessageChatMemoryAdvisor.builder(
                                MessageWindowChatMemory.builder()
                                        .chatMemoryRepository(chatMemoryRepository)
                                        .maxMessages(20)
                                        .build()
                        )
                        .conversationId(sessionId)  // 使用sessionId作为对话ID
                        .build(),
                        
                        // 日志输出
                        new SimpleLoggerAdvisor()
                )
                .build();
    }

    /**
     * 构建用户权限过滤表达式
     */
    private String buildFilterExpression(Long userId, boolean isAdmin) {
        if (isAdmin) {
            // 管理员：可以看到所有文档
            log.debug("管理员模式：无过滤");
            return null;
        }
        
        // 普通用户：只能看到全局公开的 + 自己的
        String filter = String.format(
            "(user_id == 0 && is_public == '1') || user_id == %d",
            userId
        );
        log.debug("用户过滤表达式: {}", filter);
        return filter;
    }

    /**
     * 发送消息并获取回复
     * 
     * @param sessionId 会话ID
     * @param question 用户问题
     * @return AI回复
     */
    public String chat(String sessionId, String question) {
        ChatClient client = createUserChatClient(sessionId);
        
        return client.prompt()
                .user(question)
                .call()
                .content();
    }

    /**
     * 流式回复
     */
    public reactor.core.publisher.Flux<String> chatStream(String sessionId, String question) {
        ChatClient client = createUserChatClient(sessionId);
        
        return client.prompt()
                .user(question)
                .stream()
                .content();
    }
}

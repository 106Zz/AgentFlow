# LangChain4j 迁移计划

## 已完成 ✅

1. **pom.xml** - 依赖已更新
   - 移除 Spring AI 依赖
   - 添加 LangChain4j 依赖（core, dashscope, pgvector, embeddings）

2. **配置文件** - application-local.yml
   - 移除 Spring AI 配置
   - 添加 LangChain4j 配置

3. **配置类** - LangChain4jConfig.java
   - ChatLanguageModel Bean
   - EmbeddingModel Bean  
   - EmbeddingStore Bean

4. **新服务** - RagChatServiceImplV2.java
   - 基于 LangChain4j 的 RAG 实现
   - 支持文档检索和上下文构建

## 待重构 🔄

### 高优先级（阻塞编译）

1. **RagChatServiceImpl.java** - 旧版服务
   - 删除或重命名为 RagChatServiceImplOld.java
   - 使用新的 RagChatServiceImplV2

2. **VectorMetadataUtils.java** - 元数据工具类
   - 将 `org.springframework.ai.document.Document` 改为 `dev.langchain4j.data.segment.TextSegment`
   - 更新元数据处理逻辑

3. **ChatHistoryServiceImpl.java** - 聊天历史服务
   - 移除 ChatMemoryRepository 依赖
   - 使用自定义的历史记录管理

4. **DocumentProcessServiceImpl.java** - 文档处理服务
   - 更新文档分割逻辑
   - 使用 LangChain4j 的 DocumentSplitter

5. **VectorStoreHelper.java** - 向量存储助手
   - 更新为 LangChain4j 的 EmbeddingStore API

### 中优先级（功能增强）

6. **QueryRewriteAdvisor.java** - 查询改写
   - 删除或使用 LangChain4j 的 ChatMemory

7. **RerankerQuestionAnswerAdvisor.java** - 重排序
   - 集成到新的 RAG 流程中

8. **ChatClientConfig.java** - 聊天客户端配置
   - 删除或改为 LangChain4j 配置

9. **DashScopeRerankerConfig.java** - Reranker 配置
   - 保留，可能需要适配

### 低优先级（可选）

10. **ChatController.java** - 控制器
    - 更新服务注入（使用 RagChatServiceImplV2）

11. **ChatServiceImpl.java** - 聊天服务
    - 更新服务调用

## API 映射对照表

| Spring AI | LangChain4j |
|-----------|-------------|
| `ChatModel` | `ChatLanguageModel` |
| `EmbeddingModel` | `EmbeddingModel` |
| `VectorStore` | `EmbeddingStore<TextSegment>` |
| `Document` | `TextSegment` |
| `ChatClient` | 直接使用 `ChatLanguageModel` |
| `Prompt` | `List<ChatMessage>` |
| `UserMessage` | `UserMessage` |
| `SystemMessage` | `SystemMessage` |
| `AiMessage` | `AiMessage` |

## 下一步行动

1. 重命名旧文件（添加 .old 后缀）
2. 重构 VectorMetadataUtils
3. 重构 DocumentProcessServiceImpl
4. 重构 VectorStoreHelper
5. 测试编译
6. 测试运行

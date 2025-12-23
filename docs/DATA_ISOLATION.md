# 数据隔离方案设计

## 概述

本系统实现了基于用户ID的多租户数据隔离，确保：
1. 管理员可以查看和管理所有数据
2. 普通用户只能访问自己上传的文档和公开的全局文档
3. 向量检索时自动过滤用户无权访问的数据

## 表结构设计

### 1. 用户表 (sys_user)
```sql
user_id BIGINT PRIMARY KEY  -- 用户唯一标识
role VARCHAR(20)            -- admin/user
```

### 2. 知识库元数据表 (knowledge_base)
```sql
id BIGINT PRIMARY KEY
user_id BIGINT              -- 0=全局知识库，其他=用户私有
is_public CHAR(1)           -- 0=私有，1=公开
vector_status CHAR(1)       -- 向量化状态
vector_count INT            -- 向量块数量
```

### 3. 向量存储表 (vector_store) - Spring AI自动创建
```sql
id UUID PRIMARY KEY
content TEXT
metadata JSONB              -- 包含 user_id, knowledge_id, is_public 等
embedding VECTOR(1536)
```

### 4. 聊天历史表 (chat_history)
```sql
id BIGINT PRIMARY KEY
user_id BIGINT              -- 用户ID
session_id VARCHAR(100)     -- 会话ID
```

### 5. 文件上传日志表 (file_upload_log)
```sql
id BIGINT PRIMARY KEY
knowledge_id BIGINT         -- 关联知识库
user_id BIGINT              -- 上传用户
upload_status VARCHAR(20)   -- 上传状态
process_status VARCHAR(20)  -- 处理状态
```

## 数据隔离实现

### 1. 文件上传时的标记

```java
// 上传文件时
KnowledgeBase knowledge = new KnowledgeBase();
if (SecurityUtils.isAdmin()) {
    knowledge.setUserId(0L);      // 管理员上传 -> 全局知识库
    knowledge.setIsPublic("1");   // 默认公开
} else {
    knowledge.setUserId(SecurityUtils.getUserId());  // 用户上传 -> 私有
    knowledge.setIsPublic("0");   // 默认私有
}
```

### 2. 向量化时的元数据注入

```java
// 向量化时，将用户信息写入 metadata
DocumentMetadata metadata = DocumentMetadata.builder()
    .knowledgeId(knowledge.getId())
    .userId(knowledge.getUserId())
    .isPublic(knowledge.getIsPublic())
    .fileName(knowledge.getFileName())
    .category(knowledge.getCategory())
    .build();

Document document = VectorStoreUtils.createDocument(content, metadata);
vectorStore.add(List.of(document));
```

### 3. 检索时的过滤

```java
// 构建用户过滤条件
String filter = VectorStoreUtils.buildUserFilter(userId, isAdmin);

// 管理员：filter = null（查看所有）
// 普通用户：filter = "(user_id = 0 AND is_public = '1') OR user_id = 123"

SearchRequest request = SearchRequest.query(question)
    .withTopK(5)
    .withSimilarityThreshold(0.7)
    .withFilterExpression(filter);  // 关键：应用过滤条件

List<Document> results = vectorStore.similaritySearch(request);
```

### 4. 知识库列表查询的隔离

```java
// 管理员查询
public PageResult<KnowledgeBase> selectKnowledgePage(KnowledgeBase knowledge, PageQuery pageQuery) {
    // 不加 user_id 过滤，查询所有
    return page(pageQuery.build(), wrapper);
}

// 普通用户查询
public PageResult<KnowledgeBase> selectUserKnowledgePage(Long userId, KnowledgeBase knowledge, PageQuery pageQuery) {
    wrapper.and(w -> w
        .eq(KnowledgeBase::getUserId, 0)           // 全局知识库
        .eq(KnowledgeBase::getIsPublic, "1")       // 且公开
        .or()
        .eq(KnowledgeBase::getUserId, userId)      // 或者是自己的
    );
    return page(pageQuery.build(), wrapper);
}
```

## 数据流转过程

### 上传流程
```
1. 用户上传文件
   ↓
2. 保存到 knowledge_base 表（标记 user_id）
   ↓
3. 记录到 file_upload_log 表
   ↓
4. 异步处理：解析文件 → 分块 → 向量化
   ↓
5. 向量数据写入 vector_store 表（metadata 包含 user_id）
   ↓
6. 更新 knowledge_base.vector_status = '2'（已完成）
```

### 检索流程
```
1. 用户发起问题
   ↓
2. 根据用户角色构建过滤条件
   ↓
3. 向量检索（自动过滤无权访问的文档）
   ↓
4. 返回结果（只包含用户可见的文档）
   ↓
5. 保存到 chat_history 表（标记 user_id）
```

## 权限矩阵

| 操作 | 管理员 | 普通用户 |
|------|--------|----------|
| 查看全局知识库 | ✅ | ✅（仅公开的） |
| 查看其他用户的私有知识库 | ✅ | ❌ |
| 查看自己的知识库 | ✅ | ✅ |
| 上传到全局知识库 | ✅ | ❌ |
| 上传到个人知识库 | ✅ | ✅ |
| 删除任意知识库 | ✅ | ❌ |
| 删除自己的知识库 | ✅ | ✅ |
| 检索全局知识 | ✅ | ✅（仅公开的） |
| 检索个人知识 | ✅ | ✅（仅自己的） |

## 安全考虑

### 1. SQL注入防护
- 使用 MyBatis-Plus 的 LambdaQueryWrapper
- 参数化查询，不拼接SQL

### 2. 越权访问防护
```java
// 在 Service 层检查权限
public KnowledgeBase getById(Long id) {
    KnowledgeBase knowledge = super.getById(id);
    
    // 检查权限
    if (!SecurityUtils.isAdmin()) {
        Long userId = SecurityUtils.getUserId();
        if (!knowledge.getUserId().equals(userId) 
            && !(knowledge.getUserId().equals(0L) && "1".equals(knowledge.getIsPublic()))) {
            throw new ServiceException(403, "无权访问该知识库");
        }
    }
    
    return knowledge;
}
```

### 3. 向量检索防护
- 通过 `filterExpression` 在数据库层面过滤
- 不依赖应用层过滤，防止绕过

### 4. 文件访问防护
- 文件路径不直接暴露给前端
- 通过接口下载，验证权限后返回文件流

## 测试场景

### 场景1：管理员上传文件
```
输入：admin 上传 "2026年电力政策.pdf"
预期：
- knowledge_base.user_id = 0
- knowledge_base.is_public = '1'
- 所有用户都能检索到
```

### 场景2：普通用户上传文件
```
输入：user1 上传 "企业内部资料.pdf"
预期：
- knowledge_base.user_id = user1.id
- knowledge_base.is_public = '0'
- 只有 user1 和 admin 能检索到
```

### 场景3：用户检索
```
输入：user1 搜索 "电力政策"
预期：
- 返回全局公开的文档
- 返回 user1 自己上传的文档
- 不返回其他用户的私有文档
```

### 场景4：越权访问
```
输入：user1 尝试访问 user2 的私有文档
预期：
- 返回 403 错误
- 日志记录越权尝试
```

## 总结

通过在 **knowledge_base 表** 和 **vector_store 的 metadata** 中同时标记 `user_id`，实现了：
1. ✅ 元数据层面的隔离（knowledge_base 表）
2. ✅ 向量数据层面的隔离（vector_store 表）
3. ✅ 检索层面的自动过滤（filterExpression）
4. ✅ 完整的权限控制（Spring Security + 自定义校验）

这样既保证了数据安全，又不影响检索性能。

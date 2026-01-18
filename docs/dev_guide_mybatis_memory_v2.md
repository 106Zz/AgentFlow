# 开发指南：MyBatis-Plus 版持久化记忆仓库 (v2.0)

**背景**: 为了解决 Redis 环境问题并符合生产级代码规范，我们将使用 MyBatis-Plus 重构 AI 记忆存储层。
**目标**: 建立标准的 Entity-Mapper-Repository 结构，利用 PostgreSQL 的 JSONB 能力存储复杂对话结构。

## 1. 数据库设计 (Schema)

我们需要一张符合项目规范（包含 `create_time`, `del_flag` 等字段）的表。

**SQL 脚本**:
```sql
-- AI 引擎上下文记忆表
CREATE TABLE IF NOT EXISTS sys_ai_memory (
        id BIGINT PRIMARY KEY,                        -- 雪花算法 ID
        session_id VARCHAR(64) NOT NULL,              -- 会话 ID (唯一索引)
         user_id BIGINT DEFAULT 0,                     -- 用户 ID (新增)
         messages_json TEXT NOT NULL,                  -- 对话上下文 (JSON 格式)
    
         -- 标准审计字段
         create_by VARCHAR(64) DEFAULT '',
         create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         update_by VARCHAR(64) DEFAULT '',
         update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
         remark VARCHAR(500),
         del_flag INT DEFAULT 0
     );


-- 唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_memory_session ON sys_ai_memory(session_id);
-- 用户索引 (用于按用户查询)
CREATE INDEX IF NOT EXISTS idx_ai_memory_user ON sys_ai_memory(user_id);

COMMENT ON TABLE sys_ai_memory IS 'AI引擎上下文记忆表';

```

---

## 2. 代码实现步骤

### Step 1: 创建 Domain 实体

**路径**: `src/main/java/com/agenthub/api/ai/domain/AiMemory.java`

继承 `BaseEntity`，使用 MP 注解。

```java
package com.agenthub.api.ai.domain;

import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_ai_memory")
public class AiMemory extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话唯一标识
     */
    private String sessionId;

    /**
     * 消息列表的 JSON 序列化字符串
     * 为什么存 JSON？因为 Message 对象包含 UserMessage, ToolMessage 等多态结构，
     * 关系型拆表极其复杂且难以维护，JSONB 是存储此类半结构化数据的最佳实践。
     */
    private String messagesJson;
}
```

### Step 2: 创建 Mapper 接口

**路径**: `src/main/java/com/agenthub/api/ai/mapper/AiMemoryMapper.java`

```java
package com.agenthub.api.ai.mapper;

import com.agenthub.api.ai.domain.AiMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiMemoryMapper extends BaseMapper<AiMemory> {
}
```

### Step 3: 实现 Repository (适配器模式)

**路径**: `src/main/java/com/agenthub/api/ai/config/MybatisChatMemoryRepository.java`

这里是连接 Spring AI `ChatMemoryRepository` 接口与 MyBatis-Plus `Mapper` 的桥梁。

```java
package com.agenthub.api.ai.config;

import com.agenthub.api.ai.domain.AiMemory;
import com.agenthub.api.ai.mapper.AiMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Primary
@Repository
@RequiredArgsConstructor
public class MybatisChatMemoryRepository implements ChatMemoryRepository {

    private final AiMemoryMapper aiMemoryMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> findConversationIds() {
        return aiMemoryMapper.selectList(new LambdaQueryWrapper<AiMemory>()
                .select(AiMemory::getSessionId))
                .stream()
                .map(AiMemory::getSessionId)
                .toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        AiMemory memory = aiMemoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getSessionId, conversationId));

        if (memory == null || memory.getMessagesJson() == null) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(memory.getMessagesJson(), new TypeReference<List<Message>>() {});
        } catch (Exception e) {
            log.error("反序列化记忆失败: {}", conversationId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(messages);
            
            // 查一下是否存在
            AiMemory existing = aiMemoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                    .eq(AiMemory::getSessionId, conversationId));

            if (existing != null) {
                existing.setMessagesJson(json);
                aiMemoryMapper.updateById(existing);
            } else {
                AiMemory newMemory = new AiMemory();
                newMemory.setSessionId(conversationId);
                newMemory.setMessagesJson(json);
                aiMemoryMapper.insert(newMemory);
            }
            log.debug("记忆保存成功 (MP): {}", conversationId);
        } catch (Exception e) {
            log.error("记忆保存失败", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        aiMemoryMapper.delete(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getSessionId, conversationId));
    }
}
```

### Step 4: 清理旧代码

删除之前创建的临时文件：
- `src/main/java/com/agenthub/api/ai/config/JdbcChatMemoryRepository.java` (如果不想要了)
- 确认 `ChatClientConfig.java` 已经清除了 Redis 相关 Bean 定义。

---

## 3. 架构师答疑

**Q: 为什么要存 JSON 字符串而不是拆分成关系表？**
A: Spring AI 的 `Message` 对象是多态的（UserMessage, SystemMessage, ToolMessage 包含函数调用参数等）。如果用关系型数据库拆分，需要 `message`, `tool_calls`, `properties` 多张表关联，且每次 Spring AI 升级（如 1.0.0-M5）对象结构变化都会导致 Schema 迁移地狱。**将整个上下文对象序列化存储是业界处理 Chat Memory 的标准模式**（LangChain 也是这么做的）。

**Q: 这符合生产规范吗？**
A: 是的。
1.  **数据层**: 继承了 `BaseEntity`，拥有了审计字段。
2.  **访问层**: 使用了 `Mapper` 接口，符合项目整体风格。
3.  **扩展性**: 未来如果需要分库分表，MyBatis-Plus 插件也更容易支持。

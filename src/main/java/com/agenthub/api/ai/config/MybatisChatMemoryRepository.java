package com.agenthub.api.ai.config;

import com.agenthub.api.ai.domain.AiMemory;
import com.agenthub.api.ai.mapper.AiMemoryMapper;
import com.agenthub.api.common.utils.SecurityUtils;
import com.agenthub.api.framework.config.SpringAIMixIns;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MyBatis-Plus 的会话记忆仓库
 * 替代 JDBC/Redis 实现，符合项目架构规范
 */
@Slf4j
@Primary
@Repository
public class MybatisChatMemoryRepository implements ChatMemoryRepository {

    private final AiMemoryMapper aiMemoryMapper;
    private final ObjectMapper internalObjectMapper;

    public MybatisChatMemoryRepository(AiMemoryMapper aiMemoryMapper, ObjectMapper objectMapper) {
        this.aiMemoryMapper = aiMemoryMapper;
        // 创建独立的 ObjectMapper 副本并注册 MixIns，解决反序列化报错问题
        this.internalObjectMapper = objectMapper.copy();
        this.internalObjectMapper.addMixIn(UserMessage.class, SpringAIMixIns.UserMessageMixIn.class);
        this.internalObjectMapper.addMixIn(AssistantMessage.class, SpringAIMixIns.AssistantMessageMixIn.class);
        this.internalObjectMapper.addMixIn(SystemMessage.class, SpringAIMixIns.SystemMessageMixIn.class);
    }

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
        try {
            AiMemory memory = aiMemoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                    .eq(AiMemory::getSessionId, conversationId));

            if (memory == null || memory.getMessagesJson() == null || memory.getMessagesJson().isEmpty()) {
                return new ArrayList<>();
            }

            // 手动处理多态反序列化
            List<Message> messages = new ArrayList<>();
            JsonNode rootNode = internalObjectMapper.readTree(memory.getMessagesJson());
            
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    // 兼容性处理：Spring AI M4 版本序列化与反序列化字段不一致问题
                    if (node.has("text")) {
                        ObjectNode objectNode = (ObjectNode) node;
                        String textVal = node.get("text").asText();
                        
                        if (!node.has("content")) {
                            objectNode.put("content", textVal);
                        }
                        if (!node.has("textContent")) {
                            objectNode.put("textContent", textVal);
                        }
                    }

                    String type = node.has("messageType") ? node.get("messageType").asText() : "USER";
                    Message message = switch (type) {
                        case "USER" -> internalObjectMapper.treeToValue(node, UserMessage.class);
                        case "ASSISTANT" -> internalObjectMapper.treeToValue(node, AssistantMessage.class);
                        case "SYSTEM" -> internalObjectMapper.treeToValue(node, SystemMessage.class);
                        case "TOOL" -> internalObjectMapper.treeToValue(node, ToolResponseMessage.class);
                        default -> internalObjectMapper.treeToValue(node, UserMessage.class);
                    };
                    if (message != null) {
                        messages.add(message);
                    }
                }
            }
            return messages;

        } catch (Exception e) {
            log.error("读取会话记忆失败: sessionId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        try {
            String json = internalObjectMapper.writeValueAsString(messages);
            
            AiMemory existing = aiMemoryMapper.selectOne(new LambdaQueryWrapper<AiMemory>()
                    .eq(AiMemory::getSessionId, conversationId));

            if (existing != null) {
                existing.setMessagesJson(json);
                aiMemoryMapper.updateById(existing);
            } else {
                AiMemory newMemory = new AiMemory();
                newMemory.setSessionId(conversationId);
                newMemory.setMessagesJson(json);
                
                try {
                    newMemory.setUserId(SecurityUtils.getUserId());
                } catch (Exception ignored) {
                    newMemory.setUserId(0L);
                }
                
                aiMemoryMapper.insert(newMemory);
            }
            log.debug("记忆已存入DB (MyBatis): sessionId={}, 消息数={}", conversationId, messages.size());
        } catch (Exception e) {
            log.error("保存会话记忆失败: sessionId={}", conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        aiMemoryMapper.delete(new LambdaQueryWrapper<AiMemory>()
                .eq(AiMemory::getSessionId, conversationId));
        log.debug("记忆已删除: sessionId={}", conversationId);
    }
}

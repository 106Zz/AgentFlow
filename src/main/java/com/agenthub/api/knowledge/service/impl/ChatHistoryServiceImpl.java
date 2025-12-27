package com.agenthub.api.knowledge.service.impl;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.agenthub.api.knowledge.mapper.ChatHistoryMapper;
import com.agenthub.api.knowledge.service.IChatHistoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天历史服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory> 
        implements IChatHistoryService {

    @Override
    public void saveChat(String sessionId, Long userId, String question, String answer) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setSessionId(sessionId);
        chatHistory.setUserId(userId);
        chatHistory.setQuestion(question);
        chatHistory.setAnswer(answer);
        chatHistory.setCreateTime(LocalDateTime.now());
        
        this.save(chatHistory);
        
        log.debug("保存聊天记录 - sessionId: {}, userId: {}", sessionId, userId);
    }

    @Override
    public List<ChatHistory> getBySessionId(String sessionId, Long userId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId)
                .orderByAsc(ChatHistory::getCreateTime);
        
        return this.list(wrapper);
    }

    @Override
    public List<Map<String, Object>> getUserSessions(Long userId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getUserId, userId)
                .orderByDesc(ChatHistory::getCreateTime);
        
        List<ChatHistory> histories = this.list(wrapper);
        
        // 按 sessionId 分组，返回每个会话的最新一条记录
        return histories.stream()
                .collect(Collectors.groupingBy(
                        ChatHistory::getSessionId,
                        Collectors.maxBy((h1, h2) -> h1.getCreateTime().compareTo(h2.getCreateTime()))
                ))
                .values().stream()
                .filter(opt -> opt.isPresent())
                .map(opt -> {
                    ChatHistory h = opt.get();
                    Map<String, Object> result = Map.of(
                            "sessionId", (Object) h.getSessionId(),
                            "lastQuestion", (Object) h.getQuestion(),
                            "lastAnswer", (Object) (h.getAnswer().length() > 100 
                                    ? h.getAnswer().substring(0, 100) + "..." 
                                    : h.getAnswer()),
                            "createTime", (Object) h.getCreateTime()
                    );
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void deleteBySessionId(String sessionId, Long userId) {
        LambdaQueryWrapper<ChatHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatHistory::getSessionId, sessionId)
                .eq(ChatHistory::getUserId, userId);
        
        this.remove(wrapper);
        
        log.info("删除聊天历史 - sessionId: {}, userId: {}", sessionId, userId);
    }
}

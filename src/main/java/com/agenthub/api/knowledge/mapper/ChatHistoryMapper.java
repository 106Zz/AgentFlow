package com.agenthub.api.knowledge.mapper;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 聊天历史 数据层
 */
@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /**
     * 查询用户的所有会话列表（按最后对话时间排序）
     */
    @Select("""
        SELECT 
            session_id,
            MAX(create_time) as last_time,
            COUNT(*) as message_count,
            (SELECT question FROM chat_history 
             WHERE session_id = ch.session_id AND user_id = #{userId}
             ORDER BY create_time ASC LIMIT 1) as first_question
        FROM chat_history ch
        WHERE user_id = #{userId}
        GROUP BY session_id
        ORDER BY last_time DESC
        LIMIT 50
    """)
    List<Map<String, Object>> selectUserSessions(Long userId);
}

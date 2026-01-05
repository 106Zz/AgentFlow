package com.agenthub.api.knowledge.mapper;

import com.agenthub.api.knowledge.domain.ChatSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话元数据 Mapper
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
    
    /**
     * 查询用户会话列表（按时间倒序）
     */
    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} AND del_flag = 0 ORDER BY last_message_time DESC")
    List<ChatSession> selectUserSessions(Long userId);
}
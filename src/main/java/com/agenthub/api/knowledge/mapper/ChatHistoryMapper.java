package com.agenthub.api.knowledge.mapper;

import com.agenthub.api.knowledge.domain.ChatHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 更新回答内容（用于流式生成过程中的增量更新）
     */
    @Update("UPDATE chat_history SET answer = #{answer}, status = #{status} " +
            "WHERE id = #{id}")
    int updateAnswer(@Param("id") Long id, @Param("answer") String answer, @Param("status") String status);

    /**
     * 更新回答内容和来源文件
     */
    @Update("UPDATE chat_history SET answer = #{answer}, status = #{status}, sources = #{sourcesJson}::jsonb " +
            "WHERE id = #{id}")
    int updateAnswerAndSources(@Param("id") Long id, @Param("answer") String answer,
                               @Param("status") String status, @Param("sourcesJson") String sourcesJson);

    /**
     * 标记生成中断
     */
    @Update("UPDATE chat_history SET status = 'interrupted', error_message = #{errorMsg} " +
            "WHERE id = #{id} AND status = 'generating'")
    int markAsInterrupted(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    /**
     * 批量插入（用于保存用户问题和空回答）
     */
    int insertBatch(@Param("list") List<ChatHistory> list);
}

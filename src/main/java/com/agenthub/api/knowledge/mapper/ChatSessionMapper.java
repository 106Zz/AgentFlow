package com.agenthub.api.knowledge.mapper;

import com.agenthub.api.knowledge.domain.ChatSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话元数据 数据层
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

}

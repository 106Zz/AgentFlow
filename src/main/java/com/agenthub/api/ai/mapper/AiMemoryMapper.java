package com.agenthub.api.ai.mapper;

import com.agenthub.api.ai.domain.AiMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 记忆 Mapper
 */
@Mapper
public interface AiMemoryMapper extends BaseMapper<AiMemory> {
}
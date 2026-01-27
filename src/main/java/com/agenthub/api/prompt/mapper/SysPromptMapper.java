package com.agenthub.api.prompt.mapper;


import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPromptMapper extends BaseMapper<SysPrompt> {
}

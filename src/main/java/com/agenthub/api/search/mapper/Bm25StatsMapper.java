package com.agenthub.api.search.mapper;


import com.agenthub.api.search.domain.Bm25Stats;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface Bm25StatsMapper extends BaseMapper<Bm25Stats> {
}

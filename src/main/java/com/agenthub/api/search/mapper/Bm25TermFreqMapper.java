package com.agenthub.api.search.mapper;


import com.agenthub.api.search.domain.Bm25TermFreq;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface Bm25TermFreqMapper extends BaseMapper<Bm25TermFreq> {

    /**
     * 批量插入词频记录
     */
    int insertBatch(@Param("list") List<Bm25TermFreq> list);

}

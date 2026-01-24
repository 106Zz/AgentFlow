package com.agenthub.api.search.mapper;


import com.agenthub.api.search.domain.Bm25DocFreq;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface Bm25DocFreqMapper extends BaseMapper<Bm25DocFreq> {

    /**
     * 从 term_freq 表重建 doc_freq 表
     *
     * <p>先清空 doc_freq，然后从 term_freq 重新聚合统计</p>
     */
    void rebuildFromTermFreq();

    /**
     * 批量 UPSERT：存在则更新，不存在则插入
     */
    int insertOrUpdateBatch(@Param("list") List<Bm25DocFreq> list);
}

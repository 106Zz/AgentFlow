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

    /**
     * v4.3 - 批量查询词频：用 GROUP BY 一次性获取多个词的文档频率
     * 替代 N+1 查询问题
     *
     * @param terms 词列表
     * @return List<Bm25TermFreq> 包含 term 和聚合后的 docCount（通过 frequency 字段返回）
     */
    @Select("<script>" +
            "SELECT term, COUNT(DISTINCT doc_id) as frequency " +
            "FROM bm25_term_freq " +
            "WHERE term IN " +
            "<foreach collection='terms' item='t' open='(' separator=',' close=')'>" +
            "#{t}" +
            "</foreach>" +
            " GROUP BY term" +
            "</script>")
    List<Bm25TermFreq> batchSelectDocCount(@Param("terms") List<String> terms);

}

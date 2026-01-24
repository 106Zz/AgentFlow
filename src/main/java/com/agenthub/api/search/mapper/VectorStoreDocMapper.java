package com.agenthub.api.search.mapper;

import com.agenthub.api.search.domain.VectorStoreDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Vector Store 文档 Mapper
 * <p>
 * 用于批量查询 vector_store 表，优化 BM25 检索性能
 * </p>
 */
@Mapper
public interface VectorStoreDocMapper extends BaseMapper<VectorStoreDoc> {

    /**
     * 批量查询文档内容（按 ID 列表）
     *
     * @param ids 文档ID列表
     * @return 文档列表
     */
    List<VectorStoreDoc> selectByIds(@Param("ids") List<String> ids);
}

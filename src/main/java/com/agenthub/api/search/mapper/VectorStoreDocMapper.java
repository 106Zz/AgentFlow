package com.agenthub.api.search.mapper;

import com.agenthub.api.search.domain.VectorStoreDoc;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Vector Store 文档 Mapper
 * <p>
 * 用于直接操作 vector_store 表，避免通过 PgVectorStore API
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

    /**
     * 根据 knowledge_id 批量删除向量记录（直接 SQL，不调用 DashScope API）
     *
     * @param knowledgeIds 知识库ID列表
     * @return 删除的记录数
     */
    int deleteByKnowledgeIds(@Param("knowledgeIds") List<Long> knowledgeIds);
}

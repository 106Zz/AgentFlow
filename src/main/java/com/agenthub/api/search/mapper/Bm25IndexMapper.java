package com.agenthub.api.search.mapper;


import com.agenthub.api.search.domain.Bm25Index;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface Bm25IndexMapper extends BaseMapper<Bm25Index> {
}

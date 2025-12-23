package com.agenthub.api.common.core.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询参数
 */
@Data
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long pageNum = 1L;
    private Long pageSize = 10L;
    private String orderByColumn;
    private String isAsc = "asc";

    public <T> Page<T> build() {
        return new Page<>(pageNum, pageSize);
    }
}

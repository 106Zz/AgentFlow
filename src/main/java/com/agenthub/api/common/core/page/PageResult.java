package com.agenthub.api.common.core.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应结果
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long total;
    private List<T> rows;
    private Long pageNum;
    private Long pageSize;

    public PageResult() {
    }

    public PageResult(List<T> rows, Long total) {
        this.rows = rows;
        this.total = total;
    }

    public static <T> PageResult<T> build(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setRows(page.getRecords());
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        return result;
    }
}

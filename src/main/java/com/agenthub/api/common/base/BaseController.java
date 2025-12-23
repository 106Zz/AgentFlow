package com.agenthub.api.common.base;

import com.agenthub.api.common.core.domain.AjaxResult;
import com.agenthub.api.common.core.page.PageResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基础控制器
 */
public class BaseController {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected AjaxResult success() {
        return AjaxResult.success();
    }

    protected AjaxResult success(String msg) {
        return AjaxResult.success(msg);
    }

    protected AjaxResult success(Object data) {
        return AjaxResult.success(data);
    }

    protected AjaxResult success(String msg, Object data) {
        return AjaxResult.success(msg, data);
    }

    protected AjaxResult error() {
        return AjaxResult.error();
    }

    protected AjaxResult error(String msg) {
        return AjaxResult.error(msg);
    }

    protected <T> PageResult<T> getPageResult(IPage<T> page) {
        return PageResult.build(page);
    }
}

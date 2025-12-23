package com.agenthub.api.common.core.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果
 */
@Data
public class AjaxResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;
    private String msg;
    private Object data;
    private Long timestamp;

    public AjaxResult() {
        this.timestamp = System.currentTimeMillis();
    }

    public AjaxResult(Integer code, String msg) {
        this();
        this.code = code;
        this.msg = msg;
    }

    public AjaxResult(Integer code, String msg, Object data) {
        this();
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static AjaxResult success() {
        return new AjaxResult(200, "操作成功");
    }

    public static AjaxResult success(String msg) {
        return new AjaxResult(200, msg);
    }

    public static AjaxResult success(Object data) {
        return new AjaxResult(200, "操作成功", data);
    }

    public static AjaxResult success(String msg, Object data) {
        return new AjaxResult(200, msg, data);
    }

    public static AjaxResult error() {
        return new AjaxResult(500, "操作失败");
    }

    public static AjaxResult error(String msg) {
        return new AjaxResult(500, msg);
    }

    public static AjaxResult error(Integer code, String msg) {
        return new AjaxResult(code, msg);
    }
}

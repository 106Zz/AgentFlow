package com.agenthub.api.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务异常
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ServiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private Integer code;
    private String message;

    public ServiceException(String message) {
        this.code = 500;
        this.message = message;
    }

    public ServiceException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}

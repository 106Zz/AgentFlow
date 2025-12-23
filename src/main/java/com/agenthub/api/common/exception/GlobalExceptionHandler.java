package com.agenthub.api.common.exception;

import com.agenthub.api.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request) {
        log.error("业务异常：{} - {}", request.getRequestURI(), e.getMessage());
        return AjaxResult.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        log.error("权限校验失败：{}", request.getRequestURI());
        return AjaxResult.error(403, "没有权限，请联系管理员授权");
    }

    @ExceptionHandler(BadCredentialsException.class)
    public AjaxResult handleBadCredentialsException(BadCredentialsException e) {
        log.error("认证失败：{}", e.getMessage());
        return AjaxResult.error(401, "用户名或密码错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public AjaxResult handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        log.error("参数校验失败：{}", message);
        return AjaxResult.error(message);
    }

    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        log.error("参数绑定失败：{}", message);
        return AjaxResult.error(message);
    }

    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常：{} - {}", request.getRequestURI(), e.getMessage(), e);
        return AjaxResult.error("系统内部错误，请联系管理员");
    }
}

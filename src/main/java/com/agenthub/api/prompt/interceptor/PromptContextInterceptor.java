package com.agenthub.api.prompt.interceptor;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)  // 在 JWT 认证之后
@RequiredArgsConstructor
public class PromptContextInterceptor implements HandlerInterceptor, Ordered {

    private final PromptService promptService;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return 0;
    }
}

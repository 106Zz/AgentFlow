package com.agenthub.api.ai.core;

import com.agenthub.api.ai.domain.workflow.ComplianceReport;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import lombok.Builder;
import lombok.Getter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Getter
@Builder
public class AIResponse {

    // 响应类型枚举，方便前端 switch
    public enum Type { REPORT, LIST, TEXT, STREAM }

    private final Type type;

    // 核心数据 (异步)
    // 为什么用 Object? 因为 Report 和 List<Result> 结构差异太大，
    // 但这里是用泛型 T 的 CompletableFuture 包装的，Controller 强转一次即可，
    // 或者我们直接在这里做完 mapping。
    private final CompletableFuture<?> asyncData;

    // 新增：流式数据 (用于打字机效果)
    private final Flux<?> streamData;

    // 静态工厂方法：构建全量报告响应
    public static AIResponse ofReport(CompletableFuture<ComplianceReport> future) {
        return AIResponse.builder()
                .type(Type.REPORT)
                .asyncData(future)
                .build();
    }

    // 静态工厂方法：构建列表响应
    public static AIResponse ofList(CompletableFuture<List<WorkerResult>> future) {
        return AIResponse.builder()
                .type(Type.LIST)
                .asyncData(future)
                .build();
    }

    // 静态工厂方法：构建文本响应 (把同步转异步，保持接口一致)
    public static AIResponse ofText(String text) {
        return AIResponse.builder()
                .type(Type.TEXT)
                .asyncData(CompletableFuture.completedFuture(text))
                .build();
    }

    // 静态工厂方法：构建流式响应
    public static AIResponse ofStream(Flux<?> flux) {
        return AIResponse.builder()
                .type(Type.STREAM)
                .streamData(flux)
                .build();
    }

}

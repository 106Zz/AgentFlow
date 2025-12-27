package com.agenthub.api.ai.model;

import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import com.alibaba.dashscope.utils.JsonUtils;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通义千问 Reranker 模型实现
 * 使用 HTTP API 直接调用（因为 DashScope SDK 2.16.7 没有 rerank 包）
 */
@Slf4j
public class DashScopeScoringModel implements ScoringModel {

    private final String apiKey;
    private final String modelName;
    private final OkHttpClient httpClient;
    private static final String RERANK_API_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    public DashScopeScoringModel(String apiKey, String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.httpClient = new OkHttpClient.Builder().build();
    }

    @Override
    public Response<Double> score(TextSegment textSegment, String query) {
        try {
            List<Double> scores = callRerankApi(query, List.of(textSegment.text()));
            return Response.from(scores.isEmpty() ? 0.0 : scores.get(0));
        } catch (Exception e) {
            log.error("Rerank 失败", e);
            return Response.from(0.0);
        }
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        try {
            List<String> documents = segments.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.toList());

            List<Double> scores = callRerankApi(query, documents);
            return Response.from(scores);
        } catch (Exception e) {
            log.error("Rerank 批量打分失败", e);
            // 降级：返回全0分数
            List<Double> zeros = segments.stream()
                    .map(s -> 0.0)
                    .collect(Collectors.toList());
            return Response.from(zeros);
        }
    }

    /**
     * 调用 DashScope Rerank HTTP API
     */
    private List<Double> callRerankApi(String query, List<String> documents) throws IOException {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", documents.size());
        parameters.put("return_documents", false);
        requestBody.put("parameters", parameters);

        String jsonBody = JsonUtils.toJson(requestBody);

        // 构建 HTTP 请求
        Request request = new Request.Builder()
                .url(RERANK_API_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        // 发送请求
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Rerank API 调用失败: {}", response.code());
                return Collections.nCopies(documents.size(), 0.0);
            }

            String responseBody = response.body().string();
            Map<String, Object> result = JsonUtils.fromJson(responseBody, Map.class);

            // 解析响应
            Map<String, Object> output = (Map<String, Object>) result.get("output");
            if (output == null) {
                return Collections.nCopies(documents.size(), 0.0);
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
            if (results == null || results.isEmpty()) {
                return Collections.nCopies(documents.size(), 0.0);
            }

            // 创建一个与输入顺序对应的分数列表
            List<Double> scores = new ArrayList<>(Collections.nCopies(documents.size(), 0.0));

            // 填充实际的分数
            for (Map<String, Object> rerankResult : results) {
                Integer index = (Integer) rerankResult.get("index");
                Object scoreObj = rerankResult.get("relevance_score");
                
                double score = 0.0;
                if (scoreObj instanceof Number) {
                    score = ((Number) scoreObj).doubleValue();
                }
                
                if (index != null && index < scores.size()) {
                    scores.set(index, score);
                }
            }

            return scores;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String modelName;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public DashScopeScoringModel build() {
            return new DashScopeScoringModel(apiKey, modelName);
        }
    }
}

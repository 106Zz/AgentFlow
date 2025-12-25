package com.agenthub.api.ai.config;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@Cacheable(value = "rerank", unless = "#result == null || #result.isEmpty()")
public class DashScopeRerankerConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    //云模型Rerank，直接访问这个url，阿里官网获取
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    public List<Document> rerank(String query,List<Document> documents,int topN){
        if(documents==null || documents.isEmpty()){
            log.warn("候选文档为空，跳过Rerank");
            return documents;
        }

        if(documents.size()<=topN){
            log.info("候选文档数量 {} <= topN {}，无需 Rerank", documents.size(), topN);
            return documents;
        }

        log.info("开始 Rerank：候选文档 {} 个，取 top{}", documents.size(), topN);
        long startTime = System.currentTimeMillis();

        // 构建请求体
        JSONObject requestBody = buildRequest(query, documents, topN);

        //发送http请求
        String responseStr = HttpRequest.post(RERANK_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .timeout(10000)  // 10秒超时
                .execute()
                .body();

        try {
            //解析响应
            JSONObject response = JSONUtil.parseObj(responseStr);

            //检查错误
            if(response.containsKey("code")){
                String errorMsg = response.getStr("message", "未知错误");
                log.error("Rerank API 调用失败：{}", errorMsg);
                log.warn("降级处理：返回向量检索的前 {} 个结果（未重排序）", topN);
                return fallbackToOriginal(documents, topN);
            }

            // 5. 提取重排结果
            JSONArray results = response.getByPath("output.results", JSONArray.class);
            if (results == null || results.isEmpty()) {
                log.warn("Rerank 返回结果为空");
                log.warn("降级处理：返回向量检索的前 {} 个结果（未重排序）", topN);
                return fallbackToOriginal(documents, topN);
            }

            // 6. 按重排后的顺序返回文档
            List<Document> reranked = results.stream()
                    .map(obj -> {
                        JSONObject item = (JSONObject) obj;
                        int index = item.getInt("index");
                        double score = item.getDouble("relevance_score");

                        Document doc = documents.get(index);
                        // 保存 Rerank 分数到 metadata
                        doc.getMetadata().put("rerank_score", score);
                        return doc;
                    })
                    .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Rerank 完成：返回 {} 个文档，耗时 {}ms", reranked.size(), elapsed);

            return reranked;
        } catch (Exception e) {
            log.error("Rerank 调用异常，返回原始结果", e);
            log.warn("降级处理：返回向量检索的前 {} 个结果（未重排序）", topN);
            return fallbackToOriginal(documents, topN);
        }

    }



    /**
     * 构建 Rerank 请求体
     */
    private JSONObject buildRequest(String query, List<Document> documents, int topN) {
        JSONObject request = new JSONObject();
        request.set("model", "gte-rerank-v2");  // 阿里云 Reranker 模型

        // input 部分
        JSONObject input = new JSONObject();
        input.set("query", query);

        // 提取文档文本（限制长度，避免超长）
        JSONArray docs = new JSONArray();
        for (Document doc : documents) {
            String text = doc.getText();
            // 限制每个文档最大 2000 字符（Rerank 有长度限制）
            if (text.length() > 2000) {
                text = text.substring(0, 2000);
            }
            docs.add(text);
        }
        input.set("documents", docs);

        request.set("input", input);

        // parameters 部分
        JSONObject parameters = new JSONObject();
        parameters.set("top_n", topN);
        parameters.set("return_documents", false);  // 不返回文档内容，节省流量
        request.set("parameters", parameters);

        return request;
    }

    /**
     * 降级处理：返回原始结果的前 topN 个
     */
    private List<Document> fallbackToOriginal(List<Document> documents, int topN) {
        int actualTopN = Math.min(topN, documents.size());
        return documents.subList(0, actualTopN);
    }
}

package com.agenthub.api.ai.service;

import com.agenthub.api.ai.config.DashScopeRerankerConfig;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeResult;
import com.agenthub.api.ai.utils.VectorStoreHelper;
import com.agenthub.api.common.utils.SecurityUtils;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class PowerKnowledgeService {

        private final VectorStoreHelper vectorStoreHelper;
        private final DashScopeRerankerConfig dashScopeReranker;

        // 注入 OSS 配置 (建议放在 application.yml 里)
        // 根据你的截图，Region 是上海
        @Value("${aliyun.oss.endpoint:https://oss-cn-shanghai.aliyuncs.com}")
        private String ossEndpoint;

        @Value("${aliyun.oss.accessKeyId}")
        private String accessKeyId;

        @Value("${aliyun.oss.accessKeySecret}")
        private String accessKeySecret;

        @Value("${aliyun.oss.bucketName:agenthub-knowledge}")
        private String bucketName;


        public PowerKnowledgeResult retrieve(PowerKnowledgeQuery query){
                log.info("🔍 AI 正在调用知识库工具: query={}, year={}, topK={}",
                        query.query(), query.yearFilter(), query.topK());

                long startTime = System.currentTimeMillis();

                Long userId = SecurityUtils.getUserId();
                boolean isAdmin = SecurityUtils.isAdmin();

                // 2. 向量召回 (Recall) - 故意多查一点，比如 50 条
                int userTopK = (query.topK() != null && query.topK() > 0) ? query.topK() : 5;
                int recallCount = Math.max(20, userTopK * 3); // 动态调整召回基数

                List<Document> rawDocs = vectorStoreHelper.searchWithUserFilter(
                        query.query(),
                        userId,
                        isAdmin,
                        recallCount,
                        0.45,
                        query.category()
                );
                log.debug("初排召回 {} 条文档", rawDocs.size());

                if (rawDocs.isEmpty()) {
                        return emptyResult();
                }

                // 3. 重排序 (Rerank) - 核心优化点
                List<Document> finalDocs = dashScopeReranker.rerank(
                        query.query(),
                        rawDocs,
                        userTopK
                );

                // 4. 组装结果 (Result Construction)
                // A. 提取原始切片内容 (rawContentSnippets)
                List<String> snippets = finalDocs.stream()
                        .map(Document::getText)
                        .collect(Collectors.toList());

                // B. 提取来源文件名 (sourceNames)
                // 核心逻辑：从 Document 的 metadata 里拿出 "filename" 字段，然后去重
                // B. 提取来源 + 生成 OSS 链接 (🚨 修改点)
                List<PowerKnowledgeResult.SourceDocument> sources = finalDocs.stream()
                        .map(d -> d.getMetadata().getOrDefault("filename", "unknown").toString())
                        .distinct()
                        .map(filename -> {
                                // 调用下面的私有方法生成带签名的安全链接
                                String signedUrl = generatePresignedUrl(filename);
                                return new PowerKnowledgeResult.SourceDocument(filename, signedUrl);
                        })
                        .collect(Collectors.toList());

                // C. 计算最高分 (用于调试)
                double maxScore = finalDocs.stream()
                        .mapToDouble(d -> {
                                Object s = d.getMetadata().get("rerank_score");
                                return s != null ? Double.parseDouble(s.toString()) : 0.0;
                        })
                        .max()
                        .orElse(0.0);


                long totalTime = System.currentTimeMillis() - startTime;

                // 生成简报给大模型看
                // 注意：Tool 的 answer 不是最终给用户的回复，而是告诉大模型“我查到了什么”
                String sourceNamesStr = sources.stream()
                        .map(PowerKnowledgeResult.SourceDocument::filename)
                        .collect(Collectors.joining(", "));

                String answerSummary = String.format(
                        "检索成功。共找到 %d 条相关文档片段，最高置信度 %.2f。来源包括：%s",
                        finalDocs.size(), maxScore, sourceNamesStr
                );

                log.info("⚡️ [RAG] 检索完成。耗时: {}ms, 召回: {}, 最终: {}",
                        totalTime, rawDocs.size(), finalDocs.size());

                // 返回你定义的 Record
                return new PowerKnowledgeResult(
                        answerSummary,    // 对应 record 的 String answer
                        snippets,         // 对应 record 的 List<String> rawContentSnippets
                        sources,          // 对应 record 的 List<String> sourceNames
                        Map.of("total_time_ms", totalTime, "top_score", maxScore)
                );

        }


        private PowerKnowledgeResult emptyResult() {
                return new PowerKnowledgeResult(
                        "未在知识库中找到相关内容。",
                        List.of(),
                        List.of(),
                        Map.of()
                );
        }

        /**
         * 🚨 新增核心方法：生成 OSS 临时授权链接
         * 这会让前端用户可以下载私有 Bucket 里的文件，链接有效期 1 小时
         */
        private String generatePresignedUrl(String objectName) {
                // 创建 OSSClient 实例
                OSS ossClient = new OSSClientBuilder().build(ossEndpoint, accessKeyId, accessKeySecret);
                try {
                        // 设置 URL 过期时间为 1 小时 (3600000 毫秒)
                        Date expiration = new Date(new Date().getTime() + 3600 * 1000);

                        // 生成 URL
                        URL url = ossClient.generatePresignedUrl(bucketName, objectName, expiration);
                        return url.toString();
                } catch (Exception e) {
                        log.error("生成 OSS 签名链接失败: {}", objectName, e);
                        return "";
                } finally {
                        // 一定要关闭 client，否则会连接泄露
                        ossClient.shutdown();
                }
        }



}

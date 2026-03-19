package com.agenthub.api.ai.service.gssc;

import com.agenthub.api.search.util.ChineseTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GSSC 流水线服务
 * <p>
 * 实现 Gather → Select → Structure → Compress 流程
 * </p>
 *
 * <h3>功能：</h3>
 * <ul>
 *   <li>Gather: 收集 RAG 结果和历史记忆，封装为 ContextPacket</li>
 *   <li>Select: 基于相关性、时效性、重要性进行评分选择</li>
 *   <li>Structure: 按模板结构化输出</li>
 *   <li>Compress: Token 预算控制，超出则压缩</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GSSCService {

    private final ChineseTokenizer chineseTokenizer;

    /**
     * 最大 Token 预算
     */
    @Value("${gssc.max-tokens:3000}")
    private int maxTokens;

    /**
     * 相关性权重
     */
    @Value("${gssc.weights.relevance:0.6}")
    private double relevanceWeight;

    /**
     * 时效性权重
     */
    @Value("${gssc.weights.recency:0.3}")
    private double recencyWeight;

    /**
     * 重要性权重
     */
    @Value("${gssc.weights.importance:0.1}")
    private double importanceWeight;

    /**
     * RAG 结果选择数量
     */
    @Value("${gssc.select.evidence-count:5}")
    private int evidenceCount;

    /**
     * 历史记忆选择数量
     */
    @Value("${gssc.select.history-count:3}")
    private int historyCount;

    /**
     * 是否启用 GSSC
     */
    @Value("${gssc.enabled:false}")
    private boolean enabled;

    /**
     * 执行完整的 GSSC 流水线
     *
     * @param evidencePackets RAG 证据包
     * @param historyPackets   历史记忆包
     * @param toolResultPackets 工具执行结果包（可为空）
     * @param systemPrompt     系统提示词
     * @param userQuery       用户问题
     * @return 最终上下文字符串
     */
    public String process(
            List<ContextPacket> evidencePackets,
            List<ContextPacket> historyPackets,
            List<ContextPacket> toolResultPackets,
            String systemPrompt,
            String userQuery) {

        if (!enabled) {
            // 如果未启用，返回简单的拼接
            return buildSimpleContext(evidencePackets, historyPackets, toolResultPackets, systemPrompt, userQuery);
        }

        log.debug("【GSSC】开始处理: evidence={}, history={}, tools={}", 
                evidencePackets.size(), historyPackets.size(), 
                toolResultPackets != null ? toolResultPackets.size() : 0);

        // Step 1: Select - 评分选择
        List<ContextPacket> selectedEvidence = selectEvidence(evidencePackets);
        List<ContextPacket> selectedHistory = selectHistory(historyPackets);
        List<ContextPacket> selectedTools = selectTools(toolResultPackets);

        // Step 2: Structure - 结构化
        String structured = structureContext(selectedEvidence, selectedHistory, selectedTools, systemPrompt, userQuery);

        // Step 3: Compress - 压缩（Token 预算控制）
        return compressIfNeeded(structured);
    }

    /**
     * 简重载：处理工具结果（用于第二轮工具调用场景）
     */
    public String processWithTools(
            List<ContextPacket> historyPackets,
            List<ContextPacket> toolResultPackets,
            String systemPrompt,
            String userQuery) {
        return process(new ArrayList<>(), historyPackets, toolResultPackets, systemPrompt, userQuery);
    }

    /**
     * 对历史消息进行评分选择
     * <p>
     * 使用 GSSC 的评分逻辑，基于相关性、时效性、重要性选择最相关的历史消息
     * </p>
     *
     * @param historyPackets 历史消息包列表
     * @param userQuery      当前用户问题（用于计算相关性）
     * @return 评分选择后的历史消息包列表
     */
    public List<ContextPacket> selectHistoryMessages(List<ContextPacket> historyPackets, String userQuery) {
        if (historyPackets == null || historyPackets.isEmpty()) {
            return Collections.emptyList();
        }

        if (!enabled) {
            // 如果未启用评分，返回原始列表（但仍需限制数量）
            int maxItems = Math.min(historyPackets.size(), historyCount);
            return historyPackets.subList(0, maxItems);
        }

        log.debug("【GSSC Memory】历史消息评分选择: input={}, userQuery={}", 
                historyPackets.size(), userQuery);

        // 为每个历史消息计算与当前问题的相关性
        for (ContextPacket packet : historyPackets) {
            double relevanceScore = calculateRelevanceScore(packet.getContent(), userQuery);
            packet.setRelevanceScore(relevanceScore);
        }

        // 使用现有的选择逻辑
        List<ContextPacket> selected = selectHistory(historyPackets);

        log.debug("【GSSC Memory】历史消息评分选择完成: output={}", selected.size());
        return selected;
    }

    /**
     * 计算历史消息与当前问题的相关性分数
     * <p>
     * 改进版（v2）：
     * 1. 使用 Jieba 中文分词（复用 ChineseTokenizer），替代标点分割
     * 2. 过滤停用词，只保留有意义的词
     * 3. 使用 Jaccard 相似系数衡量词集合重叠度
     * 4. bigram 短语级匹配加分，捕捉"电力市场交易"这类短语
     * </p>
     *
     * @param historyContent 历史消息内容
     * @param userQuery      当前用户问题
     * @return 相关性分数，范围 0.3 ~ 1.0
     */
    private double calculateRelevanceScore(String historyContent, String userQuery) {
        if (historyContent == null || historyContent.isEmpty() || userQuery == null || userQuery.isEmpty()) {
            return 0.5;
        }

        // 步骤1：用 Jieba 中文分词（自动过滤停用词和单字）
        List<String> queryTokens = chineseTokenizer.tokenize(userQuery);
        List<String> historyTokens = chineseTokenizer.tokenize(historyContent);

        if (queryTokens.isEmpty()) {
            return 0.5;
        }

        // 步骤2：构建集合，计算 Jaccard 相似系数
        Set<String> querySet = new LinkedHashSet<>(queryTokens);
        Set<String> historySet = new LinkedHashSet<>(historyTokens);

        // 交集：query 和 history 中都出现的词
        long intersectionCount = querySet.stream().filter(historySet::contains).count();
        // 并集
        Set<String> union = new LinkedHashSet<>(querySet);
        union.addAll(historySet);

        double jaccard = union.isEmpty() ? 0.0 : (double) intersectionCount / union.size();

        // 步骤3：bigram 短语级匹配加分
        // "电力市场交易" 分词后 ["电力","市场","交易"]
        // bigram: ["电力市场", "市场交易"]
        // 如果 history 中也有 "电力市场" 这个 bigram，说明短语级语义匹配
        double bigramBonus = 0.0;
        List<String> queryBigrams = generateBigrams(queryTokens);
        if (!queryBigrams.isEmpty()) {
            List<String> historyBigrams = generateBigrams(historyTokens);
            Set<String> historyBigramSet = new LinkedHashSet<>(historyBigrams);
            long bigramHits = queryBigrams.stream().filter(historyBigramSet::contains).count();
            bigramBonus = (double) bigramHits / queryBigrams.size() * 0.15; // 最多加 0.15
        }

        // 步骤4：合并，映射到 0.3 ~ 1.0
        double rawScore = jaccard + bigramBonus;
        return 0.3 + Math.min(rawScore, 1.0) * 0.7;
    }

    /**
     * 从 token 列表生成 bigram（二元组）
     * e.g. ["电力","市场","交易"] → ["电力市场","市场交易"]
     */
    private List<String> generateBigrams(List<String> tokens) {
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            bigrams.add(tokens.get(i) + tokens.get(i + 1));
        }
        return bigrams;
    }

    /**
     * 只处理 RAG 结果的 GSSC（用于简单场景）
     */
    public String processEvidence(List<ContextPacket> evidencePackets, String userQuery) {
        if (!enabled || evidencePackets == null || evidencePackets.isEmpty()) {
            return evidencePackets.stream()
                    .map(ContextPacket::getContent)
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        List<ContextPacket> selected = selectEvidence(evidencePackets);

        StringBuilder sb = new StringBuilder();
        sb.append("【知识库检索结果】共 ").append(evidencePackets.size()).append(" 个结果\n\n");

        for (int i = 0; i < selected.size(); i++) {
            ContextPacket packet = selected.get(i);
            sb.append(String.format("[证据 %d]\n%s\n\n", i + 1, packet.getContent()));
        }

        return compressIfNeeded(sb.toString());
    }

    /**
     * Select: 选择最相关的 RAG 证据
     */
    private List<ContextPacket> selectEvidence(List<ContextPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算综合评分
        for (ContextPacket packet : packets) {
            double score = calculateScore(packet);
            packet.setScore(score);
        }

        // 按分数排序，选择 Top N
        return packets.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(evidenceCount)
                .collect(Collectors.toList());
    }

    /**
     * Select: 选择最相关的历史记忆
     */
    private List<ContextPacket> selectHistory(List<ContextPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算综合评分（历史消息不包含 relevanceScore，主要靠 recency）
        for (ContextPacket packet : packets) {
            double score = calculateScore(packet);
            packet.setScore(score);
        }

        // 按分数排序，选择 Top N
        return packets.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(historyCount)
                .collect(Collectors.toList());
    }

    /**
     * Select: 选择工具结果（工具结果默认全选，因为通常是用户明确请求的）
     */
    private List<ContextPacket> selectTools(List<ContextPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            return Collections.emptyList();
        }
        // 工具结果通常全部保留，但超出预算时压缩
        return new ArrayList<>(packets);
    }

    /**
     * 计算综合评分
     * score = relevanceWeight * 相关性 + recencyWeight * 时效性 + importanceWeight * 重要性
     */
    private double calculateScore(ContextPacket packet) {
        // 相关性分数（0-1）
        double relevanceScore = packet.getRelevanceScore();
        if (relevanceScore <= 0) {
            relevanceScore = 0.5; // 默认分数
        }

        // 时效性分数（0-1，指数衰减）
        double recencyScore = calculateRecencyScore(packet.getTimestamp());

        // 重要性分数（0-1，基于内容长度和信息密度）
        double importanceScore = calculateImportanceScore(packet.getContent());

        // 综合评分
        return relevanceWeight * relevanceScore
                + recencyWeight * recencyScore
                + importanceWeight * importanceScore;
    }

    /**
     * 计算时效性分数（指数衰减）
     * 24小时前 = 0.36, 7天前 = 0.05
     */
    private double calculateRecencyScore(Instant timestamp) {
        if (timestamp == null) {
            return 0.5; // 默认分数
        }

        double hoursAgo = Duration.between(timestamp, Instant.now()).toHours();
        // 每24小时衰减为原来的 1/e
        return Math.max(0.1, Math.exp(-0.1 * hoursAgo / 24));
    }

    /**
     * 计算重要性分数（基于内容长度和信息密度）
     */
    private double calculateImportanceScore(String content) {
        if (content == null || content.isEmpty()) {
            return 0.1;
        }

        // 长度适中（100-500字）得分较高
        int length = content.length();
        if (length < 50) {
            return 0.3;
        } else if (length > 1000) {
            return 0.7; // 长内容可能包含更多信息
        } else {
            return 0.8;
        }
    }

    /**
     * Structure: 结构化输出
     */
    private String structureContext(
            List<ContextPacket> evidence,
            List<ContextPacket> history,
            List<ContextPacket> tools,
            String systemPrompt,
            String userQuery) {

        StringBuilder sb = new StringBuilder();

        // [System]
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append("[System]\n").append(systemPrompt).append("\n\n");
        }

        // [Evidence]
        if (!evidence.isEmpty()) {
            sb.append("[Evidence]\n");
            for (int i = 0; i < evidence.size(); i++) {
                ContextPacket packet = evidence.get(i);
                sb.append(String.format("[%d] %s\n\n", i + 1, packet.getContent()));
            }
            sb.append("\n");
        }

        // [Tools] - 工具执行结果
        if (tools != null && !tools.isEmpty()) {
            sb.append("[工具执行结果]\n");
            for (ContextPacket packet : tools) {
                String toolName = packet.getMetadata() != null 
                        ? (String) packet.getMetadata().getOrDefault("toolName", "未知工具") 
                        : "未知工具";
                sb.append(String.format("【%s】: %s\n\n", toolName, packet.getContent()));
            }
            sb.append("\n");
        }

        // [Context]
        if (!history.isEmpty()) {
            sb.append("[Context]\n");
            for (ContextPacket packet : history) {
                sb.append(packet.getContent()).append("\n");
            }
            sb.append("\n");
        }

        // [Task]
        sb.append("[Task]\n").append(userQuery).append("\n\n");

        // [Output]
        sb.append("[Output]\n请基于以上信息回答问题。");

        return sb.toString();
    }

    /**
     * Compress: Token 预算控制
     * 如果超出预算，进行简单截断或摘要
     */
    private String compressIfNeeded(String context) {
        // 粗略估算：1 Token ≈ 2 字符
        int estimatedTokens = context.length() / 2;

        if (estimatedTokens <= maxTokens) {
            return context;
        }

        log.info("【GSSC Compress】内容超出预算: {} tokens > {} tokens，进行截断",
                estimatedTokens, maxTokens);

        // 简单策略：按比例保留各部分
        // 实际生产中可以使用 LLM 进行摘要压缩
        int maxChars = maxTokens * 2;
        if (context.length() > maxChars) {
            context = context.substring(0, maxChars) + "\n\n[内容已被截断...]";
        }

        return context;
    }

    /**
     * 简单模式（未启用 GSSC 时的降级方案）
     */
    private String buildSimpleContext(
            List<ContextPacket> evidence,
            List<ContextPacket> history,
            List<ContextPacket> toolResults,
            String systemPrompt,
            String userQuery) {

        StringBuilder sb = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append("[System]\n").append(systemPrompt).append("\n\n");
        }

        if (evidence != null && !evidence.isEmpty()) {
            sb.append("[Evidence]\n");
            for (ContextPacket packet : evidence) {
                sb.append(packet.getContent()).append("\n\n---\n\n");
            }
        }

        if (toolResults != null && !toolResults.isEmpty()) {
            sb.append("[工具执行结果]\n");
            for (ContextPacket packet : toolResults) {
                String toolName = packet.getMetadata() != null 
                        ? (String) packet.getMetadata().getOrDefault("toolName", "未知工具") 
                        : "未知工具";
                sb.append(String.format("【%s】: %s\n\n", toolName, packet.getContent()));
            }
        }

        if (history != null && !history.isEmpty()) {
            sb.append("[Context]\n");
            for (ContextPacket packet : history) {
                sb.append(packet.getContent()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("[Task]\n").append(userQuery);

        return sb.toString();
    }

    /**
     * 预估 Token 数量（简单估算）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 粗略估算：1 Token ≈ 2 字符
        return text.length() / 2;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

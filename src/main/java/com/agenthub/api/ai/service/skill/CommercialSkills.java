package com.agenthub.api.ai.service.skill;

import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.ai.domain.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.domain.worker.CommercialAuditResult;
import com.agenthub.api.ai.domain.workflow.WorkerResult;
import com.agenthub.api.prompt.context.PromptContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 商务技能服务
 */
@Slf4j
@Service
public class CommercialSkills {

    private final ChatClient chatClient;
    private final PowerKnowledgeService knowledgeService;

    public CommercialSkills(ChatClient.Builder builder, PowerKnowledgeService knowledgeService) {
        this.chatClient = builder.build();
        this.knowledgeService = knowledgeService;
    }

    /**
     * 执行单项商务审查
     * 注意：这里返回的是同步的 WorkerResult，异步由 Worker 层控制
     */
    public WorkerResult auditCommercialTerm(String itemName, String contractContent) {

        // 1. 动态决定 Prompt 策略
        String specificInstruction = switch (itemName) {
            case "投标保证金" -> "重点审查：1.金额是否超过估算价2%？ 2.是否有80万元上限限制？";
            case "付款周期" -> "重点审查：1.是否符合D+X日的结算规定？ 2.是否拖延支付？";
            case "价格条款" -> "重点审查：1.是否包含负电价机制？ 2.价格单位是否为 元/MWh？";
            default -> "请严格对比参考资料，指出不合规之处。";
        };

        // 2. 查据 (RAG)
        // 使用 CATEGORY=BUSINESS 锁定商务文档
        var knowledge = knowledgeService.retrieve(new PowerKnowledgeQuery(itemName + " 规定", 3, null, "BUSINESS"));

        // 3. 获取数据库提示词模板，并进行变量替换
        String basePrompt = PromptContextHolder.getSkill("SKILL-AUDIT-COMMERCIAL");
        if (basePrompt == null || basePrompt.isEmpty()) {
            // 降级：使用硬编码默认提示词
            basePrompt = """
                你是一个电力商务合规专家。
                任务：审查合同中的【{item}】条款。

                核心关注点：
                {instruction}

                参考依据：
                {rules}
                """;
            log.debug("[CommercialSkills] 使用默认硬编码提示词（数据库未配置 SKILL-AUDIT-COMMERCIAL）");
        }

        // 替换 FreeMarker 风格的占位符
        String systemPrompt = basePrompt
                .replace("{item}", itemName)
                .replace("{instruction}", specificInstruction)
                .replace("{rules}", knowledge.answer());

        // 4. 执行 AI 调用 (解析为临时的 CommercialAuditResult)
        // 这一步是为了利用 BeanOutputConverter 自动解析 JSON
        CommercialAuditResult llmRawResult = chatClient.prompt()
                .system(systemPrompt)
                .user(u -> u.text("合同片段：\n{content}")
                        .param("content", contractContent))
                .call()
                .entity(CommercialAuditResult.class); // 临时对象

        // 4. [关键一步] 适配器模式：将 RAG 证据转为标准格式
        List<WorkerResult.SourceEvidence> evidences = (knowledge.sources() == null)
                ? Collections.emptyList()
                : knowledge.sources().stream()
                .map(s -> new WorkerResult.SourceEvidence(s.filename(), s.downloadUrl())) // ✅ 保留下载链接
                .toList();

        // 5. [关键一步] 结果封装：返回标准的 WorkerResult
        return new WorkerResult(
                itemName,                           // 检查项
                WorkerResult.WorkerType.COMMERCIAL,              // 工种：商务
                llmRawResult.passed(),              // 结论
                llmRawResult.detail(),              // 风险详情
                llmRawResult.requirement(),         // 建议/要求
                evidences                           // 证据链
        );
    }



}

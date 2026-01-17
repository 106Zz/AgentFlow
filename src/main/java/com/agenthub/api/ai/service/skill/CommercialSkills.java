package com.agenthub.api.ai.service.skill;

import com.agenthub.api.ai.service.PowerKnowledgeService;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.worker.CommercialAuditResult;
import lombok.RequiredArgsConstructor;
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
     * 智能路由版技能：虽然方法只有一个，但内部有"千人千面"的 Prompt 策略
     */
    public CommercialAuditResult auditCommercialTerm(String itemName, String contractContent) {

        // 1. 动态决定 Prompt 侧重点 (Prompt Engineering)
        String specificInstruction = switch (itemName) {
            case "投标保证金" -> "重点审查：1.金额是否超过估算价2%？ 2.是否有80万元上限限制？";
            case "付款周期" -> "重点审查：1.是否符合D+X日的结算规定？ 2.是否拖延支付？";
            case "价格条款" -> "重点审查：1.是否包含负电价机制？ 2.价格单位是否为 元/MWh？";
            default -> "请严格对比参考资料，指出不合规之处。"; // 兜底通用策略
        };

        // 2. 查据 (保持不变，利用 category=BUSINESS 物理隔离)
        var knowledge = knowledgeService.retrieve(new PowerKnowledgeQuery(itemName + " 规定", 3, null, "BUSINESS"));

        // 3. 执行 AI 调用
        CommercialAuditResult llmResult = chatClient.prompt()
                .system(s -> s.text("""
                    你是一个电力商务合规专家。
                    任务：审查合同中的【{item}】条款。
                    
                    核心关注点：
                    {instruction}  <-- 动态注入的专用策略
                    
                    参考依据：
                    {rules}
                    """))
                .user(u -> u.text("合同片段：\n{content}")
                        .param("item", itemName)
                        .param("instruction", specificInstruction) // 注入策略
                        .param("rules", knowledge.answer())
                        .param("content", contractContent))
                .call()
                .entity(CommercialAuditResult.class);

        List<CommercialAuditResult.Source> realSources = (knowledge.sources() == null)
                ? Collections.emptyList()
                : knowledge.sources().stream()
                .map(s -> new CommercialAuditResult.Source(
                        s.filename(),     // 从 RAG 结果拿文件名
                        s.downloadUrl()   // 从 RAG 结果拿 OSS 链接
                ))
                .toList();

        // 4. 证据回填 (Data Lineage)
        return new CommercialAuditResult(
                llmResult.item(),
                llmResult.passed(),
                llmResult.detail(),
                llmResult.requirement(),
                realSources
        );
    }



}

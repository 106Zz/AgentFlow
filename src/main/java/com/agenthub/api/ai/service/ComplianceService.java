package com.agenthub.api.ai.service;

import com.agenthub.api.ai.tool.Compliance.ComplianceCheckRequest;
import com.agenthub.api.ai.tool.Compliance.ComplianceCheckResult;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeQuery;
import com.agenthub.api.ai.tool.knowledge.PowerKnowledgeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

/**
 * 合规审查
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceService {

    private final PowerKnowledgeService knowledgeService; // 你已有的 RAG Service
    // 2. 注入 ChatClient.Builder (用来构建一个内部专用的审查机器人)
    private final ChatClient.Builder chatClientBuilder;


    /**
     * 执行审查的核心方法
     */
    public ComplianceCheckResult audit(ComplianceCheckRequest request){
        log.info("开始执行合规审查，场景: {}", request.scene());

        // Step 1: 构造查询参数
        // 严格匹配你的 PowerKnowledgeQuery record 结构：(query, topK, yearFilter)
        // 这里我们把用户的待审文本直接作为 query，或者你可以让 AI 先提取关键词
        PowerKnowledgeQuery ragQuery = new PowerKnowledgeQuery(
                request.content(), // String query
                5,                 // Integer topK (查5条，多给点上下文)
                null               // String yearFilter (暂时给null，除非你想解析出来)
        );

        // Step 2: 调用你现有的 retrieve 方法
        PowerKnowledgeResult ragResult = knowledgeService.retrieve(ragQuery);

        // Step 3: 把查到的规则拼成字符串
        // 从你的 PowerKnowledgeResult 里取 sourceNames 和 snippets
        String ruleContext = "";
        if (ragResult.rawContentSnippets() != null) {
            ruleContext = String.join("\n---\n", ragResult.rawContentSnippets());
        }

        // Step 4: 让 AI 进行比对 (AI 逻辑)
        // 定义转换器，强制转 JSON
        var converter = new BeanOutputConverter<>(ComplianceCheckResult.class);

        String prompt = """
            你是一个电力合规审查员。
            
            【权威规则】：
            %s
            
            【用户待审内容】：
            %s
            
            请判断【用户待审内容】是否违反了【权威规则】。
            如果规则里没提，就认为合规。
            只输出 JSON。
            """
                .formatted(ruleContext, request.content(),converter.getFormat());

        log.info("合规审查提示词: {}", prompt);

        String jsonResult = chatClientBuilder.build()
                .prompt()
                .system("严格审查，输出 JSON")
                .user(prompt)
                .call()
                .content();

        return converter.convert(jsonResult);
    }




}

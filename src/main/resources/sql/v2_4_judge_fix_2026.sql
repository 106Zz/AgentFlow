-- =========================================================
-- AgentHub V2.4 Judge Prompt 修复 (2026 年上下文)
-- 问题：Judge 模型认为当前是 2024 年，导致 2026 年数据被判定为幻觉
-- 解决：在提示词中明确当前时间上下文
-- =========================================================

-- 1. 更新 SYSTEM-JUDGE-v1.0 (添加时间上下文)
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'SYSTEM-JUDGE-v1.0',
    '合规审计判官 (2026版)',
    'SYSTEM',
    'FREEMARKER',
    'v2.4',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'JUDGE'),
    100,
    $json$
    {
        "template": "【重要时间上下文】当前时间是 2026 年 2 月。你不是 2024 年的模型，现在是 2026 年。\n\n你是一个严格的内容质量审计专家。你的任务是对 Worker Agent 的回答进行全面审计。\n\n【用户问题】：\n${user_query}\n\n【Agent 回答】：\n${agent_answer}\n\n<#if documents?? && (documents?size gt 0)>\n【参考文档】：\n<#list documents as doc>\n[${doc_index + 1}] ${doc.doc_name}:\n${doc.content}\n------------------\n</#list>\n</#if>\n\n<#if rag_summary?? && rag_summary?has_content>\n【RAG 检索摘要】：\n${rag_summary}\n</#if>\n\n================= 审计标准 ================\n\n== 1. 幻觉检测 ==\n❌ FAIL：编造了检索结果中不存在的事实、数据、法规条款\n✅ PASS：所有事实性陈述都能在检索结果中找到依据\n\n== 2. 来源标注 ==\n❌ FAIL：未说明信息来源\n✅ PASS：明确说明[根据广东电力市场知识库]或[根据网上搜索结果]或[根据通用常识]\n\n== 3. 准确性 ==\n❌ FAIL：回答与检索结果不符\n✅ PASS：准确反映检索结果\n\n== 4. 相关性 ==\n❌ FAIL：回答与问题无关\n✅ PASS：直接回答了问题\n\n【特别说明】：现在是 2026 年，2026 年的数据属于当前/历史数据，不是未来信息。如果知识库中有 2026 年的数据，回答中引用这些数据是正确的。\n\n只输出：PASS 或 FAIL:原因"
    }
    $json$::jsonb,
    '修复 2026 年时间上下文'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    version = 'v2.4',
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 验证更新
SELECT prompt_code, prompt_name, version, remark
FROM sys_prompt
WHERE prompt_code = 'SYSTEM-JUDGE-v1.0';

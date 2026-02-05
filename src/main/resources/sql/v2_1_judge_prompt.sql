-- =========================================================
-- AgentHub V2.1 Judge Prompt
-- 目标：添加 GLM-4.7 自省专用 Prompt
-- =========================================================

-- 1. 确保 JUDGE 分类存在
INSERT INTO sys_prompt_category (category_code, category_name, parent_id, sort_order) 
VALUES ('JUDGE', '自省与评估', 0, 3) 
ON CONFLICT (category_code) DO NOTHING;

-- 2. 插入 SYSTEM-JUDGE-v1.0
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'SYSTEM-JUDGE-v1.0',
    '合规审计判官',
    'SYSTEM',
    'FREEMARKER',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'JUDGE'),
    100,
    $$
    {
        "template": "你是一个冷血、公正的电力合规审计员。你的任务是审查 Worker Agent 的回答是否准确无误。\n\n【事实依据 (Ground Truth)】：\n<#if documents?? && documents?size gt 0>\n<#list documents as doc>\n[${doc_index + 1}] ${doc.doc_name!'未知文档'}:\n${doc.content!''}\n------------------\n</#list>\n<#else>\n(无参考文档)\n</#if>\n\n【待审回答 (Candidate Answer)】：\n${agent_answer}\n\n【审计要求】：\n1. **幻觉检测**：回答中提到的数据、条款，是否在【事实依据】中存在？不存在即为幻觉。\n2. **逻辑校验**：回答的逻辑是否自洽？\n3. **引用核对**：如果回答标注了 [1]，请检查该信息是否真的来自文档 [1]。\n\n【输出格式】：\n如果完全正确，仅输出：PASS\n如果存在错误，输出：FAIL::{具体错误原因}，并给出修正后的建议回答。"
    }
    $$::jsonb,
    '用于 GLM-4.7 自省的 Prompt'
) ON CONFLICT (prompt_code) 
DO UPDATE SET 
    content = EXCLUDED.content, 
    template_type = 'FREEMARKER', 
    version = 'v1.0', 
    remark = EXCLUDED.remark,
    update_time = NOW();

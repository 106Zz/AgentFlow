-- =========================================================
-- AgentHub V2.3 反思服务与工具更新
-- =========================================================

-- 1. 更新 SYSTEM-JUDGE-v1.0 (增强幻觉检测)
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'SYSTEM-JUDGE-v1.0',
    '合规审计判官 (增强版)',
    'SYSTEM',
    'FREEMARKER',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'JUDGE'),
    100,
    $json$
    {
        "template": "你是一个严格的内容质量审计专家。你的任务是对 Worker Agent 的回答进行全面审计。\n\n【用户问题】：\n${user_query}\n\n【Agent 回答】：\n${agent_answer}\n\n<#if documents?? && (documents?size gt 0)>\n【参考文档】：\n<#list documents as doc>\n[${doc_index + 1}] ${doc.doc_name}:\n${doc.content}\n------------------\n</#list>\n</#if>\n\n<#if rag_summary?? && rag_summary?has_content>\n【RAG 检索摘要】：\n${rag_summary}\n</#if>\n\n================= 审计标准 ================\n\n== 1. 幻觉检测 ==\n❌ FAIL：编造了检索结果中不存在的事实、数据、法规条款\n✅ PASS：所有事实性陈述都能在检索结果中找到依据\n\n== 2. 来源标注 ==\n❌ FAIL：未说明信息来源\n✅ PASS：明确说明[根据广东电力市场知识库]或[根据网上搜索结果]或[根据通用常识]\n\n== 3. 准确性 ==\n❌ FAIL：回答与检索结果不符\n✅ PASS：准确反映检索结果\n\n== 4. 相关性 ==\n❌ FAIL：回答与问题无关\n✅ PASS：直接回答了问题\n\n只输出：PASS 或 FAIL:原因"
    }
    $json$::jsonb,
    '增强版 Judge Prompt'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 2. 更新 TOOL-KNOWLEDGE_SEARCH-v1.0
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-KNOWLEDGE_SEARCH-v1.0',
    '知识库检索工具',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'KNOWLEDGE'),
    10,
    $json$
    {
        "template": "广东电力市场专属知识库工具。当用户询问电价、交易规则、结算公式、费用、补偿、合约等技术或业务问题时使用。\n\n参数说明：\n - query: 查询关键词（请提炼核心词）\n - year: 可选，年份上下文\n - category: 可选，业务分类过滤\n   - BUSINESS: 涉及电价、结算公式、费用\n   - TECHNICAL: 涉及技术标准、物理参数\n   - REGULATION: 涉及交易规则、管理办法"
    }
    $json$::jsonb,
    '知识库检索工具'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 3. 更新 TOOL-POWERKNOWLEDGE-v1.0
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-POWERKNOWLEDGE-v1.0',
    '知识库工具描述',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'KNOWLEDGE'),
    10,
    $json$
    {
        "template": "广东电力市场专属知识库工具。当用户询问电价、交易规则、结算公式、费用、补偿、合约等问题时使用。\n\n参数说明：\n - query: 查询关键词（请提炼核心词）\n - category: 可选，业务分类过滤"
    }
    $json$::jsonb,
    '知识库工具描述'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 4. 确保 WEB_SEARCH 分类存在
INSERT INTO sys_prompt_category (category_code, category_name, parent_id, sort_order)
VALUES ('WEB_SEARCH', '搜索工具', 0, 6)
ON CONFLICT (category_code) DO NOTHING;

-- 5. 确保 TIME_TOOL 分类存在
INSERT INTO sys_prompt_category (category_code, category_name, parent_id, sort_order)
VALUES ('TIME_TOOL', '时间工具', 0, 7)
ON CONFLICT (category_code) DO NOTHING;

-- 6. 新增 web_search 工具
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-WEB_SEARCH-v1.0',
    '互联网搜索工具',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'WEB_SEARCH'),
    10,
    $json$
    {
        "template": "互联网搜索工具。使用 Bing Search API 搜索最新信息。当用户询问时事新闻、最新政策、市场行情、技术进展等需要最新信息的问题时使用。\n\n参数说明：\n - query: 搜索关键词或问题（必填）\n - count: 可选，返回结果数量，默认5条\n\n注意：使用此工具后，回答时必须标注信息来源为[根据网上搜索结果]。"
    }
    $json$::jsonb,
    '互联网搜索工具'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 7. 新增 get_current_time 工具
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-GET_CURRENT_TIME-v1.0',
    '获取当前时间工具',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'TIME_TOOL'),
    10,
    $json$
    {
        "template": "获取当前日期和时间工具。当用户询问今天几号、现在几点、本周是第几周、星期几等时间相关问题时使用。\n\n参数说明：\n - timezone: 可选，时区，默认 Asia/Shanghai\n - format: 可选，返回格式\n   - full: 完整日期时间（默认）\n   - date: 仅日期\n   - time: 仅时间\n   - week: 星期信息"
    }
    $json$::jsonb,
    '获取当前时间工具'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 8. 更新 SYSTEM-RAG-v1.0 添加来源标注
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'SYSTEM-RAG-v1.0',
    'RAG 系统提示词',
    'SYSTEM',
    'FREEMARKER',
    'v2.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'RAG'),
    100,
    $json$
    {
        "template": "你是一个电力行业的智能助手。你可以使用以下工具来解决用户的问题：\n\n${tools_desc}\n\n<#if documents?? && documents?size gt 0>\n【参考文档】：\n<#list documents as doc>\n[${doc_index + 1}] ${doc.doc_name}:\n${doc.content}\n</#list>\n\n【回复要求】：\n1. 严禁编造，只使用参考文档中的信息\n2. 每处关键信息标注来源，格式为 [编号]\n3. 列出参考的文档名称\n</#if>\n\n【重要：回答必须标注信息来源】\n1. 使用 knowledge_search 工具，请标注：[根据广东电力市场知识库]\n2. 使用 web_search 工具，请标注：[根据网上搜索结果]\n3. 基于通用知识，请标注：[根据通用常识]"
    }
    $json$::jsonb,
    'RAG 系统提示词 v2.0'
) ON CONFLICT (prompt_code)
DO UPDATE SET
    content = EXCLUDED.content,
    remark = EXCLUDED.remark,
    update_time = NOW();

-- 验证
SELECT prompt_code, prompt_name, version FROM sys_prompt
WHERE prompt_code IN (
    'SYSTEM-JUDGE-v1.0',
    'TOOL-KNOWLEDGE_SEARCH-v1.0',
    'TOOL-POWERKNOWLEDGE-v1.0',
    'TOOL-WEB_SEARCH-v1.0',
    'TOOL-GET_CURRENT_TIME-v1.0',
    'SYSTEM-RAG-v1.0'
)
ORDER BY prompt_code;

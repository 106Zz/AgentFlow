-- =========================================================
-- AgentHub V2.2 Tool Prompts
-- 目标：补全缺失的工具 Prompt，消除 "Prompt not found" 警告
-- =========================================================

-- 1. 确保分类存在
INSERT INTO sys_prompt_category (category_code, category_name, parent_id, sort_order) 
VALUES ('CALCULATOR', '计算工具', 0, 4)
ON CONFLICT (category_code) DO NOTHING;

INSERT INTO sys_prompt_category (category_code, category_name, parent_id, sort_order) 
VALUES ('AUDIT', '审查工具', 0, 5)
ON CONFLICT (category_code) DO NOTHING;

-- 2. 知识检索工具 (修正 Code)
-- 代码中 formatToolDesc 使用的是 Tool Name (knowledge_search)
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
    $$
    {
        "template": "【必须调用】广东电力市场专属知识库工具。\n参数说明：\n - query: 查询关键词（请提炼核心词，如'2026年交易时间'）。\n - year: [可选] 年份上下文（如'2026'）。\n - category: [可选] 业务分类过滤。\n   - 'BUSINESS': 涉及电价、结算公式、费用、补偿、合约。\n   - 'TECHNICAL': 涉及技术标准、物理参数、接入规范。\n   - 'REGULATION': 涉及交易规则、管理办法、政策通知。"
    }
    $$::jsonb,
    '知识库工具描述 (Match Tool Name: knowledge_search)'
) ON CONFLICT (prompt_code) 
DO UPDATE SET content = EXCLUDED.content, remark = EXCLUDED.remark, update_time = NOW();

-- 3. 偏差计算工具 (calculate_deviation)
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-CALCULATE_DEVIATION-v1.0',
    '偏差考核计算器',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'CALCULATOR'),
    10,
    $$
    {
        "template": "【偏差考核计算器】用于计算电力市场的偏差考核费用。\n参数说明：\n - plan: 计划电量 (MWh)\n - actual: 实际电量 (MWh)\n - price: 市场价格 (元/MWh)\n - threshold: [可选] 偏差阈值 (默认 0.02)\n注意：仅在用户提供了具体的数值时调用，不要自己编造数值。"
    }
    $$::jsonb,
    '偏差计算工具描述'
) ON CONFLICT (prompt_code) 
DO UPDATE SET content = EXCLUDED.content, remark = EXCLUDED.remark, update_time = NOW();

-- 4. 合规审查工具 (audit_contract)
INSERT INTO sys_prompt (
    prompt_code, prompt_name, prompt_type, template_type,
    version, is_active, category_id, priority, content, remark
) VALUES (
    'TOOL-AUDIT_CONTRACT-v1.0',
    '合规审查工具',
    'TOOL',
    'TEXT',
    'v1.0',
    TRUE,
    (SELECT id FROM sys_prompt_category WHERE category_code = 'AUDIT'),
    10,
    $$
    {
        "template": "【合规审查工具】用于审查合同文本或标书的合规性。\n参数说明：\n - content: 需要审查的文本内容片段（必填）。\n - rules: [可选] 特定的审查规则（如'支付条款'、'违约责任'）。\n限制：如果文本过长，请先调用知识库检索相关规则。"
    }
    $$::jsonb,
    '合规审查工具描述'
) ON CONFLICT (prompt_code) 
DO UPDATE SET content = EXCLUDED.content, remark = EXCLUDED.remark, update_time = NOW();

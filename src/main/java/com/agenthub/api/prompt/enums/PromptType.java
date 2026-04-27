package com.agenthub.api.prompt.enums;

/**
 * 提示词类型枚举
 * <p>对应 AgentHub v4.0 架构的不同层级，与 {@code sys_prompt_category} 表一一对应：</p>
 *
 * <pre>
 * ROUTER      → id=1  'Router 层'      意图分类、用例分发
 * SYSTEM      → id=2  'System 层'      RAG 系统级提示、通用后处理规则
 *   └─ RAG    → id=8  'RAG 系统'       (System 子分类)
 * SKILL       → id=3  'Skill 层'       从自然语言中提取结构化参数
 *   ├─ 商务   → id=9  '商务审查'
 *   └─ 合规   → id=10 '合规参数提取'
 * TOOL        → id=4  'Tool 层'        Tool 功能描述和使用说明
 *   ├─ 知识库 → id=11 '知识库工具'
 *   └─ 计算   → id=12 '计算工具'
 * WORKER      → id=5  'Worker 层'      工作流编排、任务执行指导
 * FEWSHOT     → id=6  'FewShot 示例'   少样本学习示例
 * POST_PROCESS→ id=7  '后处理提示词'   输出格式转换、结果校验
 * </pre>
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public enum PromptType {

    /**
     * 路由层提示词（v4.0 新增）
     * <p>用于意图分类、用例分发</p>
     * <p>对应：RouterService</p>
     */
    ROUTER,

    /**
     * 系统提示词
     * <p>RAG 系统级提示、通用后处理规则</p>
     * <p>对应：rag-system-prompt.st</p>
     */
    SYSTEM,

    /**
     * Skill 提示词（参数提取）
     * <p>从自然语言中提取结构化参数</p>
     * <p>对应：CommercialSkills, ComplianceSkills</p>
     */
    SKILL,

    /**
     * 工具提示词
     * <p>Tool 的功能描述和使用说明</p>
     * <p>对应：PowerKnowledgeTool, ElectricityFormulaTool</p>
     */
    TOOL,

    /**
     * Worker 层提示词（v4.0 新增）
     * <p>工作流编排、任务执行指导</p>
     * <p>对应：CommercialWorker, CalculationWorker</p>
     */
    WORKER,

    /**
     * FewShot 示例
     * <p>少样本学习示例</p>
     */
    FEWSHOT,

    /**
     * 后处理提示词
     * <p>输出格式转换、结果校验</p>
     */
    POST_PROCESS
}

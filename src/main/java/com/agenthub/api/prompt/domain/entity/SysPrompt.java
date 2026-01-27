package com.agenthub.api.prompt.domain.entity;

import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.util.List;

/**
 * 提示词主表实体
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_prompt")
public class SysPrompt extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 提示词代码（唯一标识）
     * 格式：{TYPE}-{NAME}-{VERSION}
     * 示例：SYSTEM-RAG-v1.0, SKILL-EXTRACT-CALC-PARAMS-v1.0
     */
    private String promptCode;

    /**
     * 提示词名称
     */
    private String promptName;

    /**
     * 提示词类型
     */
    private PromptType promptType;

    /**
     * 提示词内容（JSONB 格式）
     * {
     *   "template": "模板内容",
     *   "variables": {"变量定义"},
     *   "metadata": {"元数据"}
     * }
     */
    private JsonNode content;

    /**
     * 模板类型
     */
    private TemplateType templateType;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 标签（逗号分隔）
     */
    private String tags;

    /**
     * 当前版本号
     */
    private String version;

    /**
     * 是否激活
     */
    private Boolean isActive;

    /**
     * 是否锁定（锁定后不可修改）
     */
    private Boolean isLocked;

    /**
     * 租户 ID（0 表示系统级）
     */
    private Long tenantId;

    /**
     * 作用域
     */
    private Scope scope;

    /**
     * 优先级（数值越大优先级越高）
     */
    private Integer priority;

    /**
     * 提示词分类（关联查询时填充）
     */
    @TableField(exist = false)
    private SysPromptCategory category;

    /**
     * 提示词标签列表（关联查询时填充）
     */
    @TableField(exist = false)
    private List<SysPromptTag> tagList;

    /**
     * 提示词类型枚举
     *
     * 对应 AgentHub v4.0 架构层级：
     * - ROUTER  → RouterService (意图分发)
     * - SYSTEM  → RAG 系统提示词
     * - SKILL   → CommercialSkills, ComplianceSkills (参数提取)
     * - TOOL    → PowerKnowledgeTool, ElectricityFormulaTool
     * - WORKER  → CommercialWorker, CalculationWorker (工作流编排)
     * - FEWSHOT → 少样本学习示例
     * - POST_PROCESS → 后处理提示词
     */
    public enum PromptType {
        ROUTER,          // 路由层提示词（v4.0 新增）
        SYSTEM,          // 系统提示词
        SKILL,           // Skill 提示词（参数提取）
        TOOL,            // 工具提示词
        WORKER,          // Worker 层提示词（v4.0 新增）
        FEWSHOT,         // FewShot 示例
        POST_PROCESS     // 后处理提示词
    }

    /**
     * 模板类型枚举
     */
    public enum TemplateType {
        FREEMARKER,      // FreeMarker 模板
        SPEL,            // Spring EL 表达式
        TEXT             // 纯文本
    }

    /**
     * 作用域枚举
     */
    public enum Scope {
        GLOBAL,          // 全局
        TENANT,          // 租户级
        USER             // 用户级
    }
}

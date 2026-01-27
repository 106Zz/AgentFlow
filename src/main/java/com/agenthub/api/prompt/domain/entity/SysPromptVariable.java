package com.agenthub.api.prompt.domain.entity;


import com.agenthub.api.common.base.BaseEntity;
import com.agenthub.api.prompt.enums.VariableType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 提示词变量定义实体
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_prompt_variable")
public class SysPromptVariable extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联提示词 ID
     */
    private Long promptId;

    /**
     * 提示词代码
     */
    private String promptCode;

    /**
     * 变量名称
     */
    private String variableName;

    /**
     * 变量键（模板中的变量名，如 ${query}）
     */
    private String variableKey;

    /**
     * 变量类型
     */
    private VariableType variableType;

    /**
     * 默认值（JSON 字符串）
     */
    private String defaultValue;

    /**
     * 是否必填
     */
    private Boolean isRequired;

    /**
     * 验证规则（正则或 JSON Schema）
     */
    private String validationRule;

    /**
     * 验证失败提示
     */
    private String errorMessage;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sortOrder;
}

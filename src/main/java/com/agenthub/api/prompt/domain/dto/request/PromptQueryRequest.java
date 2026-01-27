package com.agenthub.api.prompt.domain.dto.request;

import com.agenthub.api.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 查询提示词请求
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PromptQueryRequest extends PageQuery {
    /**
     * 提示词代码（模糊查询）
     */
    private String promptCode;

    /**
     * 提示词名称（模糊查询）
     */
    private String promptName;

    /**
     * 提示词类型
     */
    private String promptType;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 标签 ID
     */
    private Long tagId;

    /**
     * 是否激活
     */
    private Boolean isActive;

    /**
     * 租户 ID
     */
    private Long tenantId;

    /**
     * 作用域
     */
    private String scope;
}

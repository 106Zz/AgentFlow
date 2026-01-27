package com.agenthub.api.prompt.domain.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * 更新提示词请求
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
public class PromptUpdateRequest {

    /**
     * 提示词 ID
     */
    private Long id;

    /**
     * 提示词名称
     */
    private String promptName;

    /**
     * 提示词内容（JSON 格式）
     */
    private JsonNode content;

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 标签 ID 列表
     */
    private List<Long> tagIds;

    /**
     * 新版本号
     */
    private String version;

    /**
     * 变更原因
     */
    private String changeReason;

    /**
     * 是否激活
     */
    private Boolean isActive;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 备注
     */
    private String remark;
}

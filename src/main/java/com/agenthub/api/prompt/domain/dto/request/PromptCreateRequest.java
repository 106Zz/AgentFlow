package com.agenthub.api.prompt.domain.dto.request;


import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 创建提示词请求
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
public class PromptCreateRequest {

    /**
     * 提示词代码
     */
    @NotBlank(message = "提示词代码不能为空")
    private String promptCode;

    /**
     * 提示词名称
     */
    @NotBlank(message = "提示词名称不能为空")
    private String promptName;

    /**
     * 提示词类型
     */
    @NotNull(message = "提示词类型不能为空")
    private String promptType;

    /**
     * 提示词内容（JSON 格式）
     */
    @NotNull(message = "提示词内容不能为空")
    private JsonNode content;

    /**
     * 模板类型
     */
    private String templateType = "FREEMARKER";

    /**
     * 分类 ID
     */
    private Long categoryId;

    /**
     * 标签 ID 列表
     */
    private List<Long> tagIds;

    /**
     * 版本号
     */
    private String version = "v1.0";

    /**
     * 租户 ID
     */
    private Long tenantId = 0L;

    /**
     * 作用域
     */
    private String scope = "GLOBAL";

    /**
     * 优先级
     */
    private Integer priority = 0;

    /**
     * 备注
     */
    private String remark;
}

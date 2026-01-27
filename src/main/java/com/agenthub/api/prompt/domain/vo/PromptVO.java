package com.agenthub.api.prompt.domain.vo;


import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 提示词视图对象
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
public class PromptVO {

    /**
     * 提示词 ID
     */
    private Long id;

    /**
     * 提示词代码
     */
    private String promptCode;

    /**
     * 提示词名称
     */
    private String promptName;

    /**
     * 提示词类型
     */
    private String promptType;

    /**
     * 提示词模板内容
     */
    private String template;

    /**
     * 提示词完整内容
     */
    private JsonNode content;

    /**
     * 模板类型
     */
    private String templateType;

    /**
     * 分类名称
     */
    private String categoryName;

    /**
     * 标签列表
     */
    private List<TagVO> tags;

    /**
     * 版本号
     */
    private String version;

    /**
     * 是否激活
     */
    private Boolean isActive;

    /**
     * 是否锁定
     */
    private Boolean isLocked;

    /**
     * 作用域
     */
    private String scope;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 备注
     */
    private String remark;

    /**
     * 标签 VO
     */
    @Data
    @Builder
    public static class TagVO {
        private Long id;
        private String tagName;
        private String tagColor;
    }
}

package com.agenthub.api.prompt.domain.entity;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

/**
 * 提示词版本历史实体
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_prompt_version")
public class SysPromptVersion extends BaseEntity {

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
     * 版本号
     */
    private String version;

    /**
     * 版本名称
     */
    private String versionName;

    /**
     * 版本内容快照
     */
    private JsonNode content;

    /**
     * 变更类型
     */
    private ChangeType changeType;

    /**
     * 变更原因
     */
    private String changeReason;

    /**
     * 从哪个版本变更
     */
    private String changeFromVersion;

    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        CREATE,          // 创建
        UPDATE,          // 更新
        ROLLBACK         // 回滚
    }
}

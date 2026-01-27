package com.agenthub.api.prompt.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 提示词与标签关联实体
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_prompt_tag_relation")
public class SysPromptTagRelation {

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 提示词 ID
     */
    private Long promptId;

    /**
     * 标签 ID
     */
    private Long tagId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

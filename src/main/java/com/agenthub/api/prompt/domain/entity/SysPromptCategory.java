package com.agenthub.api.prompt.domain.entity;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.util.List;

/**
 * 提示词分类实体
 *
 * <h3>预置分类体系：</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ id=1  ROUTER       'Router 层'      (顶级)                   │
 * │ id=2  SYSTEM       'System 层'      (顶级)                   │
 * │   └─ id=8  RAG      'RAG 系统'      (子分类)                 │
 * │ id=3  SKILL        'Skill 层'       (顶级)                   │
 * │   ├─ id=9  COMMERCIAL  '商务审查'    (子分类)                 │
 * │   └─ id=10 COMPLIANCE  '合规参数提取' (子分类)                │
 * │ id=4  TOOL         'Tool 层'        (顶级)                   │
 * │   ├─ id=11 KNOWLEDGE   '知识库工具'  (子分类)                 │
 * │   └─ id=12 CALCULATOR  '计算工具'    (子分类)                 │
 * │ id=5  WORKER       'Worker 层'      (顶级)                   │
 * │ id=6  FEWSHOT      'FewShot 示例'   (顶级)                   │
 * │ id=7  POST_PROCESS '后处理提示词'   (顶级)                   │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @author AgentHub
 * @since 2026-01-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_prompt_category")
public class SysPromptCategory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 分类代码
     */
    private String categoryCode;

    /**
     * 分类名称
     */
    private String categoryName;

    /**
     * 父分类 ID（0 表示顶级）
     */
    private Long parentId;

    /**
     * 图标
     */
    private String icon;

    /**
     * 排序
     */
    private Integer sortOrder;

    /**
     * 子分类列表（关联查询时填充）
     */
    @TableField(exist = false)
    private List<SysPromptCategory> children;
}

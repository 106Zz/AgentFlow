package com.agenthub.api.knowledge.domain;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库对象 knowledge_base
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_base")
public class KnowledgeBase extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 知识库ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 知识标题
     */
    @NotBlank(message = "知识标题不能为空")
    private String title;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件类型（pdf/excel/word/image等）
     */
    private String fileType;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 知识分类
     */
    private String category;

    /**
     * 标签（多个用逗号分隔）
     */
    private String tags;

    /**
     * 知识内容（文本提取后的内容）
     */
    private String content;

    /**
     * 知识摘要
     */
    private String summary;

    /**
     * 向量化状态（0未处理 1处理中 2已完成 3失败）
     */
    private String vectorStatus;

    /**
     * 向量化后的文档块数量
     */
    private Integer vectorCount;

    /**
     * 所属用户ID（0表示全局知识库）
     */
    private Long userId;

    /**
     * 是否公开（0私有 1公开）
     */
    private String isPublic;

    /**
     * 状态（0正常 1停用）
     */
    private String status;
}

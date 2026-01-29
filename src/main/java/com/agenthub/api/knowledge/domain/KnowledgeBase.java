package com.agenthub.api.knowledge.domain;


import com.agenthub.api.common.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
     * 知识库ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
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
     * 向量化状态
     * <ul>
     *   <li>0 - 待上传：记录已创建，等待 OSS 上传（file_path=NULL）</li>
     *   <li>1 - 待处理：OSS 已上传完成，等待分词/向量化（file_path 有值）</li>
     *   <li>2 - 处理中：正在分词/向量化中</li>
     *   <li>3 - 成功：文档处理完成，向量已入库</li>
     *   <li>4 - 失败：处理失败，可重新触发处理</li>
     * </ul>
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

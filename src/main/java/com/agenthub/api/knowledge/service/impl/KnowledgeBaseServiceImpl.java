package com.agenthub.api.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.common.exception.ServiceException;
import com.agenthub.api.knowledge.domain.KnowledgeBase;
import com.agenthub.api.knowledge.mapper.KnowledgeBaseMapper;
import com.agenthub.api.knowledge.service.IKnowledgeBaseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库服务实现类
 */
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase> 
        implements IKnowledgeBaseService {

    @Override
    public PageResult<KnowledgeBase> selectKnowledgePage(KnowledgeBase knowledge, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StrUtil.isNotEmpty(knowledge.getTitle()), KnowledgeBase::getTitle, knowledge.getTitle())
                .eq(StrUtil.isNotEmpty(knowledge.getCategory()), KnowledgeBase::getCategory, knowledge.getCategory())
                .eq(StrUtil.isNotEmpty(knowledge.getStatus()), KnowledgeBase::getStatus, knowledge.getStatus())
                .orderByDesc(KnowledgeBase::getCreateTime);
        
        IPage<KnowledgeBase> page = this.page(pageQuery.build(), wrapper);
        return PageResult.build(page);
    }

    @Override
    public KnowledgeBase uploadKnowledge(MultipartFile file, KnowledgeBase knowledge) {
        // TODO: 实现文件上传逻辑
        // 1. 保存文件到本地或OSS
        // 2. 提取文件信息
        // 3. 保存到数据库
        // 4. 异步处理向量化
        
        if (file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        
        // 设置文件信息
        knowledge.setFileName(file.getOriginalFilename());
        knowledge.setFileSize(file.getSize());
        knowledge.setVectorStatus("0"); // 未处理
        
        // 保存到数据库
        this.save(knowledge);
        
        return knowledge;
    }

    @Override
    public void processAndVectorize(Long knowledgeId) {
        // TODO: 实现向量化处理
        // 1. 读取文件
        // 2. 解析内容
        // 3. 分块
        // 4. 向量化
        // 5. 存储到向量数据库
        
        KnowledgeBase knowledge = this.getById(knowledgeId);
        if (knowledge == null) {
            throw new ServiceException("知识库不存在");
        }
        
        // 更新状态为处理中
        knowledge.setVectorStatus("1");
        this.updateById(knowledge);
    }

    @Override
    public PageResult<KnowledgeBase> selectUserKnowledgePage(Long userId, KnowledgeBase knowledge, PageQuery pageQuery) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        
        // 用户可以看到：全局公开的 + 自己的
        wrapper.and(w -> w
                .and(w1 -> w1.eq(KnowledgeBase::getUserId, 0).eq(KnowledgeBase::getIsPublic, "1"))
                .or()
                .eq(KnowledgeBase::getUserId, userId)
        );
        
        // 其他查询条件
        wrapper.like(StrUtil.isNotEmpty(knowledge.getTitle()), KnowledgeBase::getTitle, knowledge.getTitle())
                .eq(StrUtil.isNotEmpty(knowledge.getCategory()), KnowledgeBase::getCategory, knowledge.getCategory())
                .eq(StrUtil.isNotEmpty(knowledge.getStatus()), KnowledgeBase::getStatus, knowledge.getStatus())
                .orderByDesc(KnowledgeBase::getCreateTime);
        
        IPage<KnowledgeBase> page = this.page(pageQuery.build(), wrapper);
        return PageResult.build(page);
    }
}

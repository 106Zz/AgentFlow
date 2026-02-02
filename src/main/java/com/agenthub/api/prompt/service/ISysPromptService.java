package com.agenthub.api.prompt.service;

import com.agenthub.api.common.core.page.PageQuery;
import com.agenthub.api.common.core.page.PageResult;
import com.agenthub.api.prompt.domain.entity.SysPrompt;
import com.agenthub.api.prompt.domain.dto.request.PromptCreateRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptQueryRequest;
import com.agenthub.api.prompt.domain.dto.request.PromptUpdateRequest;
import com.agenthub.api.prompt.domain.vo.PromptVO;
import com.agenthub.api.prompt.enums.PromptType;

import java.util.List;
import java.util.Map;

/**
 * 提示词管理 Service
 *
 * @author AgentHub
 * @since 2026-01-27
 */
public interface ISysPromptService {

    /**
     * 分页查询提示词列表
     *
     * @param request 查询请求
     * @return 分页结果
     */
    PageResult<PromptVO> selectPage(PromptQueryRequest request);

    /**
     * 根据类型查询激活的提示词列表
     *
     * @param promptType 提示词类型
     * @return 提示词列表
     */
    List<SysPrompt> listByType(PromptType promptType);

    /**
     * 根据代码查询提示词
     *
     * @param promptCode 提示词代码
     * @return 提示词实体
     */
    SysPrompt getByCode(String promptCode);

    /**
     * 根据 ID 查询提示词详情（含分类和标签）
     *
     * @param id 提示词 ID
     * @return 提示词 VO
     */
    PromptVO getDetail(Long id);

    /**
     * 创建提示词
     *
     * @param request 创建请求
     * @return 创建的提示词 ID
     */
    Long create(PromptCreateRequest request);

    /**
     * 更新提示词
     *
     * @param request 更新请求
     * @return 是否成功
     */
    Boolean update(PromptUpdateRequest request);

    /**
     * 删除提示词（逻辑删除）
     *
     * @param id 提示词 ID
     * @return 是否成功
     */
    Boolean delete(Long id);

    /**
     * 切换激活状态
     *
     * @param id 提示词 ID
     * @param isActive 是否激活
     * @return 是否成功
     */
    Boolean toggleActive(Long id, Boolean isActive);

    /**
     * 切换锁定状态
     *
     * @param id 提示词 ID
     * @param isLocked 是否锁定
     * @return 是否成功
     */
    Boolean toggleLocked(Long id, Boolean isLocked);

    /**
     * 绑定标签
     *
     * @param promptId 提示词 ID
     * @param tagIds    标签 ID 列表
     * @return 是否成功
     */
    Boolean bindTags(Long promptId, List<Long> tagIds);

    /**
     * 解绑标签
     *
     * @param promptId 提示词 ID
     * @param tagIds    标签 ID 列表
     * @return 是否成功
     */
    Boolean unbindTags(Long promptId, List<Long> tagIds);

    /**
     * 创建版本快照
     *
     * @param promptId    提示词 ID
     * @param changeReason 变更原因
     * @return 版本 ID
     */
    Long createVersionSnapshot(Long promptId, String changeReason);

    /**
     * 回滚到指定版本
     *
     * @param promptId    提示词 ID
     * @param versionId   版本 ID
     * @return 是否成功
     */
    Boolean rollbackToVersion(Long promptId, Long versionId);

    /**
     * 渲染 Prompt
     * 根据 promptCode 找到对应的 Prompt，并使用传入的变量进行渲染
     *
     * @param promptCode 提示词代码
     * @param variables  上下文变量
     * @return 渲染后的文本
     */
    String render(String promptCode, Map<String, Object> variables);
}

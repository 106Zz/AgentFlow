package com.agenthub.api.common.validation;

/**
 * 验证分组
 * 用于区分不同场景下的验证规则
 */
public class ValidationGroups {

    /**
     * 新增操作验证分组
     */
    public interface Create {
    }

    /**
     * 更新操作验证分组
     */
    public interface Update {
    }
}

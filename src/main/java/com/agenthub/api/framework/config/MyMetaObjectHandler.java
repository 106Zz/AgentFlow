package com.agenthub.api.framework.config;

import com.agenthub.api.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充配置
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        
        try {
            String username = SecurityUtils.getUsername();
            this.strictInsertFill(metaObject, "createBy", String.class, username);
            this.strictInsertFill(metaObject, "updateBy", String.class, username);
        } catch (Exception e) {
            this.strictInsertFill(metaObject, "createBy", String.class, "system");
            this.strictInsertFill(metaObject, "updateBy", String.class, "system");
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        
        try {
            String username = SecurityUtils.getUsername();
            this.strictUpdateFill(metaObject, "updateBy", String.class, username);
        } catch (Exception e) {
            this.strictUpdateFill(metaObject, "updateBy", String.class, "system");
        }
    }
}

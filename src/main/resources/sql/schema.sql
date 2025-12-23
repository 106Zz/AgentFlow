-- =====================================================
-- 用户管理模块表结构
-- 说明：这些表用于用户认证、权限管理
-- 不会与Spring AI自动生成的向量表冲突
-- =====================================================

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(30) NOT NULL UNIQUE,
    nickname VARCHAR(30),
    email VARCHAR(50),
    phonenumber VARCHAR(11),
    sex CHAR(1) DEFAULT '2',
    avatar VARCHAR(255),
    password VARCHAR(100) NOT NULL,
    status CHAR(1) DEFAULT '0',
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    del_flag INT DEFAULT 0
);

-- 聊天历史表（用于保存用户的问答记录）
CREATE TABLE IF NOT EXISTS chat_history (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT,
    sources TEXT,
    question_type VARCHAR(50),
    response_time BIGINT,
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    del_flag INT DEFAULT 0
);

-- 知识库元数据表（用于管理上传的文件信息，不存储向量）
-- 向量数据由Spring AI自动管理在 vector_store 表中
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255),
    file_type VARCHAR(50),
    file_path VARCHAR(500),
    file_size BIGINT,
    category VARCHAR(100),
    tags VARCHAR(500),
    content TEXT,
    summary TEXT,
    vector_status CHAR(1) DEFAULT '0',  -- 0未处理 1处理中 2已完成 3失败
    vector_count INT DEFAULT 0,         -- 向量化后的文档块数量
    user_id BIGINT DEFAULT 0,           -- 0表示全局知识库，其他表示用户私有
    is_public CHAR(1) DEFAULT '0',      -- 0私有 1公开
    status CHAR(1) DEFAULT '0',
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    del_flag INT DEFAULT 0
);

-- 文件上传记录表（可选，用于详细追踪）
CREATE TABLE IF NOT EXISTS file_upload_log (
    id BIGSERIAL PRIMARY KEY,
    knowledge_id BIGINT,                -- 关联 knowledge_base 表
    user_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT,
    upload_status VARCHAR(20),          -- uploading/success/failed
    process_status VARCHAR(20),         -- pending/processing/completed/failed
    error_message TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_knowledge_user_id ON knowledge_base(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_category ON knowledge_base(category);
CREATE INDEX IF NOT EXISTS idx_knowledge_status ON knowledge_base(vector_status);
CREATE INDEX IF NOT EXISTS idx_chat_session_id ON chat_history(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_user_id ON chat_history(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_log_user_id ON file_upload_log(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_log_knowledge_id ON file_upload_log(knowledge_id);

-- 插入默认管理员账号（密码：admin123）
INSERT INTO sys_user (username, nickname, password, role, status)
VALUES ('admin', '管理员', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qj6wFKZy', 'admin', '0')
ON CONFLICT (username) DO NOTHING;

-- 插入测试用户（密码：user123）
INSERT INTO sys_user (username, nickname, password, role, status)
VALUES ('user', '测试用户', '$2a$10$RMuFXGQ5AtH4wOvkUqyvuecpqUSeoxZYqilXzbz50dceRsga.WYiq', 'user', '0')
ON CONFLICT (username) DO NOTHING;

COMMENT ON TABLE sys_user IS '用户信息表';
COMMENT ON TABLE knowledge_base IS '知识库元数据表（文件信息管理，向量数据在vector_store表）';
COMMENT ON TABLE chat_history IS '聊天历史表';
COMMENT ON TABLE file_upload_log IS '文件上传日志表（追踪上传和处理过程）';

-- =====================================================
-- 注意事项：
-- 1. Spring AI会自动创建 vector_store 表来存储向量数据
-- 2. knowledge_base 表只存储文件的元数据信息
-- 3. 两个表通过文档ID关联，不会冲突
-- =====================================================

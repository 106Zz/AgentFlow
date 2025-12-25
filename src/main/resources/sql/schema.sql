-- =====================================================
-- 用户管理模块表结构
-- 说明：这些表用于用户认证、权限管理
-- 不会与Spring AI自动生成的向量表冲突
-- =====================================================

-- 用户表（使用雪花算法生成ID）
CREATE TABLE IF NOT EXISTS sys_user (
                                        user_id BIGINT PRIMARY KEY,
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

-- 聊天历史表（优化版，使用雪花算法生成ID）
CREATE TABLE IF NOT EXISTS chat_history (
                                            id BIGINT PRIMARY KEY,
                                            session_id VARCHAR(64) NOT NULL,           -- 改为64（UUID长度）
    user_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT,
    sources JSONB,                             -- 改为JSONB（更好的查询性能）
    token_count INTEGER DEFAULT 0,             -- 新增：Token数量统计
    question_type VARCHAR(50),
    response_time BIGINT,
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    del_flag INT DEFAULT 0
    );

-- 会话元数据表（新增：用于管理会话列表，使用雪花算法生成ID）
CREATE TABLE IF NOT EXISTS chat_session (
                                            id BIGINT PRIMARY KEY,
                                            session_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),                        -- 会话标题（首个问题的摘要）
    message_count INTEGER DEFAULT 0,           -- 消息数量
    last_message_time TIMESTAMP,               -- 最后一条消息时间
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    del_flag INT DEFAULT 0
    );

-- 知识库元数据表（用于管理上传的文件信息，不存储向量，使用雪花算法生成ID）
-- 向量数据由Spring AI自动管理在 vector_store 表中
CREATE TABLE IF NOT EXISTS knowledge_base (
                                              id BIGINT PRIMARY KEY,
                                              title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255),
    file_type VARCHAR(50),
    file_path VARCHAR(500),
    file_size BIGINT,
    category VARCHAR(100),
    tags VARCHAR(500),
    content TEXT,
    summary TEXT,
    vector_status CHAR(1) DEFAULT '0',         -- 0未处理 1处理中 2已完成 3失败
    vector_count INT DEFAULT 0,                -- 向量化后的文档块数量
    user_id BIGINT DEFAULT 0,                  -- 0表示全局知识库，其他表示用户私有
    is_public CHAR(1) DEFAULT '0',             -- 0私有 1公开
    status CHAR(1) DEFAULT '0',
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remark VARCHAR(500),
    del_flag INT DEFAULT 0
    );

-- 文件上传记录表（可选，用于详细追踪，使用雪花算法生成ID）
CREATE TABLE IF NOT EXISTS file_upload_log (
                                               id BIGINT PRIMARY KEY,
                                               knowledge_id BIGINT,                       -- 关联 knowledge_base 表
                                               user_id BIGINT NOT NULL,
                                               file_name VARCHAR(255) NOT NULL,
    file_size BIGINT,
    upload_status VARCHAR(20),                 -- uploading/success/failed
    process_status VARCHAR(20),                -- pending/processing/completed/failed
    error_message TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- =====================================================
-- 创建索引（优化查询性能）
-- =====================================================

-- 用户表索引
CREATE INDEX IF NOT EXISTS idx_user_username ON sys_user(username);

-- 知识库索引
CREATE INDEX IF NOT EXISTS idx_knowledge_user_id ON knowledge_base(user_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_category ON knowledge_base(category);
CREATE INDEX IF NOT EXISTS idx_knowledge_status ON knowledge_base(vector_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_user_public ON knowledge_base(user_id, is_public);  -- 新增：数据隔离查询

-- 聊天历史索引（重要！）
CREATE INDEX IF NOT EXISTS idx_chat_session_user ON chat_history(session_id, user_id);  -- 联合索引（最重要）
CREATE INDEX IF NOT EXISTS idx_chat_user_time ON chat_history(user_id, create_time DESC);  -- 按时间查询
CREATE INDEX IF NOT EXISTS idx_chat_session_time ON chat_history(session_id, create_time ASC);  -- 会话内按时间排序
CREATE INDEX IF NOT EXISTS idx_chat_session_user_time_asc ON chat_history(session_id, user_id, create_time ASC);
-- 定时清理
CREATE INDEX IF NOT EXISTS idx_chat_create_time ON chat_history(create_time);

-- 会话元数据索引（新增）
CREATE INDEX IF NOT EXISTS idx_session_user_time ON chat_session(user_id, last_message_time DESC);

-- 上传日志索引
CREATE INDEX IF NOT EXISTS idx_upload_log_user_id ON file_upload_log(user_id);
CREATE INDEX IF NOT EXISTS idx_upload_log_knowledge_id ON file_upload_log(knowledge_id);

-- =====================================================
-- 插入默认数据
-- =====================================================

-- 插入默认管理员账号（密码：admin123）
INSERT INTO sys_user (username, nickname, password, role, status)
VALUES ('admin', '管理员', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE/TU.qj6wFKZy', 'admin', '0')
    ON CONFLICT (username) DO NOTHING;

-- 插入测试用户（密码：user123）
INSERT INTO sys_user (username, nickname, password, role, status)
VALUES ('user', '测试用户', '$2a$10$RMuFXGQ5AtH4wOvkUqyvuecpqUSeoxZYqilXzbz50dceRsga.WYiq', 'user', '0')
    ON CONFLICT (username) DO NOTHING;

-- =====================================================
-- 表注释
-- =====================================================

COMMENT ON TABLE sys_user IS '用户信息表';
COMMENT ON TABLE chat_history IS '聊天历史表（完整对话记录，永久保存）';
COMMENT ON TABLE chat_session IS '会话元数据表（用于会话列表展示）';
COMMENT ON TABLE knowledge_base IS '知识库元数据表（文件信息管理，向量数据在vector_store表）';
COMMENT ON TABLE file_upload_log IS '文件上传日志表（追踪上传和处理过程）';

-- =====================================================
-- 列注释（重要字段说明）
-- =====================================================

COMMENT ON COLUMN chat_history.session_id IS '会话ID（UUID格式，用于关联同一对话）';
COMMENT ON COLUMN chat_history.sources IS '引用来源（JSONB格式，存储文档名和页码）';
COMMENT ON COLUMN chat_history.token_count IS 'Token消耗数量（用于成本统计）';

COMMENT ON COLUMN chat_session.title IS '会话标题（通常是首个问题的摘要）';
COMMENT ON COLUMN chat_session.message_count IS '消息数量（用于统计）';
COMMENT ON COLUMN chat_session.last_message_time IS '最后一条消息时间（用于排序）';

COMMENT ON COLUMN knowledge_base.user_id IS '用户ID（0=全局知识库，其他=用户私有）';
COMMENT ON COLUMN knowledge_base.is_public IS '是否公开（0=私有，1=公开）';
COMMENT ON COLUMN knowledge_base.vector_status IS '向量化状态（0=未处理，1=处理中，2=已完成，3=失败）';
COMMENT ON COLUMN knowledge_base.vector_count IS '向量块数量（文档切分后的块数）';

-- =====================================================
-- 注意事项：
-- 1. 所有表使用雪花算法生成ID（分布式友好）
-- 2. MyBatis-Plus 配置 @TableId(type = IdType.ASSIGN_ID)
-- 3. Spring AI会自动创建 vector_store 表来存储向量数据
-- 4. knowledge_base 表只存储文件的元数据信息
-- 5. chat_history 存储完整对话历史（PostgreSQL）
-- 6. 对话记忆由 Spring AI 管理（Redis，最近N轮）
-- 7. sessionId 使用 UUID 生成（全局唯一）
-- =====================================================

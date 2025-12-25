# 电力知识库管理系统

基于Spring Boot 3 + Spring AI + PostgreSQL + PGVector的智能电力知识库RAG问答系统

## 项目简介

本系统是一个专为电力行业设计的智能知识库管理与问答系统，支持PDF、Excel、Word、图片等多种文件格式的上传、解析、向量化存储，并基于RAG（检索增强生成）技术提供智能问答服务。

## 核心功能模块

### 1. 用户管理与权限控制模块
- ✅ 支持管理员（admin）和普通用户（user）两种角色
- ✅ 用户注册、登录功能
- ✅ 基于JWT的Token认证
- ✅ 基于Spring Security的权限控制
- ✅ 用户信息管理（增删改查）

### 2. 电力知识库管理模块
- ✅ 支持上传PDF、Excel、Word、图片等文件
- ✅ 自动文本提取和表格解析（基于Tika和PDFBox）
- ✅ 文档分块处理和向量化嵌入
- ✅ 知识分类、标签管理
- ✅ 知识条目的增删改查操作
- ✅ 管理员权限控制

### 3. 知识扩展与隔离模块
- ✅ 全局知识库（管理员上传，所有用户可见）
- ✅ 用户私有知识库（普通用户上传，仅自己可见）
- ✅ 基于userId的数据隔离
- ✅ 公开/私有知识设置

### 4. 智能检索与RAG问答模块
- ✅ 聊天式问答界面
- ✅ 基于PGVector的向量检索
- ✅ 结合大语言模型生成准确回答
- ✅ 答案来源引用和相似度评分
- ✅ 多轮对话支持
- ✅ 会话历史管理

### 5. 知识展示与辅助功能
- ✅ 知识列表分页浏览
- ✅ 关键词/标签搜索
- ✅ 答案来源高亮显示
- ✅ 对话记录保存
- ✅ 聊天历史查询和清空

## 技术栈

### 后端
- **框架**: Spring Boot 3.4.12
- **安全**: Spring Security + JWT
- **数据库**: PostgreSQL + PGVector
- **ORM**: MyBatis-Plus 3.5.5
- **AI**: Spring AI + 阿里云DashScope
- **文档解析**: Apache Tika 2.9.1 + PDFBox 3.0.3
- **工具**: Hutool、Lombok
- **API文档**: Knife4j (Swagger)

### 前端
- **框架**: Vue 3.4+ (Composition API)
- **语言**: TypeScript 5.0+
- **构建工具**: Vite 5.0+
- **UI组件库**: Element Plus 2.5+
- **状态管理**: Pinia 2.1+
- **路由**: Vue Router 4.2+
- **HTTP客户端**: Axios 1.6+
- **工具库**: dayjs、marked、highlight.js

## 项目结构

```
com.agenthub
├── common                          # 通用模块
│   ├── base                        # 基础类
│   │   ├── BaseEntity.java         # 基础实体（参考若依）
│   │   └── BaseController.java    # 基础控制器
│   ├── constant                    # 常量定义
│   │   └── Constants.java
│   ├── core                        # 核心类
│   │   ├── domain
│   │   │   └── AjaxResult.java    # 统一响应结果
│   │   └── page
│   │       ├── PageQuery.java     # 分页查询参数
│   │       └── PageResult.java    # 分页响应结果
│   ├── enums                       # 枚举类
│   │   └── UserRole.java          # 用户角色枚举
│   ├── exception                   # 异常处理
│   │   ├── ServiceException.java  # 业务异常
│   │   └── GlobalExceptionHandler.java  # 全局异常处理器
│   └── utils                       # 工具类
│       └── SecurityUtils.java     # 安全工具类
│
├── framework                       # 框架配置
│   ├── config                      # 配置类
│   │   ├── CorsConfig.java        # 跨域配置
│   │   ├── MyBatisPlusConfig.java # MyBatis-Plus配置
│   │   └── MyMetaObjectHandler.java # 自动填充配置
│   └── security                    # 安全模块
│       ├── config
│       │   └── SecurityConfig.java # Spring Security配置
│       ├── filter
│       │   └── JwtAuthenticationTokenFilter.java # JWT过滤器
│       ├── handle
│       │   ├── AuthenticationEntryPointImpl.java # 认证失败处理
│       │   └── LogoutSuccessHandlerImpl.java     # 退出成功处理
│       └── service
│           └── TokenService.java   # Token服务
│
├── system                          # 系统管理模块
│   ├── controller
│   │   ├── AuthController.java    # 认证控制器（登录/注册）
│   │   └── SysUserController.java # 用户管理控制器
│   ├── domain
│   │   ├── SysUser.java           # 用户实体
│   │   └── model
│   │       ├── LoginUser.java     # 登录用户信息
│   │       └── LoginBody.java     # 登录请求体
│   ├── mapper
│   │   └── SysUserMapper.java     # 用户Mapper
│   └── service
│       ├── ISysUserService.java
│       └── impl
│           ├── SysUserServiceImpl.java
│           └── UserDetailsServiceImpl.java # Spring Security用户服务
│
└── knowledge                       # 知识库模块
    ├── controller
    │   ├── KnowledgeBaseController.java # 知识库管理控制器
    │   └── ChatController.java          # 智能问答控制器
    ├── domain
    │   ├── KnowledgeBase.java      # 知识库实体
    │   ├── ChatHistory.java        # 聊天历史实体
    │   └── vo
    │       ├── ChatRequest.java    # 聊天请求VO
    │       └── ChatResponse.java   # 聊天响应VO
    ├── mapper
    │   ├── KnowledgeBaseMapper.java
    │   └── ChatHistoryMapper.java
    └── service
        ├── IKnowledgeBaseService.java # 知识库服务接口
        └── IChatService.java          # 聊天服务接口
```

## 快速开始

### 1. 环境要求
- JDK 21+
- PostgreSQL 14+ (需安装pgvector扩展)
- Maven 3.8+

### 2. 数据库配置

```bash
# 安装PostgreSQL和pgvector扩展
# 创建数据库
createdb agenthub

# 连接数据库并启用pgvector扩展
psql -d agenthub
CREATE EXTENSION vector;

# 执行初始化脚本
psql -d agenthub -f src/main/resources/sql/schema.sql
```

### 3. 配置文件

修改 `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agenthub
    username: your_username
    password: your_password
  
  ai:
    dashscope:
      api-key: your_dashscope_api_key
```

### 4. 启动项目

```bash
mvn clean install
mvn spring-boot:run
```

### 5. 访问系统

- 后端API: http://localhost:8080
- Swagger文档: http://localhost:8080/doc.html

### 6. 默认账号

**管理员账号**:
- 用户名: admin
- 密码: admin123

**测试用户**:
- 用户名: user
- 密码: user123

## API接口说明

### 认证接口

#### 用户登录
```
POST /auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

#### 用户注册
```
POST /auth/register
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123",
  "nickname": "新用户",
  "email": "user@example.com"
}
```

### 知识库接口

#### 获取OSS上传凭证（前端直传）
```
GET /knowledge/base/upload/policy?filename=test.pdf
Authorization: Bearer {token}

Response:
{
  "code": 200,
  "data": {
    "accessKeyId": "LTAI5t...",
    "policy": "eyJleHBpcmF0aW9uIjoi...",
    "signature": "xMj8z...",
    "host": "https://your-bucket.oss-cn-guangzhou.aliyuncs.com",
    "key": "knowledge/user/123/2025/12/25/abc123_test.pdf"
  }
}
```

#### 上传回调（创建知识库记录）
```
POST /knowledge/base/upload/callback
Authorization: Bearer {token}
Content-Type: application/json

{
  "fileName": "test.pdf",
  "filePath": "knowledge/user/123/2025/12/25/abc123.pdf",
  "fileSize": 1024000,
  "title": "测试文档",
  "category": "技术文档",
  "tags": "测试,PDF",
  "isPublic": "0"
}
```

#### 查询知识库列表
```
GET /knowledge/base/list?pageNum=1&pageSize=10&title=xxx
Authorization: Bearer {token}
```

#### 删除知识库
```
DELETE /knowledge/base/{ids}
Authorization: Bearer {token}
```

### 智能问答接口

#### 普通问答
```
POST /knowledge/chat/ask
Authorization: Bearer {token}
Content-Type: application/json

{
  "sessionId": "session-uuid",
  "question": "广东省2026年电力市场交易政策是什么？",
  "useRag": true,
  "topK": 5,
  "similarityThreshold": 0.7
}
```

#### 流式问答（SSE）
```
POST /knowledge/chat/stream
Authorization: Bearer {token}
Content-Type: application/json

{
  "sessionId": "session-uuid",
  "question": "广东省2026年电力市场交易政策是什么？"
}

Response: text/event-stream (逐字返回答案)
```

#### 获取会话列表
```
GET /knowledge/chat/sessions
Authorization: Bearer {token}
```

#### 获取聊天历史
```
GET /knowledge/chat/history/{sessionId}
Authorization: Bearer {token}
```

#### 清空聊天历史
```
DELETE /knowledge/chat/history/{sessionId}
Authorization: Bearer {token}
```

## 核心特性

### 1. 参考若依框架设计
- BaseEntity: 统一的实体基类，包含创建时间、更新时间、创建人等字段
- BaseController: 统一的控制器基类，提供通用响应方法
- AjaxResult: 统一的响应结果封装
- 全局异常处理器
- MyBatis-Plus自动填充配置

### 2. Spring Security + JWT
- 基于JWT的无状态认证
- 角色权限控制（ROLE_admin、ROLE_user）
- 方法级权限注解（@PreAuthorize）
- 自定义认证失败处理

### 3. 跨域配置
- 支持所有域名跨域访问（已配置CorsConfig）
- 允许携带Cookie和Authorization头
- 支持所有HTTP方法（GET/POST/PUT/DELETE）
- 前端开发环境可使用Vite代理

### 4. 数据隔离
- 管理员可查看所有知识库
- 普通用户只能查看公开知识库和自己上传的知识库
- 基于userId的数据过滤

### 5. RAG问答
- 向量检索相关知识片段（基于PGVector）
- 结合大语言模型生成回答（阿里云DashScope）
- 返回知识来源和相似度评分
- 支持多轮对话和会话管理
- 支持流式响应（SSE）和普通响应

### 6. OSS直传
- 前端直接上传文件到阿里云OSS
- 不占用服务器带宽
- 支持上传进度显示
- 后端只负责生成临时凭证和创建记录

## 前端开发

前端应用基于Vue 3 + TypeScript + Element Plus构建，提供现代化的用户界面和流畅的交互体验。

### 前端功能特性

**已规划功能**：
- ✅ 用户认证（登录/注册/JWT Token管理）
- ✅ 知识库管理（列表/上传/删除/搜索）
- ✅ OSS直传（前端直接上传到阿里云OSS）
- ✅ 智能问答（流式响应/多轮对话）
- ✅ 会话管理（创建/切换/删除会话）
- ✅ 响应式布局（桌面/移动端适配）
- ✅ 权限控制（管理员/普通用户）

### 前端开发文档

详细的前端开发规范和任务列表请查看：
- **需求文档**: `.kiro/specs/frontend-development/requirements.md`
- **设计文档**: `.kiro/specs/frontend-development/design.md`
- **任务列表**: `.kiro/specs/frontend-development/tasks.md`

### 前端快速开始

```bash
# 进入前端目录（待创建）
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build
```

### 前端环境配置

创建 `.env.development` 文件：
```env
VITE_API_BASE_URL=http://localhost:8080
VITE_APP_TITLE=电力知识库管理系统
```

### 前端核心功能

1. **用户认证**
   - JWT Token自动管理
   - 路由守卫保护
   - Token过期自动跳转

2. **文件上传**
   - OSS前端直传（不占用服务器带宽）
   - 上传进度实时显示
   - 支持批量上传
   - 文件类型和大小验证

3. **智能问答**
   - 流式响应（逐字显示答案）
   - 多轮对话支持
   - 知识来源引用
   - Markdown渲染

4. **权限管理**
   - 管理员可查看所有知识库
   - 普通用户只能查看公开知识库和自己的私有知识库
   - 删除权限自动校验

## 项目文档

- **数据隔离方案**: `docs/DATA_ISOLATION.md`
- **OSS直传方案**: `docs/OSS_DIRECT_UPLOAD.md`
- **前端开发规范**: `.kiro/specs/frontend-development/`

## 开发规范

1. 所有实体类继承BaseEntity
2. 所有控制器继承BaseController
3. 使用统一的AjaxResult返回结果
4. 使用@PreAuthorize进行权限控制
5. 使用Lombok简化代码
6. 遵循RESTful API设计规范

## 许可证

MIT License

## 联系方式

如有问题，请提交Issue或联系开发团队。

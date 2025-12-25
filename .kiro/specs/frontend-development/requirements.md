# 前端开发需求文档

## 简介

本文档定义了电力知识库管理系统前端应用的需求。该系统是一个基于Vue 3的单页应用（SPA），提供用户认证、知识库管理和智能问答功能。前端通过RESTful API与后端Spring Boot服务通信，支持文件直传OSS，并提供流式问答体验。

## 术语表

- **Frontend Application**: 基于Vue 3 + Element Plus构建的单页应用
- **Backend API**: Spring Boot提供的RESTful API服务，运行在http://localhost:8080
- **OSS**: 阿里云对象存储服务，用于存储文件
- **JWT Token**: JSON Web Token，用于用户认证的令牌
- **RAG**: 检索增强生成，智能问答的核心技术
- **Session**: 聊天会话，用于管理多轮对话
- **SSE**: Server-Sent Events，服务器推送事件，用于流式响应
- **Direct Upload**: 前端直传，文件直接从浏览器上传到OSS，不经过后端服务器
- **Admin User**: 管理员用户，拥有所有权限
- **Regular User**: 普通用户，只能访问公开知识库和自己的私有知识库

## 需求

### 需求 1：用户认证与授权

**用户故事**：作为用户，我希望能够注册、登录系统，以便访问知识库和问答功能。

#### 验收标准

1. WHEN 用户访问需要认证的页面 THEN Frontend Application SHALL 重定向到登录页面
2. WHEN 用户提交有效的用户名和密码 THEN Frontend Application SHALL 调用Backend API的登录接口并存储返回的JWT Token
3. WHEN 用户登录成功 THEN Frontend Application SHALL 在所有后续API请求的Authorization头中携带JWT Token
4. WHEN JWT Token过期或无效 THEN Frontend Application SHALL 清除本地存储的Token并重定向到登录页面
5. WHEN 用户点击注册按钮 THEN Frontend Application SHALL 显示注册表单并验证输入字段
6. WHEN 用户提交注册表单 THEN Frontend Application SHALL 调用Backend API的注册接口并显示结果消息

### 需求 2：知识库文件上传

**用户故事**：作为用户，我希望能够上传文档到知识库，以便系统能够基于这些文档回答问题。

#### 验收标准

1. WHEN 用户选择文件进行上传 THEN Frontend Application SHALL 验证文件类型为PDF、Word、Excel或图片格式
2. WHEN 用户选择的文件大小超过100MB THEN Frontend Application SHALL 阻止上传并显示错误提示
3. WHEN 用户开始上传文件 THEN Frontend Application SHALL 先调用Backend API获取OSS上传凭证
4. WHEN Frontend Application获得OSS凭证 THEN Frontend Application SHALL 使用FormData直接上传文件到OSS服务器
5. WHEN 文件上传到OSS成功 THEN Frontend Application SHALL 调用Backend API的回调接口创建知识库记录
6. WHEN 文件上传过程中 THEN Frontend Application SHALL 显示上传进度百分比
7. WHEN 用户选择多个文件 THEN Frontend Application SHALL 支持批量上传并显示每个文件的上传状态
8. WHEN Admin User上传文件 THEN Frontend Application SHALL 提供选项设置文件为公开或私有
9. WHEN Regular User上传文件 THEN Frontend Application SHALL 自动将文件标记为私有

### 需求 3：知识库列表展示

**用户故事**：作为用户，我希望能够浏览知识库中的文档列表，以便了解系统中有哪些知识。

#### 验收标准

1. WHEN 用户访问知识库列表页面 THEN Frontend Application SHALL 调用Backend API获取知识库列表数据
2. WHEN Admin User访问列表 THEN Frontend Application SHALL 显示所有知识库记录
3. WHEN Regular User访问列表 THEN Frontend Application SHALL 仅显示公开知识库和用户自己的私有知识库
4. WHEN 知识库列表数据超过10条 THEN Frontend Application SHALL 提供分页控件
5. WHEN 用户点击分页控件 THEN Frontend Application SHALL 请求对应页码的数据
6. WHEN 知识库记录的vectorStatus为0或1 THEN Frontend Application SHALL 显示"处理中"状态
7. WHEN 知识库记录的vectorStatus为2 THEN Frontend Application SHALL 显示"已完成"状态
8. WHEN 知识库记录的vectorStatus为3 THEN Frontend Application SHALL 显示"失败"状态
9. WHEN 用户在列表中输入搜索关键词 THEN Frontend Application SHALL 过滤显示匹配的知识库记录

### 需求 4：知识库管理操作

**用户故事**：作为用户，我希望能够查看、删除知识库记录，以便管理我的文档。

#### 验收标准

1. WHEN 用户点击知识库记录 THEN Frontend Application SHALL 显示该记录的详细信息
2. WHEN 用户点击删除按钮 THEN Frontend Application SHALL 显示确认对话框
3. WHEN 用户确认删除 THEN Frontend Application SHALL 调用Backend API删除接口
4. WHEN Admin User删除知识库 THEN Frontend Application SHALL 允许删除任何记录
5. WHEN Regular User删除知识库 THEN Frontend Application SHALL 仅允许删除自己上传的记录
6. WHEN 删除操作成功 THEN Frontend Application SHALL 刷新知识库列表并显示成功消息
7. WHEN 用户选择多条记录 THEN Frontend Application SHALL 支持批量删除操作

### 需求 5：智能问答界面

**用户故事**：作为用户，我希望能够向系统提问并获得基于知识库的答案，以便快速获取信息。

#### 验收标准

1. WHEN 用户访问问答页面 THEN Frontend Application SHALL 显示聊天界面和输入框
2. WHEN 用户输入问题并提交 THEN Frontend Application SHALL 调用Backend API的流式问答接口
3. WHEN Backend API返回流式响应 THEN Frontend Application SHALL 逐字显示答案内容
4. WHEN 流式响应完成 THEN Frontend Application SHALL 在聊天历史中保存完整的问答记录
5. WHEN 用户在同一会话中提问 THEN Frontend Application SHALL 使用相同的sessionId维持上下文
6. WHEN 用户创建新会话 THEN Frontend Application SHALL 生成新的sessionId
7. WHEN 用户切换会话 THEN Frontend Application SHALL 加载对应会话的聊天历史
8. WHEN 答案中包含来源引用 THEN Frontend Application SHALL 高亮显示引用的文档名称

### 需求 6：会话管理

**用户故事**：作为用户，我希望能够管理我的聊天会话，以便组织不同主题的对话。

#### 验收标准

1. WHEN 用户访问问答页面 THEN Frontend Application SHALL 在侧边栏显示用户的所有会话列表
2. WHEN 用户点击会话 THEN Frontend Application SHALL 加载该会话的聊天历史
3. WHEN 用户点击新建会话按钮 THEN Frontend Application SHALL 创建新会话并切换到该会话
4. WHEN 用户点击删除会话按钮 THEN Frontend Application SHALL 显示确认对话框
5. WHEN 用户确认删除会话 THEN Frontend Application SHALL 调用Backend API清空该会话的聊天历史
6. WHEN 会话列表为空 THEN Frontend Application SHALL 自动创建默认会话
7. WHEN 会话包含聊天记录 THEN Frontend Application SHALL 显示最后一条消息的预览

### 需求 7：响应式布局与用户体验

**用户故事**：作为用户，我希望界面美观易用且支持不同设备，以便在各种场景下使用系统。

#### 验收标准

1. WHEN 用户在桌面浏览器访问 THEN Frontend Application SHALL 显示完整的侧边栏和主内容区域
2. WHEN 用户在移动设备访问 THEN Frontend Application SHALL 隐藏侧边栏并提供菜单按钮
3. WHEN 用户点击移动端菜单按钮 THEN Frontend Application SHALL 显示可折叠的侧边栏
4. WHEN API请求进行中 THEN Frontend Application SHALL 显示加载指示器
5. WHEN API请求失败 THEN Frontend Application SHALL 显示友好的错误消息
6. WHEN 用户执行操作成功 THEN Frontend Application SHALL 显示成功提示消息
7. WHEN 用户长时间无操作 THEN Frontend Application SHALL 保持会话状态直到Token过期

### 需求 8：跨域请求处理

**用户故事**：作为开发者，我希望前端能够正确处理跨域请求，以便与后端API和OSS服务通信。

#### 验收标准

1. WHEN Frontend Application发送API请求到Backend API THEN Frontend Application SHALL 在请求头中包含正确的Origin
2. WHEN Backend API返回CORS响应头 THEN Frontend Application SHALL 正确处理响应
3. WHEN Frontend Application上传文件到OSS THEN Frontend Application SHALL 使用OSS返回的host地址
4. WHEN OSS返回CORS错误 THEN Frontend Application SHALL 显示明确的错误提示
5. WHEN 开发环境运行 THEN Frontend Application SHALL 配置代理转发API请求到http://localhost:8080

### 需求 9：错误处理与用户反馈

**用户故事**：作为用户，我希望系统能够清晰地告知我操作结果和错误信息，以便我了解系统状态。

#### 验收标准

1. WHEN API返回401未授权错误 THEN Frontend Application SHALL 清除Token并重定向到登录页面
2. WHEN API返回403权限不足错误 THEN Frontend Application SHALL 显示"权限不足"消息
3. WHEN API返回404资源不存在错误 THEN Frontend Application SHALL 显示"资源不存在"消息
4. WHEN API返回500服务器错误 THEN Frontend Application SHALL 显示"服务器错误，请稍后重试"消息
5. WHEN 网络请求超时 THEN Frontend Application SHALL 显示"网络超时，请检查连接"消息
6. WHEN 文件上传失败 THEN Frontend Application SHALL 显示具体的失败原因
7. WHEN 表单验证失败 THEN Frontend Application SHALL 在对应字段下方显示错误提示

### 需求 10：状态管理与数据持久化

**用户故事**：作为用户，我希望系统能够记住我的登录状态和偏好设置，以便下次访问时无需重新配置。

#### 验收标准

1. WHEN 用户登录成功 THEN Frontend Application SHALL 将JWT Token存储到localStorage
2. WHEN 用户刷新页面 THEN Frontend Application SHALL 从localStorage读取Token并验证有效性
3. WHEN 用户退出登录 THEN Frontend Application SHALL 清除localStorage中的所有用户数据
4. WHEN 用户切换会话 THEN Frontend Application SHALL 将当前sessionId存储到sessionStorage
5. WHEN 用户关闭浏览器标签页 THEN Frontend Application SHALL 保留localStorage中的Token
6. WHEN 用户的Token即将过期 THEN Frontend Application SHALL 提示用户重新登录

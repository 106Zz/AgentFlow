# 前端应用设计文档

## 概述

本文档描述了电力知识库管理系统前端应用的技术设计。该应用基于Vue 3 + TypeScript + Element Plus构建，采用组合式API（Composition API）和Pinia状态管理，通过Axios与后端RESTful API通信。应用支持响应式布局、文件直传OSS、流式问答等现代Web应用特性。

## 架构

### 技术栈

**核心框架**：
- Vue 3.4+ (Composition API)
- TypeScript 5.0+
- Vite 5.0+ (构建工具)

**UI组件库**：
- Element Plus 2.5+ (UI组件)
- @element-plus/icons-vue (图标)

**状态管理**：
- Pinia 2.1+ (状态管理)
- pinia-plugin-persistedstate (状态持久化)

**路由**：
- Vue Router 4.2+

**HTTP客户端**：
- Axios 1.6+

**工具库**：
- dayjs (日期处理)
- marked (Markdown渲染)
- highlight.js (代码高亮)

### 项目结构

```
frontend/
├── public/                      # 静态资源
│   └── favicon.ico
├── src/
│   ├── api/                     # API接口定义
│   │   ├── auth.ts              # 认证相关API
│   │   ├── knowledge.ts         # 知识库API
│   │   ├── chat.ts              # 问答API
│   │   └── types.ts             # API类型定义
│   │
│   ├── assets/                  # 资源文件
│   │   ├── styles/              # 样式文件
│   │   │   ├── index.scss       # 全局样式
│   │   │   ├── variables.scss   # 样式变量
│   │   │   └── element.scss     # Element Plus主题定制
│   │   └── images/              # 图片资源
│   │
│   ├── components/              # 通用组件
│   │   ├── Layout/              # 布局组件
│   │   │   ├── AppHeader.vue    # 顶部导航
│   │   │   ├── AppSidebar.vue   # 侧边栏
│   │   │   └── AppLayout.vue    # 主布局
│   │   ├── Upload/              # 上传组件
│   │   │   └── OssUpload.vue    # OSS直传组件
│   │   └── Chat/                # 聊天组件
│   │       ├── MessageList.vue  # 消息列表
│   │       ├── MessageItem.vue  # 消息项
│   │       └── InputBox.vue     # 输入框
│   │
│   ├── composables/             # 组合式函数
│   │   ├── useAuth.ts           # 认证逻辑
│   │   ├── useUpload.ts         # 上传逻辑
│   │   ├── useChat.ts           # 聊天逻辑
│   │   └── useStream.ts         # 流式响应处理
│   │
│   ├── router/                  # 路由配置
│   │   ├── index.ts             # 路由主文件
│   │   └── guards.ts            # 路由守卫
│   │
│   ├── stores/                  # Pinia状态管理
│   │   ├── auth.ts              # 认证状态
│   │   ├── knowledge.ts         # 知识库状态
│   │   └── chat.ts              # 聊天状态
│   │
│   ├── types/                   # TypeScript类型定义
│   │   ├── auth.ts              # 认证类型
│   │   ├── knowledge.ts         # 知识库类型
│   │   └── chat.ts              # 聊天类型
│   │
│   ├── utils/                   # 工具函数
│   │   ├── request.ts           # Axios封装
│   │   ├── storage.ts           # 本地存储封装
│   │   ├── validate.ts          # 表单验证
│   │   └── format.ts            # 格式化工具
│   │
│   ├── views/                   # 页面组件
│   │   ├── auth/                # 认证页面
│   │   │   ├── Login.vue        # 登录页
│   │   │   └── Register.vue     # 注册页
│   │   ├── knowledge/           # 知识库页面
│   │   │   ├── List.vue         # 知识库列表
│   │   │   ├── Upload.vue       # 文件上传
│   │   │   └── Detail.vue       # 知识详情
│   │   ├── chat/                # 问答页面
│   │   │   └── Index.vue        # 问答主页
│   │   └── Home.vue             # 首页
│   │
│   ├── App.vue                  # 根组件
│   └── main.ts                  # 入口文件
│
├── .env.development             # 开发环境变量
├── .env.production              # 生产环境变量
├── index.html                   # HTML模板
├── package.json                 # 依赖配置
├── tsconfig.json                # TypeScript配置
└── vite.config.ts               # Vite配置
```

## 组件和接口

### 核心组件

#### 1. AppLayout (主布局)

**职责**：提供应用的整体布局结构

**Props**：无

**Emits**：无

**插槽**：
- default: 主内容区域

**状态**：
- `collapsed`: 侧边栏折叠状态

#### 2. OssUpload (OSS直传组件)

**职责**：处理文件直传到OSS的完整流程

**Props**：
- `accept`: 接受的文件类型
- `multiple`: 是否支持多选
- `maxSize`: 最大文件大小(MB)
- `autoUpload`: 是否自动上传

**Emits**：
- `success`: 上传成功
- `error`: 上传失败
- `progress`: 上传进度

**方法**：
- `uploadFile(file: File)`: 上传单个文件
- `uploadFiles(files: File[])`: 批量上传

#### 3. MessageList (消息列表)

**职责**：展示聊天消息列表

**Props**：
- `messages`: 消息数组
- `loading`: 加载状态

**Emits**：无

**方法**：
- `scrollToBottom()`: 滚动到底部

#### 4. InputBox (输入框)

**职责**：处理用户输入和消息发送

**Props**：
- `disabled`: 是否禁用
- `placeholder`: 占位文本

**Emits**：
- `send`: 发送消息

**方法**：
- `focus()`: 聚焦输入框
- `clear()`: 清空输入

### API接口定义

#### 认证API (api/auth.ts)

```typescript
// 登录
POST /auth/login
Request: { username: string, password: string }
Response: { code: 200, data: { token: string, userId: number, username: string, roles: string[] } }

// 注册
POST /auth/register
Request: { username: string, password: string, nickname?: string, email?: string }
Response: { code: 200, msg: "注册成功" }
```

#### 知识库API (api/knowledge.ts)

```typescript
// 获取知识库列表
GET /knowledge/base/list?pageNum=1&pageSize=10&title=xxx
Response: { code: 200, data: { total: number, rows: KnowledgeBase[] } }

// 获取OSS上传凭证
GET /knowledge/base/upload/policy?filename=test.pdf
Response: { code: 200, data: OssPolicy }

// 上传回调
POST /knowledge/base/upload/callback
Request: { fileName: string, filePath: string, fileSize: number, title: string, category?: string, tags?: string, isPublic: string }
Response: { code: 200, data: KnowledgeBase }

// 批量上传回调
POST /knowledge/base/upload/batch-callback
Request: KnowledgeBase[]
Response: { code: 200, data: KnowledgeBase[] }

// 获取知识详情
GET /knowledge/base/{id}
Response: { code: 200, data: KnowledgeBase }

// 删除知识库
DELETE /knowledge/base/{ids}
Response: { code: 200, msg: "删除成功" }
```

#### 问答API (api/chat.ts)

```typescript
// 普通问答
POST /knowledge/chat/ask
Request: { sessionId?: string, question: string, useRag?: boolean, topK?: number, similarityThreshold?: number }
Response: { code: 200, data: ChatResponse }

// 流式问答
POST /knowledge/chat/stream (SSE)
Request: { sessionId?: string, question: string }
Response: text/event-stream

// 获取聊天历史
GET /knowledge/chat/history/{sessionId}
Response: { code: 200, data: ChatHistory[] }

// 获取会话列表
GET /knowledge/chat/sessions
Response: { code: 200, data: ChatSession[] }

// 清空聊天历史
DELETE /knowledge/chat/history/{sessionId}
Response: { code: 200, msg: "聊天历史已清空" }
```

## 数据模型

### TypeScript类型定义

```typescript
// 用户信息
interface User {
  userId: number;
  username: string;
  nickname?: string;
  email?: string;
  roles: string[];
}

// 登录响应
interface LoginResponse {
  token: string;
  userId: number;
  username: string;
  roles: string[];
}

// 知识库
interface KnowledgeBase {
  id: number;
  title: string;
  fileName: string;
  filePath: string;
  fileType: string;
  fileSize: number;
  userId: number;
  isPublic: string;  // '0'=私有, '1'=公开
  vectorStatus: string;  // '0'=未处理, '1'=处理中, '2'=已完成, '3'=失败
  category?: string;
  tags?: string;
  createTime: string;
  updateTime: string;
}

// OSS上传凭证
interface OssPolicy {
  accessKeyId: string;
  policy: string;
  signature: string;
  dir: string;
  host: string;
  expire: string;
  key: string;
}

// 聊天请求
interface ChatRequest {
  sessionId?: string;
  question: string;
  useRag?: boolean;
  topK?: number;
  similarityThreshold?: number;
}

// 聊天响应
interface ChatResponse {
  sessionId: string;
  answer: string;
  sources: KnowledgeSource[];
  responseTime: number;
}

// 知识来源
interface KnowledgeSource {
  knowledgeId: number;
  title: string;
  content: string;
  score: number;
  fileType: string;
}

// 聊天消息
interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  sources?: KnowledgeSource[];
  timestamp: number;
}

// 聊天会话
interface ChatSession {
  sessionId: string;
  title: string;
  lastMessage?: string;
  createTime: string;
  updateTime: string;
}

// 分页查询参数
interface PageQuery {
  pageNum: number;
  pageSize: number;
}

// 分页响应
interface PageResult<T> {
  total: number;
  rows: T[];
}

// 统一响应
interface ApiResponse<T = any> {
  code: number;
  msg: string;
  data?: T;
  timestamp: number;
}
```

## 正确性属性

*属性是一个特征或行为，应该在系统的所有有效执行中保持为真——本质上是关于系统应该做什么的正式声明。属性作为人类可读规范和机器可验证正确性保证之间的桥梁。*

### 属性 1：认证状态一致性

*对于任何*需要认证的操作，如果用户已登录，则所有API请求头中应包含有效的JWT Token；如果Token无效或过期，应清除本地存储并重定向到登录页

**验证：需求 1.1, 1.2, 1.3, 1.4**

### 属性 2：文件上传三步流程

*对于任何*文件上传操作，必须严格按顺序完成三个步骤：(1)获取OSS凭证 (2)上传到OSS (3)回调后端创建记录，任何步骤失败都应停止后续步骤并通知用户

**验证：需求 2.3, 2.4, 2.5**

### 属性 3：文件类型白名单验证

*对于任何*用户选择的文件，只有类型在白名单中（PDF、Word、Excel、图片）的文件才能通过验证并开始上传

**验证：需求 2.1**

### 属性 4：上传进度单调性

*对于任何*正在上传的文件，其上传进度百分比应单调递增（从0到100），不应出现回退

**验证：需求 2.6**

### 属性 5：用户权限数据过滤

*对于任何*知识库列表请求，管理员应看到所有记录，普通用户应只看到(userId=0且isPublic='1')或(userId=当前用户ID)的记录

**验证：需求 3.2, 3.3**

### 属性 6：向量状态映射一致性

*对于任何*知识库记录，其vectorStatus字段应映射到对应的显示文本：0或1→"处理中"，2→"已完成"，3→"失败"

**验证：需求 3.6, 3.7, 3.8**

### 属性 7：删除权限校验

*对于任何*删除操作，普通用户只能删除userId等于自己的记录，管理员可以删除任何记录

**验证：需求 4.4, 4.5**

### 属性 8：流式响应完整性

*对于任何*流式问答响应，接收到的所有文本片段应按顺序拼接，流式完成后应保存完整的问答记录到聊天历史

**验证：需求 5.3, 5.4**

### 属性 9：会话上下文隔离

*对于任何*会话，其sessionId应在整个会话生命周期内保持不变，不同会话的sessionId应不同，切换会话应加载对应的聊天历史

**验证：需求 5.5, 5.6, 5.7**

### 属性 10：HTTP错误码统一处理

*对于任何*API错误响应，应根据HTTP状态码执行对应操作：401→清除Token并重定向登录，403→显示"权限不足"，404→显示"资源不存在"，500→显示"服务器错误"，超时→显示"网络超时"

**验证：需求 9.1, 9.2, 9.3, 9.4, 9.5**

### 属性 11：Token持久化往返

*对于任何*成功登录操作，Token应存储到localStorage；对于任何页面刷新，应从localStorage读取Token并恢复登录状态；对于任何退出操作，应清除localStorage中的所有用户数据

**验证：需求 10.1, 10.2, 10.3**

### 属性 12：搜索过滤一致性

*对于任何*搜索关键词，知识库列表应只显示标题、文件名、分类或标签中包含该关键词的记录

**验证：需求 3.9**

### 属性 13：批量操作原子性

*对于任何*批量上传或批量删除操作，每个文件或记录的操作应独立进行，单个失败不应影响其他项的处理，应显示每个项的独立状态

**验证：需求 2.7, 4.7**

## 错误处理

### HTTP错误处理

```typescript
// Axios拦截器统一处理
axios.interceptors.response.use(
  response => response,
  error => {
    const status = error.response?.status;
    const message = error.response?.data?.msg || '请求失败';
    
    switch (status) {
      case 401:
        // 清除Token，重定向到登录页
        authStore.logout();
        router.push('/login');
        ElMessage.error('登录已过期，请重新登录');
        break;
      case 403:
        ElMessage.error('权限不足');
        break;
      case 404:
        ElMessage.error('资源不存在');
        break;
      case 500:
        ElMessage.error('服务器错误，请稍后重试');
        break;
      default:
        ElMessage.error(message);
    }
    
    return Promise.reject(error);
  }
);
```

### 文件上传错误处理

```typescript
// 文件大小验证
if (file.size > maxSize * 1024 * 1024) {
  ElMessage.error(`文件大小不能超过${maxSize}MB`);
  return false;
}

// 文件类型验证
const validTypes = ['application/pdf', 'application/msword', ...];
if (!validTypes.includes(file.type)) {
  ElMessage.error('不支持的文件类型');
  return false;
}

// OSS上传失败
try {
  await uploadToOss(file, policy);
} catch (error) {
  ElMessage.error('文件上传失败，请重试');
  throw error;
}
```

### 流式响应错误处理

```typescript
// EventSource错误处理
eventSource.onerror = (error) => {
  eventSource.close();
  ElMessage.error('连接中断，请重试');
  isStreaming.value = false;
};

// 超时处理
const timeout = setTimeout(() => {
  eventSource.close();
  ElMessage.error('响应超时');
}, 60000);
```

## 测试策略

### 单元测试

使用Vitest进行单元测试，覆盖以下内容：

1. **工具函数测试**
   - 日期格式化
   - 文件大小格式化
   - 表单验证规则

2. **Composables测试**
   - useAuth: 登录/登出逻辑
   - useUpload: 文件上传流程
   - useStream: 流式响应处理

3. **Store测试**
   - authStore: 状态变更和持久化
   - knowledgeStore: 列表管理和分页
   - chatStore: 会话管理和消息存储

### 组件测试

使用Vue Test Utils进行组件测试：

1. **OssUpload组件**
   - 文件选择和验证
   - 上传进度显示
   - 成功/失败回调

2. **MessageList组件**
   - 消息渲染
   - 自动滚动
   - 来源引用显示

3. **InputBox组件**
   - 输入验证
   - 发送事件触发
   - 禁用状态

### E2E测试

使用Playwright进行端到端测试：

1. **用户认证流程**
   - 登录成功/失败
   - 注册流程
   - Token过期处理

2. **文件上传流程**
   - 单文件上传
   - 批量上传
   - 上传进度显示

3. **问答流程**
   - 发送问题
   - 接收流式响应
   - 查看来源引用

### 性能测试

1. **首屏加载时间** < 2秒
2. **路由切换时间** < 500ms
3. **API响应时间** < 1秒
4. **文件上传速度** 取决于网络和文件大小
5. **流式响应延迟** < 100ms

### 兼容性测试

**浏览器支持**：
- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

**移动端支持**：
- iOS Safari 14+
- Android Chrome 90+

## 安全考虑

### 1. XSS防护

- 使用Vue的模板语法自动转义
- 对用户输入进行HTML转义
- 使用DOMPurify清理富文本内容

### 2. CSRF防护

- 使用JWT Token而非Cookie
- 在请求头中携带Token
- 后端验证Token有效性

### 3. 敏感信息保护

- Token存储在localStorage（仅限HTTPS）
- 不在URL中传递敏感信息
- 生产环境禁用console.log

### 4. 文件上传安全

- 前端验证文件类型和大小
- 使用临时签名上传到OSS
- 不暴露OSS AccessKey

### 5. API安全

- 所有请求使用HTTPS
- 设置请求超时
- 限制并发请求数量

## 性能优化

### 1. 代码分割

```typescript
// 路由懒加载
const routes = [
  {
    path: '/knowledge',
    component: () => import('@/views/knowledge/List.vue')
  }
];
```

### 2. 组件懒加载

```vue
<script setup>
const OssUpload = defineAsyncComponent(() => 
  import('@/components/Upload/OssUpload.vue')
);
</script>
```

### 3. 虚拟滚动

对于长列表（如知识库列表、聊天历史），使用虚拟滚动：

```vue
<el-virtual-list :data="messages" :item-size="80" />
```

### 4. 请求优化

- 使用防抖/节流处理搜索输入
- 缓存API响应结果
- 使用分页减少数据量

### 5. 资源优化

- 图片使用WebP格式
- 启用Gzip压缩
- 使用CDN加速静态资源

## 部署配置

### 开发环境 (.env.development)

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_APP_TITLE=电力知识库管理系统
```

### 生产环境 (.env.production)

```env
VITE_API_BASE_URL=https://api.yourdomain.com
VITE_APP_TITLE=电力知识库管理系统
```

### Vite配置 (vite.config.ts)

```typescript
export default defineConfig({
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue', 'vue-router', 'pinia']
        }
      }
    }
  }
});
```

## 国际化支持

虽然当前版本仅支持中文，但架构设计考虑了未来的国际化需求：

```typescript
// i18n配置（预留）
import { createI18n } from 'vue-i18n';

const i18n = createI18n({
  locale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  }
});
```

## 可访问性

- 使用语义化HTML标签
- 提供键盘导航支持
- 添加ARIA标签
- 支持屏幕阅读器
- 确保颜色对比度符合WCAG标准

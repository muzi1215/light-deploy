# Admin Template — 通用后台管理系统模版

基于 **Vue3 + Vite + TypeScript + Element Plus + Pinia** 的通用后台管理模版，开箱即用。

---

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
- [目录结构](#目录结构)
- [架构设计](#架构设计)
- [对接后端](#对接后端)
- [菜单与路由](#菜单与路由)
- [主题定制](#主题定制)
- [权限控制](#权限控制)
- [开发指南](#开发指南)
- [构建部署](#构建部署)
- [常见问题](#常见问题)

---

## 特性

| 特性 | 说明 |
|------|------|
| **深色/浅色主题** | 一键切换，CSS 变量驱动，覆盖 Element Plus 全部组件 |
| **模拟登录** | 内置模拟数据，无需后端即可运行和体验全部功能 |
| **动态菜单** | 后端返回菜单 JSON，前端自动渲染侧边栏和多级路由 |
| **静态菜单** | 也支持前端手动配置路由，灵活切换 |
| **标签页导航** | 多标签页管理，支持右键关闭、刷新、固定 |
| **权限控制** | 路由级 + 按钮级权限，后端控制 |
| **响应式布局** | 适配桌面端和移动端 |
| **SVG 图标** | 内置 SVG 图标系统，Element Plus 图标全局可用 |
| **TypeScript** | 全项目 TS，类型安全 |
| **代码分割** | 自动按 vendor 分包构建 |

---

## 快速开始

### 环境要求

- **Node.js** >= 18（推荐 v20+）
- **npm** >= 9（或 pnpm / yarn）

### 安装运行

```bash
# 1. 进入项目目录
cd admin-template

# 2. 安装依赖
npm install

# 3. 启动开发服务器
npm run dev
```

浏览器自动打开 `http://localhost:9850`，看到登录页即为成功。

### 模拟登录

模版内置了模拟登录，**无需后端即可体验完整功能**。任意输入用户名和密码即可登录，登录后可看到示例菜单。

模拟登录实现在 `src/api/user.ts`，包含：
- 任意账号均可登录
- 返回模拟 Token
- 返回示例用户信息和动态菜单

```
用户名：任意
密码：任意
```

### 配置品牌信息

编辑 `.env.development`：

```env
VITE_APP_TITLE = 我的后台
VITE_APP_SUBTITLE = 管理系统
```

---

## 目录结构

```
admin-template/
├── index.html                     # HTML 入口
├── package.json                   # 依赖和脚本
├── vite.config.ts                 # Vite 构建配置
├── tsconfig.json                  # TypeScript 配置
├── .env.development               # 开发环境变量
├── .env.production                # 生产环境变量
├── .env.staging                   # 预发布环境变量
│
└── src/
    ├── main.ts                    # 应用入口
    ├── App.vue                    # 根组件
    ├── settings.ts                # 全局设置（标题、布局开关）
    ├── permission.ts              # 路由守卫（Token 校验 + 动态路由加载）
    ├── env.d.ts                   # TypeScript 声明
    │
    ├── api/                       # API 接口层
    │   ├── user.ts                # 认证接口（登录/获取信息/登出）
    │   └── example.ts             # 示例 CRUD 接口
    │
    ├── assets/                    # 静态资源
    │   └── 404_images/            # 404 页面图片
    │
    ├── components/                # 公共组件
    │   ├── Breadcrumb/            # 面包屑导航
    │   ├── Hamburger/             # 侧边栏折叠按钮
    │   ├── ParentView/            # 父级视图容器（动态路由使用）
    │   ├── SvgIcon/               # SVG 图标组件
    │   └── StatusDisplay.vue      # 状态展示组件
    │
    ├── composables/               # 组合式函数
    │   └── useResizeHandler.ts    # 窗口响应式 + 移动端检测
    │
    ├── config/                    # 配置
    │   └── apiConfig.ts           # 环境相关 URL 配置
    │
    ├── icons/                     # SVG 图标系统
    │   ├── index.ts               # 图标注册入口
    │   ├── svgo.yml               # SVGO 优化配置
    │   └── svg/                   # 自定义 SVG 图标（11个）
    │
    ├── layout/                    # 布局系统
    │   ├── index.vue              # 主布局容器
    │   └── components/
    │       ├── AppMain.vue        # 主内容区（keep-alive）
    │       ├── Navbar.vue         # 顶部导航栏（主题切换 + 用户菜单）
    │       ├── Sidebar/           # 侧边栏（菜单递归渲染）
    │       │   ├── index.vue      # 侧边栏容器
    │       │   ├── SidebarItem.vue # 菜单项递归组件
    │       │   ├── Item.vue       # 图标+标题渲染
    │       │   ├── Link.vue       # 路由链接
    │       │   └── Logo.vue       # 侧边栏 Logo
    │       └── TagsView/          # 标签页视图
    │           ├── index.vue      # 标签页管理
    │           └── ScrollPane.vue # 滚动面板
    │
    ├── router/                    # 路由配置
    │   └── index.ts               # 静态路由定义
    │
    ├── store/                     # Pinia 状态管理
    │   ├── index.ts               # 统一导出
    │   ├── app.ts                 # 侧边栏 + 设备类型
    │   ├── user.ts                # 用户认证 + 菜单权限
    │   ├── settings.ts            # 应用设置
    │   ├── tagsView.ts            # 标签页状态
    │   └── theme.ts               # 深色/浅色主题
    │
    ├── styles/                    # 全局样式
    │   ├── index.scss             # 全局入口 + Element Plus 暗黑覆盖
    │   ├── variables.scss         # CSS 变量（70+ 主题变量）
    │   ├── variables.ts           # JS 侧边栏变量导出
    │   ├── mixin.scss             # SCSS 混入
    │   ├── sidebar.scss           # 侧边栏样式
    │   ├── transition.scss        # 过渡动画
    │   ├── element-ui.scss        # Element Plus 兼容补丁
    │   └── element-plus.scss      # Element Plus 扩展（预留）
    │
    ├── types/                     # TypeScript 类型定义
    │   ├── api.d.ts               # API 响应 + 业务类型
    │   └── router.d.ts            # 路由 Meta 扩展
    │
    ├── utils/                     # 工具函数
    │   ├── request.ts             # Axios 封装（拦截器 + 错误处理）
    │   ├── auth.ts                # Token Cookie 管理
    │   ├── validate.ts            # 表单验证
    │   ├── index.ts               # 通用工具（时间格式化等）
    │   ├── btn-permission.ts      # 按钮权限判断
    │   └── get-page-title.ts      # 页面标题拼接
    │
    └── views/                     # 页面视图
        ├── login/                 # 登录页
        │   └── index.vue
        ├── dashboard/             # 仪表盘（示例页，可替换）
        │   └── index.vue
        ├── example/               # 示例 CRUD 页（开发参考）
        │   └── list.vue
        └── 404.vue                # 404 页面
```

---

## 架构设计

### 分层架构

```
View（页面组件）
  ↓ 调用
API 层（src/api/）
  ↓ 发送 HTTP 请求
后端接口
  ↓ 响应
Store（Pinia 状态管理）
  ↓ 驱动
View 渲染
```

**原则**：页面组件只负责展示和交互，数据获取通过 API 层，全局状态放 Store。

### 数据流

```
用户登录 → 获取 Token → 请求携带 Token
                          ↓
                    路由守卫校验
                          ↓
                    调用 getInfo() 获取用户信息 + 菜单 JSON
                          ↓
                    动态注册路由 → 渲染侧边栏菜单
```

---

## 对接后端

### 从模拟登录切换到真实后端

模版默认使用**模拟登录**，无需后端即可运行。准备好后端后，按以下步骤切换：

**1. 配置 API 代理**

编辑 `.env.development`：

```env
VITE_BASE_API = '/api'
```

编辑 `vite.config.ts` 中的 proxy：

```ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',  // 改为你的后端地址
      changeOrigin: true
      // 如果后端没有 /api 前缀，取消下面注释：
      // rewrite: (path) => path.replace(/^\/api/, '')
    }
  }
}
```

**2. 修改 `src/api/user.ts`**

注释掉文件中的 mock 函数（3 个 `async function`），取消注释底部 3 个真实 API 调用的函数。详见文件内注释。

### 1. 后端需实现的接口

后端需要提供以下 3 个认证接口：

#### POST /login — 登录

请求：
```json
{ "username": "admin", "password": "123456" }
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzUxMiJ9..."
  }
}
```

#### GET /info — 获取当前用户信息

请求头：`Authorization: Bearer {token}`

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "1",
    "name": "管理员",
    "avatar": "https://example.com/avatar.png",
    "buttons": ["bnt.sysUser.add", "bnt.sysUser.update"],
    "routers": [
      {
        "path": "/system",
        "component": "Layout",
        "meta": { "title": "系统管理", "icon": "system" },
        "children": [
          {
            "path": "user",
            "component": "system/user/list",
            "name": "UserList",
            "meta": { "title": "用户管理", "icon": "user" }
          }
        ]
      }
    ]
  }
}
```

`routers` 字段说明：
- **path**：路由路径（相对 /system 则为 "user"，绝对路径以 / 开头）
- **component**：组件路径，相对于 `src/views`，不含 `.vue`
  - 特殊值 `"Layout"`：使用布局容器
  - 特殊值 `"ParentView"`：使用空父视图
  - 其他值如 `"system/user/list"`：对应 `src/views/system/user/list.vue`
- **name**：路由名称（可选，用于 keep-alive 缓存）
- **meta**：菜单元信息
  - `title`：菜单标题
  - `icon`：图标名（Element Plus 图标名 或 SVG 图标名）
  - `hidden`：是否隐藏（true 时不在侧边栏显示）
  - `affix`：是否固定标签页
  - `noCache`：是否不缓存
- **children**：子路由数组

#### GET /logout — 退出登录

响应：
```json
{ "code": 200, "message": "success" }
```

### 2. 统一响应格式

后端所有接口需要返回统一 JSON 格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

- `code` 不为 200 时前端自动弹出错误提示
- 分页数据可在 `data` 中返回 `records` + `total`

### 3. 配置 API 代理

开发环境通过 Vite proxy 转发请求，修改 `vite.config.ts`：

```ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',   // 你的后端地址
      changeOrigin: true
      // 如果后端接口没有 /api 前缀，取消下面注释：
      // rewrite: (path) => path.replace(/^\/api/, '')
    }
  }
}
```

### 4. 静态菜单模式（不需要动态菜单）

如果不需要从后端获取菜单（或后端暂未实现），直接在前端配置：

1. 让后端 `/info` 接口的 `routers` 返回空数组 `[]`
2. 编辑 `src/router/index.ts`，在 `constantRoutes` 中添加路由：

```ts
export const constantRoutes = [
  { path: '/login', component: () => import('@/views/login/index.vue'), hidden: true },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('@/views/dashboard/index.vue'), meta: { title: '首页', icon: 'dashboard', affix: true } },

      // ↓ 在这里添加你的业务路由 ↓
      {
        path: 'user',
        component: Layout,
        meta: { title: '用户管理', icon: 'user' },
        children: [
          { path: 'list', component: () => import('@/views/user/list.vue'), meta: { title: '用户列表' } }
        ]
      }
    ]
  }
]
```

---

## 菜单与路由

### 菜单渲染机制

侧边栏菜单通过路由自动渲染：

1. **静态路由**：`src/router/index.ts` 中的 `constantRoutes`
2. **动态路由**：后端 `getInfo()` 返回 `routers`，由 `src/permission.ts` 动态注册

两者合并后在 `Sidebar/index.vue` 中渲染。

### 路由 Meta 字段

```ts
interface RouteMeta {
  title?: string       // 页面标题（显示在菜单、标签页、面包屑）
  icon?: string        // 图标名称（Element Plus 图标 或 SVG 图标）
  affix?: boolean      // 是否固定标签页（首页通常设为 true）
  hidden?: boolean     // 是否在侧边栏隐藏（hidden: true 仍可访问）
  noCache?: boolean    // 是否禁用 keep-alive 缓存
  activeMenu?: string  // 高亮指定菜单路径（详情页关联列表页）
  roles?: string[]     // 角色权限（后端控制时可选）
}
```

### 添加新页面

以"用户管理"为例：

**方式一：动态菜单（推荐）**

1. 创建页面文件：`src/views/user/list.vue`
2. 创建 API 文件：`src/api/user.ts`
3. 在后端菜单管理中配置菜单（或在 `/info` 的 `routers` 中添加）
4. 刷新页面，菜单自动出现

**方式二：静态菜单**

1. 创建页面文件：`src/views/user/list.vue`
2. 在 `src/router/index.ts` 中手动添加路由
3. 重启开发服务器

---

## 主题定制

### 切换主题

点击顶部导航栏的太阳/月亮图标即可切换深色/浅色主题，偏好自动保存在 localStorage。

编程方式：
```ts
import { useThemeStore } from '@/store/theme'
const themeStore = useThemeStore()
themeStore.toggleTheme()   // 切换
themeStore.setTheme('dark') // 设置为深色
```

### 自定义主题色

编辑 `src/styles/variables.scss`，修改 CSS 变量：

```scss
:root {
  --menu-active-text: #409EFF;     // 菜单高亮色 / 主题色
  --menu-bg: #304156;              // 菜单背景色
  --menu-text: #bfcbd9;            // 菜单文字色
  --menu-hover: #263445;           // 菜单悬停色
  // ... 更多变量见文件内注释
}

[data-theme="dark"] {
  --menu-active-text: #409EFF;     // 暗黑主题菜单高亮色
  --menu-bg: #1d1e1f;
  // ...
}
```

### 使用 CSS 变量

在业务页面中，使用 CSS 变量适配主题：

```scss
.my-component {
  color: var(--text-primary);
  background: var(--card-bg);
  border: 1px solid var(--border-color);
}
```

常用 CSS 变量：

| 变量 | 用途 |
|------|------|
| `--text-primary` | 主要文字色 |
| `--text-regular` | 常规文字色 |
| `--text-secondary` | 次要文字色 |
| `--page-bg` | 页面背景色 |
| `--card-bg` | 卡片背景色 |
| `--border-color` | 边框色 |
| `--menu-active-text` | 主题色/高亮色 |
| `--table-row-hover` | 表格行悬停色 |

---

## 权限控制

### 路由级权限

在路由 meta 中配置 `roles`，或由后端 `routers` 中根据角色过滤返回不同菜单。

### 按钮级权限

后端在 `/info` 接口的 `buttons` 数组中返回权限标识：

```json
{
  "buttons": ["bnt.sysUser.add", "bnt.sysUser.update", "bnt.sysUser.remove"]
}
```

在页面中使用：
```vue
<template>
  <el-button v-if="$hasBP('bnt.sysUser.add')">新增</el-button>
</template>

<script setup lang="ts">
import { getCurrentInstance } from 'vue'
const { proxy } = getCurrentInstance()!
// proxy.$hasBP('bnt.sysUser.add')
</script>
```

---

## 开发指南

### 新增 CRUD 页面

1. 复制 `src/views/example/list.vue` 到你的业务目录
2. 复制 `src/api/example.ts` 到对应 API 目录
3. 修改搜索条件、表格列、表单字段
4. 替换 API 调用为你的实际接口
5. 注册路由（动态菜单或静态菜单）

### API 封装

```ts
// src/api/yourModule.ts
import request from '@/utils/request'

export default {
  getPageList(page: number, limit: number, params?: any) {
    return request({ url: `/your-module/list/${page}/${limit}`, method: 'get', params })
  },
  save(data: any) {
    return request({ url: '/your-module/save', method: 'post', data })
  },
  updateById(data: any) {
    return request({ url: '/your-module/update', method: 'put', data })
  },
  removeById(id: number) {
    return request({ url: `/your-module/remove/${id}`, method: 'delete' })
  }
}
```

### Store 使用

```ts
import { useUserStore } from '@/store/user'
const userStore = useUserStore()
console.log(userStore.name)       // 当前用户名
console.log(userStore.buttons)    // 权限按钮列表
```

### 常用工具函数

```ts
import { parseTime, formatTime } from '@/utils'
parseTime(new Date(), '{y}-{m}-{d} {h}:{i}:{s}') // "2025-01-01 12:00:00"

import { getToken, setToken, removeToken } from '@/utils/auth'
getToken()  // 获取当前 Token
```

---

## 构建部署

```bash
# 构建生产版本
npm run build

# 构建预发布版本
npm run build:staging

# 预览构建结果
npm run preview
```

构建产物在 `dist/` 目录，直接部署到 Nginx 或其他 Web 服务器。

### Nginx 配置示例

```nginx
server {
    listen 80;
    server_name admin.example.com;

    root /var/www/admin-template/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # 反向代理后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 子路径部署

如果部署在子路径下（如 `/admin`），修改 `vite.config.ts`：

```ts
export default defineConfig({
  base: '/admin',  // 改为你的子路径
})
```

同时修改路由：

```ts
const router = createRouter({
  history: createWebHashHistory('/admin'),  // 传入子路径
})
```

---

## 常见问题

### Q: 登录后菜单不显示？

检查后端 `/info` 接口的 `routers` 字段是否正确返回菜单 JSON。确保 `component` 字段对应的 `.vue` 文件存在于 `src/views` 目录下。

### Q: 页面打开空白？

打开浏览器控制台查看报错。常见原因：
- 组件路径错误（`component` 字段对应的文件不存在）
- 路由 path 重复

### Q: 主题切换后组件颜色没变？

业务组件中需要将硬编码颜色改为 CSS 变量：
```scss
// 错误
.my-div { color: #333; }

// 正确
.my-div { color: var(--text-primary); }
```

### Q: 如何去掉标签页导航？

编辑 `src/settings.ts`：
```ts
export default {
  tagsView: false  // 设为 false 关闭标签页
}
```

### Q: 如何切换为 history 模式路由？

编辑 `src/router/index.ts`：
```ts
import { createRouter, createWebHistory } from 'vue-router'
const router = createRouter({
  history: createWebHistory(),  // 替换 createWebHashHistory()
})
```

> 注意：history 模式需要后端配合配置所有路径 fallback 到 index.html。

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 框架 | Vue | 3.4+ |
| 语言 | TypeScript | 5.4+ |
| 构建 | Vite | 5.x |
| UI 库 | Element Plus | 2.7+ |
| 状态管理 | Pinia | 2.x |
| 路由 | Vue Router | 4.x |
| HTTP | Axios | 1.7+ |
| CSS | SCSS | (sass 1.77+) |
| 图标 | @element-plus/icons-vue | 2.3+ |

---

## License

MIT

# AGENTS.md

面向在本仓库中工作的编码代理/协作者的约定。**改动前先读本文件**；更深层目录若另有
`AGENTS.md`，以更深层为准。项目说明、部署步骤、API 细节见 `README.md` 与
`Neko歌姬计划文档/README.md`。

> **首要约束（违反即视为未完成）**
> 1. 新增 API 必须显式设置 `Cache-Control`（见「HTTP 缓存策略」）。
> 2. 新增/修改/删除后端 API 必须同步更新 API 文档（见「API 文档同步」）。
> 3. 新增运行时配置不得写入 `config.yml`（见「安全防护约定」）。
> 3.1 **新增运行时配置一律加到 `system_settings`**（后端 `config/SystemSettingRegistry.java` 登记 +
>      后台「系统设置」页维护），`config.yml` 只保留 `mysql.*` 启动引导信息（见「运行时配置」）。
> 4. API 文档**只写客户端契约，不写安全实现细节**（见「安全防护约定」）。
> 5. 不提交密钥/本地配置、构建产物，以及与任务无关的子模块指针变动。

## 项目结构

```
backend/                Java 后端（Jetty 12 + Servlet，Maven，Java 27），Web 与 API 的唯一服务端
frontend/               Vue 3 + Vite 前端（构建产物打进后端 classpath 的 site/）
Android/                Android 客户端（git submodule，独立仓库）
pc/                     桌面客户端 Qt/C++（git submodule，独立仓库）
Neko歌姬计划文档/        API/产品文档（git submodule，独立仓库）
pc/ 与 Android/        是子模块：除非任务明确要求，不要改动、不要顺手提交其指针变动
```

- 浏览器/客户端访问的是**同一个后端**（默认端口 `65535`）；前端 dev server 通过代理与后端同源
  （`frontend/.env.development` 的 `VITE_DEV_PROXY_TARGET`），因此**不需要**为浏览器放开 CORS。
- 站内图片、音频、安装包等由后端直接提供（见 `handlers/`、`util/HttpResourceCache.java`）。

## 构建与运行

### 后端
```bash
# 推荐：Docker（首次需 cp src/main/resources/config.yml config.yml 并填好 MySQL）
cd backend && docker compose build && docker compose up -d && docker compose logs -f neko-music

# 本地 Maven（Java 27）
cd backend && mvn -B package -DskipTests     # 打包
cd backend && mvn test                       # 跑单元测试（JUnit 5）
```
- 入口 `com.neko.music.Main`；路由集中在 `handlers/ServletRegistrar.java`。
- 生产配置来自运行目录的 `backend/config.yml`（由 `src/main/resources/config.yml` 复制并填写），
  其中**只需要** `mysql.*`；其余运行时配置在管理后台「系统设置」里维护（存 `system_settings` 表）。
  模板缺失时后端会自动复制，因此 `config.yml` 里被删掉的节不会导致启动失败。

### 前端
```bash
cd frontend && npm install
cd frontend && npm run dev      # http://localhost:5173，/api、/version 代理到本地后端
cd frontend && npm run build    # 产物输出到 ../backend/src/main/resources/site（该目录已 gitignore）
```
- 前端产物是**构建物**，不要提交 `backend/src/main/resources/site/*`；部署/打包前必须跑一次 `npm run build`。
- 前后端改动互相依赖时，先 `npm run build` 让后端能提供最新前端，再验证。

### 客户端（子模块）
`Android/`、`pc/` 各自有独立仓库与构建脚本，参见其 `README.md`；本仓库只固定子模块指针。

## 测试

- 后端：`cd backend && mvn test`（现有用例在 `backend/src/test/java/...`，覆盖 handler、util、service）。
- 前端：未配置测试框架/`lint` 脚本；改完至少 `npm run build` 保证可编译。
- 无格式化/lint 门禁，但请保持与相邻代码一致的风格。

## 代码约定

- **路由必须注册在 `ServletRegistrar`**：嵌入式 Jetty 不处理 `@WebServlet` 注解。
  映射遵循 Servlet 规范：精确路径 > 最长前缀 > 扩展名 > 默认 `/`，因此 `/api/music/latest`
  会胜过通配的 `/api/music/*`。新增接口用清晰的最小前缀，避免与既有通配产生歧义。
- HTTP JSON 处理器继承 `handlers/ApiServlet`，统一用 `sendSuccessResponse` / `sendErrorResponse` /
  `writeJson` 等，不要各自复制响应样板；错误体保持 `{"success":false,"message":"..."}` 契约。
- 注释、日志、提交信息用中文；提交信息用 Conventional Commits 中文描述，例如
  `fix(frontend): 修复播放页手势拦截控件触摸与两处布局偏移`。
- 前端统一使用 `@/ui` 组件（`NButton`、`NIcon`…）与 `design/tokens.css` 的 CSS 变量，
  不要新造一套按钮/颜色；图标走图标注册表（`NIcon` 的 `name`）。
- 涉及 MySQL/Redis 等外部依赖的改动，保持失败可降级或明确报错，不要静默吞异常。
- **改后端 API 必须同步改 API 文档**（见下节）；文档内容还要遵守「安全防护约定」的脱敏要求。

## 运行时配置（system_settings）

- **`config.yml` 只放启动引导信息**：当前仅 `mysql.*`（连上主库之前无从读取数据库）。
  其余配置项一律登记到后端 `config/SystemSettingRegistry.java`，值存在主库 `system_settings` 表，
  由 `GET/PUT /api/admin/settings`（管理员及以上）在后台「系统设置」页维护，**禁止**再往
  `config.yml` 加节或加键。该接口属于后台管理接口，**不写入 API 文档**（见「API 文档脱敏」）。
- 首次启动若 `system_settings` 表为空，会把 `config.yml` 的现值（缺省则取出厂默认值）灌一次，
  保证老部署升级后行为不变；此后以表里的值为准，`config.yml` 里的同项不再生效。
- 新增配置项时必须同时：登记到 `SystemSettingRegistry`（含出厂默认值、类型、范围与是否需重启）、
  在读取端复用 `ConfigManager` 的解析分支（按点分路径合并进配置树，不要另写 setter）。
- 敏感项（`secret`）只写不读：接口只回「是否已配置」，不回明文；数据库与日志中同样不得出现明文。
- **不在清单里的键 = 后台既看不到也改不了**：`SystemSettingRegistry` 是「后台可见可改键」的唯一
  清单，不在其中的键（如 `video_render.non_vip_max_duration_sec`、`netease_search_fill.language|
  upload_user_id` 这类写死的策略项）由 `ConfigManager` 的字段默认值 / `config.yml` 决定，
  既不进 `system_settings` 表、也不出现在后台；`SystemSettingsDatabaseManager.pruneUnregistered()`
  会在启动时清掉表里遗留的非清单键。改动清单时要同步更新 `SystemSettingRegistryTest` 的名单用例。
- 启动期绑定的项（端口、线程池、连接池、Redis 地址等）用 `.restart()` 标注，后台保存后回提示
  「需重启」，服务端不做热重载。

## 安全防护约定（强约束）

### 响应安全头（CSP）

- 全部响应（页面、静态资源、API、SSE、媒体、报错页）都由 `filter/SecurityHeadersFilter.java`
  统一带上 `Content-Security-Policy` 与其余加固头；策略是代码里的类常量，**不要**新增 `config.yml`
  键或 `system_settings` 项。
- 策略取值受现有前端实现约束，改前端前先确认：构建产物的 `index.html` 带内联首屏样式
  （`style-src 'unsafe-inline'`）、播放页可视化用 PixiJS 在运行期 `new Function` 生成 uniform
  同步代码（`script-src 'unsafe-eval'`）、封面可能是第三方（网易云 / QQ / 酷狗）http(s) 直链
  （`img-src https: http:`）。新增内联脚本、外部 CDN、iframe 或 Worker 时必须同步调整策略。
- 策略有单测锁定（`SecurityHeadersFilterTest`）：收紧或放宽都要在同一处改用例，否则视为未完成。

### User-Agent 与 SEO

- 所有出站请求应携带标准 `User-Agent`；官方客户端的 UA 为 `NekoMusic-<平台>/<版本>`。
- `User-Agent` 只用于 SEO 页面与 SPA 壳的表示选择，不参与 API 鉴权、放行或拦截。
- 公开且允许 CDN 缓存的接口（`/api/music/ranking`、`/api/music/latest`）对所有请求者一致返回
  JSON，不按 UA 分流。

### NekoAuth 单点登录

- 浏览器登录遵循 NekoAuth 的 OpenID Connect 授权码流程：从发现文档读取端点，使用 PKCE S256、
  一次性 state 与 nonce，并通过 JWKS 校验 ID Token 的签名、issuer、audience、azp、nonce、at_hash
  和 subject。
- 规范回调地址为 `/sso/nekocore/callback`；旧 `/oauth/nekocore/callback` 只作兼容别名。
- 原生客户端回跳先经过站内 HTTPS 页面 `/sso/native-complete`，再由页面打开
  `nekomusic://sso/complete?handoff=...`；禁止服务端直接 302 到自定义协议，避免 CDN 拒绝非 HTTP
  Location。页面必须保留手动按钮，处理浏览器阻止自动跳转的情况。
- 一次性 handoff 可以进入深链，access token 禁止进入深链；回跳目标只能由服务端按 state 中的
  客户端类型选择。
- NekoAuth 客户端密钥只能保存在服务端，不得下发到浏览器、Android 或 PC 客户端。
- Web、Android 与 PC 新界面不得再提供邮箱密码、注册验证码或原生改密/改昵称表单；注册与账号资料
  修改统一打开 NekoAuth 页面。Android 使用应用内 WebView，Web 使用当前浏览器，PC 使用系统浏览器。
- 用户头像以 NekoAuth 为唯一权威源。NekoMusic 的 `/api/user/avatar/*` 只作兼容代理，后端的
  `/api/user/avatar/upload` 也只转发到 Auth，不得再写本地头像路径或新增本地头像文件。
- Web 无感登录使用 Auth 写入的父域 HttpOnly `nekoauth_sso_session` 会话句柄，由子域后端通过
  `account:bridge` 内部接口直接兑换登录态，不得让浏览器跳转 Auth 做静默 OIDC。刷新令牌 Cookie
  仍保持 host-only，禁止整个刷新令牌扩展到父域。
- Web 退出必须撤销父域共享会话族并清除 `nekoauth_sso_session` / `nekoauth_session_hint` Cookie；
  不能只清 `localStorage`，也不得重新引入跳转式全局登出。

### API 文档脱敏

- **铁律：后台管理类接口不写入 API 文档。** 管理端（`/api/admin/*` 等）只面向官方管理后台与
  运维，其存在与取值都属于内部信息；新增这类接口时不要往文档里加端点、字段或示例。
- API 文档只描述**客户端可见契约**：端点、请求头 / 参数、请求体、响应结构、状态码，以及客户端
  必须遵守的行为约定。
- **不得**写入安全实现细节，包括但不限于：防护判定规则与判定顺序、爬虫关键词与放行/白名单、
  频率与时效的具体数值阈值、内部存储与算法（Redis / Lua 等）、配置项名称、
  IP 解析与 CDN 头的处理方式，以及「某路径未被保护 / 可被绕过 / 地址可复用」之类的旁路说明。
- 防护类专项文档要在开头声明：具体判定规则、关键词、名单与阈值属安全实现，**不对外公开且会随时调整**。
- 破坏性变更仍需写清兼容 / 迁移方式，但只描述调用方式，不解释背后的防护机制。

## API 文档同步（强约束）

**后端 API 的新增、修改、删除，必须在同一次改动中同步更新 API 文档；只改代码不改文档视为未完成。**
包括但不限于：路由/方法变更、请求参数或字段变更、响应结构变更、鉴权/权限变化、状态码变化、接口下线。

- 主文档：`Neko歌姬计划文档/README.md`（标题为「Neko歌姬计划 API 文档」，含 `## 目录`）。
- 专项文档：如 `API-滑块与人机验证.md`（均在
  `Neko歌姬计划文档/`）；涉及对应流程时一并更新，并按「API 文档脱敏」收敛内容。
  新专项主题可新增独立 `.md`，并在主文档 `## 目录` 里加链接。
- 文档在 **git submodule** `Neko歌姬计划文档/`（独立仓库 `FantasyNetworkCN/NekoMusicDocs`）。
  改文档要提交到该子模块仓库；若需要在本仓库固定新版本，再单独更新子模块指针，不要夹带无关指针变动。

每个端点按现有格式补齐，字段缺一不可：

- 分节序号 + 标题（如 `### 21. 扫码登录`），并同步更新 `## 目录` 中对应条目/链接。
- `**端点:**` `METHOD /path`。
- `**请求头:**`（鉴权使用 `Authorization: Bearer <token>`，公开接口要注明「无需登录」）。
- 请求体 / 查询参数说明及示例。
- `**响应示例（成功）**` 与 `**响应示例（失败/未登录）**`。
- `**状态码:**` 及各码含义。

破坏性变更（改路径、改字段名/类型、删除接口）要在文档中显式标注，并说明兼容/迁移方式。
改动若也影响 `README.md` 的功能描述或示例，记得一并更新。

## HTTP 缓存策略（强约束）

**所有新增 API 必须显式声明 `Cache-Control`。** 动态接口由
`filter/CacheControlFilter.java` 兜底为 `private, no-store`（作用于 `/api/*`、`/loser/*`、`/detail/*`），
需要公开缓存的接口在处理器内 `setHeader` 覆盖。时长一律复用
`util/HttpResourceCache.java` 的常量，**不要写死数字**。

| 类别 | 响应头 | 说明 |
| --- | --- | --- |
| 动态 API、登录、注册、个人数据、后台 | `private, no-store` | 不缓存，每次回源 |
| `/api/user/avatar/default`（代理 Auth 默认头像） | 透传 Auth 的长期公共缓存头 | NekoMusic 不再保存默认头像资源 |
| `/api/user/avatar/{id}`（代理 Auth 用户头像） | 透传 Auth 的公共再校验缓存头 | NekoMusic 不再读取本地头像路径 |
| `/api/music/file/{id}`（音质解析跳转） | `private, no-store` | 每次重新解析 |
| 后台审核预览 `/api/user/upload/preview`（音频 / 封面 / 歌词） | `private, max-age=15552000, must-revalidate` | 六个月，且只允许浏览器私有缓存（待审核文件 + 管理员 token，不能被 CDN 免鉴权分发） |
| `/api/music/latest`、`/api/music/ranking` | `public, max-age=1800` | 半小时 |
| `/sitemap.xml`、`.txt`（robots/llms 等） | `public, max-age=86400` | 一天 |
| 静态固定资源（png/ico/svg/js/css/字体/webmanifest/安装包 .exe/.pak/.deb 等） | `public, max-age=15552000` | 六个月 |
| 带内容哈希的 `/assets/*` | `public, max-age=15552000, immutable` | 六个月 + immutable |
| 音频、封面、客户端安装包（磁盘文件） | `HttpResourceCache.applyFileCachingHeaders(...)` | 六个月 + `must-revalidate` + ETag/Last-Modified |
| `index.html`、`sw.js` | `no-cache` + ETag/Last-Modified | 必须能及时发版，靠 304 再校验 |

- 新增磁盘媒体/文件响应时，优先复用 `HttpResourceCache`（自动带 ETag、Last-Modified、`If-None-Match → 304`、
  `Accept-Ranges`），不要手写头部。
- 给「同一 URL 内容可能被替换」的资源别加 `immutable`；入口 HTML / Service Worker 保持可再校验，
  否则用户拿不到新版本。
- 需要 `Vary` 的响应（如按 User-Agent 区分 SEO HTML / SPA 壳）要显式设置，避免共享缓存串味。

## 不要提交

- `backend/config.yml` 等含密码/密钥的本地配置；样例模板提交在 `backend/src/main/resources/config.yml`。
- 构建产物：`backend/src/main/resources/site/*`、`target/`、`frontend/node_modules/`、
  `backend/Music/`、`backend/releases/`、`dist/`。
- 与任务无关的 `Android/`、`pc/` 子模块指针变动（`git status` 里常见，属他人/历史遗留，别顺手带上）。

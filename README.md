# NekoMusic

![](https://count.getloli.com/get/@:NekoMusic?theme=moebooru)

在线音乐平台（Web / Android / PC）。完整 API 说明见 [Neko歌姬计划文档/README.md](Neko歌姬计划文档/README.md)。


## Docker 部署（后端）

后端 Docker 配置位于 `backend/`，默认监听 `65535` 端口。当前 Compose 使用 `network_mode: host` 和 `gpus: all`，适用于安装了 NVIDIA Container Toolkit 的 Linux 主机；非 Linux 或无 NVIDIA GPU 环境请改用 CPU 渲染（见下方说明）。

首次部署：

```bash
cd backend
cp src/main/resources/config.yml config.yml
# 编辑 config.yml：只需填写 MySQL 连接信息（连库前无法从库里读配置）
mkdir -p Music/music Music/covers releases user_upload
NEKO_WEB_VERSION="$(git describe --tags --always)" docker compose build --pull
docker compose up -d
docker compose logs -f neko-music
```

检查服务：

```bash
curl -fsS http://127.0.0.1:65535/version
```

音乐文件、封面、声纹索引、待审核上传和客户端发布包分别持久化在 `backend/Music/`、`backend/user_upload/` 与 `backend/releases/`。头像统一由 NekoAuth 保存。复用已有部署的数据时，可在 `backend/.env` 设置 `NEKO_DATA_DIR=/原后端运行目录`；配置文件为 `backend/config.yml`（只含 MySQL 引导信息，密码等敏感项请勿提交到 Git）；运行时配置存
主库 `system_settings` 表，同样不要外泄。升级代码后执行
`NEKO_WEB_VERSION="$(git describe --tags --always)" docker compose build --pull && docker compose up -d`。

首次启动会自动建表 `system_settings`，并把出厂默认值（或旧 `config.yml` 里的现值）灌一次；之后
所有运行时配置（端口、Redis、JWT、邮件、频率限制、网络与安全、视频渲染、支付、日志等）都在管理后台
「系统设置」页维护，改完即时生效，`config.yml` 里对应项不再生效。

默认配置使用 `video_render.pipeline: cuda_native`，需要 NVIDIA GPU、驱动和容器工具包，并要求 FFmpeg 具备 NVENC/CUDA。没有 GPU 时，在管理后台「系统设置 → 视频渲染」把 `pipeline` 改为 `cpu_legacy`、把 `video_codec` 改为
`libx264`（需重启后端），并从 `backend/docker-compose.yaml` 删除 `gpus: all`；在 Docker Desktop 上还需将 `network_mode: host` 改为 `ports: ["65535:65535"]`，并按实际地址修改 MySQL/Redis 的 host。


## 账号体系（NekoAuth）

用户账号、密码、登录会话与用户 Token 的唯一权威源是 NekoAuth。NekoMusic 不再保存用户密码或用户 Token，`users` 表只保留本地业务资料：

- `auth_user_id` 保存 NekoAuth 的稳定数字用户 ID，并建立唯一索引；这是账号关联的第一依据。
- `email` 是 NekoAuth 返回的邮箱快照，用于旧 API 响应、后台展示、邮件通知和首次关联补偿；它不是密码或登录凭据。
- 邮箱验证状态由 NekoAuth 管理，不作为 NekoMusic 登录门槛；NekoMusic 按 `sub` 关联本地业务资料。
- `nickname` 以 NekoAuth 为唯一权威源。NekoMusic 的 MySQL 不保存昵称列；评论、通知、歌单、用户搜索和后台展示都通过 NekoAuth 的内部账号查询接口实时解析，避免出现过期副本。
- 头像以 NekoAuth 为唯一权威源：上传、替换、默认头像和公开读取都由 NekoAuth 处理；NekoMusic 的原头像 API 保留为兼容入口，由后端从 NekoAuth 获取并返回。`vip_expires_at` 继续由 NekoMusic 本地维护。
- 用户密码、注册验证码、找回密码验证码、账号启用状态、用户 access token 的签发、校验与吊销全部由 NekoAuth 处理；NekoMusic 的 Redis 不再保存任何 `user_token` 键。

原有 `/api/user/login`、`/api/user/register`、`/api/user/send-verification`、`/api/user/send-reset-code`、`/api/user/reset-password`、改密和管理员用户管理接口保持原路径、请求体和响应结构不变。NekoMusic 后端在内部转发到 NekoAuth，旧客户端不需要改接 OAuth，也不需要接触客户端密钥；登录响应里的 `token` 字段仍在原位置，但内容已改为 NekoAuth access token。Web、Android 与 PC 新版本都使用 NekoAuth OIDC 单点登录；邮箱密码接口继续保留给旧客户端兼容。

首次登录或注册成功后，NekoMusic 会按 `auth_user_id` 查找本地资料；不存在时自动创建，只在本地保留邮箱映射和 VIP，不写入昵称或头像。若本地已有同邮箱资料但尚未绑定，会在首次认证时补绑；同一邮箱已绑定其他 NekoAuth 用户时拒绝登录，避免账号串绑。

### 配置

在 NekoMusic 管理后台的认证设置中填写以下运行配置，不要写进源码：

| 设置 | 说明 |
| --- | --- |
| `oauth.issuer` | NekoAuth 公网根地址，例如 `https://auth.nekocore.cn`，末尾不要带 `/` |
| `oauth.client_id` | 在 NekoAuth 自行创建的**机密客户端** ID |
| `oauth.client_secret` | 该客户端的密钥，只保存在 NekoMusic 服务端 |
| `oauth.redirect_uri` | 必须与 NekoAuth 登记值完全一致，默认 `https://music.nekocore.cn/sso/nekocore/callback` |
| `oauth.scopes` | 浏览器登录请求的 scope，必须包含 `openid profile email`；默认值另含 `roles` |

该客户端必须启用、`publicClient=false`，服务端账号桥接 scope 至少包含 `account:bridge`；浏览器 OIDC 流程还需要 `openid profile email`，若保留默认 `oauth.scopes`，客户端也要同时登记 `roles`。NekoAuth 不会自动注册客户端，代码中也没有写死的客户端 ID 或密钥。客户端密钥轮换后必须同步更新 NekoMusic。

NekoAuth 的 `account:bridge` 只允许服务端 Basic 认证，不能用于浏览器授权，也不会出现在公开发现文档中。NekoMusic 转发请求时使用 `oauth.client_id` 和 `oauth.client_secret`，客户端安装包与浏览器永远拿不到该密钥。

### 单点登录

浏览器登录完全由 NekoAuth 完成，NekoMusic 作为标准 OIDC 机密客户端接入：

- NekoMusic 从 NekoAuth 的 `/.well-known/openid-configuration` 读取授权、令牌、UserInfo 与 JWKS 端点，不硬编码端点地址。
- `POST /api/user/sso/start`：请求体 `{"returnTo":"<站内相对路径>"}`，返回 `{url}`。授权请求使用授权码流程、PKCE S256、一次性 state 与 OIDC nonce，前端随后直接导航到 NekoAuth。
- `GET /sso/nekocore/callback`：规范回调地址。服务端校验 state，用授权码与 `code_verifier` 换取 access token / ID Token，通过 NekoAuth JWKS 验证 ID Token 的签名、issuer、audience、azp、nonce、at_hash 与 `sub`，再读取 UserInfo 并换取 NekoAuth 长期第一方 token。旧 `/oauth/nekocore/callback` 保留为兼容别名。
- `GET /sso/complete`：前端完成页立即从地址栏清除 handoff，调用 `POST /api/user/sso/claim` 领取 token，写入 `localStorage.userToken` 后按原 `returnTo` 返回。交接 120 秒有效且只能领取一次；旧 `/oauth/nekocore/complete` 会重定向到这里。
- `POST /api/user/sso/claim`：请求体 `{"handoff":"..."}`，成功返回 `{token, returnTo, user}`；token 是 NekoAuth access token，`user` 是 NekoMusic 本地业务资料。响应为 `Cache-Control: private, no-store`。
- `POST /api/user/sso/password-reset-url`：返回 NekoAuth 的 `/forgot-password` 完整地址，前端随后直接导航。

Android / PC 调用 `/api/user/sso/start` 时分别传 `client=android`、`client=pc`。完成 NekoAuth 授权后，
服务端先回跳到站内 HTTPS 引导页 `/sso/native-complete`，再由页面打开
`nekomusic://sso/complete`，App 再调用 `POST /api/user/sso/claim` 兑换 token；长期 token 不会出现在
深链或浏览器历史中。HTTPS 引导页保留手动按钮，处理浏览器阻止自定义协议自动跳转的情况。
注册、找回密码和账号资料修改统一跳转 NekoAuth：Web 在当前浏览器中打开，Android 使用应用内
WebView，PC 使用系统浏览器。

已有部署升级时，应把 NekoAuth 客户端登记的回调地址与 `oauth.redirect_uri` 一起更新为
`https://music.nekocore.cn/sso/nekocore/callback`；旧 `/oauth/nekocore/callback` 仍可继续回调，
但新部署应直接使用规范地址。

浏览器与旧客户端使用同一个 `localStorage.userToken` / `Authorization` 字段，但 token 已由 NekoMusic 本地随机串改为 NekoAuth access token。NekoMusic 每个受保护请求通过 NekoAuth introspection 校验身份，登出通过 NekoAuth revoke 吊销令牌。密码修改、管理员重置密码和账号停用会推进 NekoAuth 的 token 版本，使旧 token 立即失效。

### 账号操作流程

- 登录：Web、Android 与 PC 新版本统一使用 NekoAuth OIDC 单点登录；成功后只保存 NekoAuth 签发的 access token，并用 `sub` 关联本地业务资料。
- 注册：新客户端不再提供邮箱、密码或验证码表单，统一打开 NekoAuth 注册页；Android 使用应用内 WebView，Web 与 PC 使用浏览器。
- 账号资料与安全：昵称、邮箱、密码、邮箱验证和登录设备统一在 NekoAuth 账号中心修改；NekoMusic 资料页只展示本地业务资料并提供账号中心入口。
- 头像与 VIP：头像由 NekoAuth 账号中心统一维护，NekoMusic 的后端头像 API 只代理 Auth；VIP 状态和到期时间继续由 NekoMusic 本地维护。
- 旧客户端兼容：邮箱密码、注册、验证码与找回密码接口只为旧客户端保留，NekoMusic 后端转发给 NekoAuth；新客户端不得再接入这些表单。
- 扫码登录：手机端确认时用当前有效 NekoAuth token 换取新的长期 token，PC 端仍通过原有一次性 `deliveryKey` 领取，旧扫码接口字段保持不变。
- 登出：NekoMusic 调用 NekoAuth `/oauth2/revoke` 吊销当前 access token；本地 Redis 不保留用户 token 副本。
- 管理员创建/重置用户：NekoMusic 先校验管理员权限，再调用 NekoAuth 的管理员桥接接口；管理员只调整 VIP 等本地资料时不会改 NekoAuth 密码。

桥接不可用或客户端配置错误时，旧 API 返回既有格式的错误响应；需要等待时保留 NekoAuth 的 `Retry-After` 语义。日志只记录操作类型、状态与用户 ID，不记录密码、验证码或 client secret。

## 本地前端开发

```bash
cd frontend
npm install
npm run dev
```

dev server（默认 `http://localhost:5173`）会把 `/api`、`/version` 反向代理到本地后端，
前端与接口/音频/封面保持同源，**不需要后端为浏览器放开 CORS**。代理目标默认取
`frontend/.env.development` 的 `VITE_DEV_PROXY_TARGET`（`http://localhost:65535`，
即后端默认端口）；需要指向别的地址（例如线上 `https://music.nekocore.cn`）时，
新建 `frontend/.env.development.local` 覆盖即可（该文件不入库）。


## 本地听歌识曲（后端）

后端提供 `POST /api/music/recognize`，接收 `multipart/form-data` 的 `audio` 字段。服务端使用本地 FFmpeg 将录音转换为 PCM，并用本站 `Music/music/{id}.*` 曲库建立声纹索引；不会调用第三方识曲 API，也不会把音频转发到外部服务。服务启动时会主动预热索引；单曲声纹及可直接恢复的全曲库倒排索引持久化在 `Music/.fingerprints/`，音频或曲库元数据变更后会在后台重建并原子切换，重建期间继续使用上一份可用索引。

示例：

```bash
curl -sS -F 'audio=@sample.m4a' https://music.example.com/api/music/recognize
```

识别结果在 `data` 中返回歌曲 ID、标题、歌手、专辑、置信度和匹配偏移；未匹配本站曲库时返回 `matched: false`。相关大小、时长、并发和限流参数可在管理后台「系统设置 → 听歌识曲」里调整（改动需重启后端）。

## 违禁词检测 API

将违禁词校验从上传、注册等业务中拆出，供前端在提交前预检标题、用户名、歌单名等文案。词表位于 `backend/src/main/resources/违禁词/`（`主词表.txt`、`英文词表.txt`、`白名单.txt`），与线上一致。

**基础 URL：** 与站点 API 相同，例如 `https://music.nekocore.cn`

**端点：** `POST /api/sensitive-word/check`

**认证：** 无需登录

**请求头：**

```
Content-Type: application/json
```

### 单条检测

**请求体：**

```json
{
  "text": "爱上你"
}
```

**响应示例（通过）：**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": false,
  "data": {
    "text": "爱上你",
    "contains": false,
    "words": []
  }
}
```

**响应示例（命中）：**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": true,
  "data": {
    "text": "示例文本",
    "contains": true,
    "words": ["命中词1"]
  }
}
```

`words` 为命中的违禁词片段列表（与上传接口拦截逻辑相同）。

### 批量检测

**请求体：**

```json
{
  "texts": ["歌曲标题", "歌手名", "专辑名"]
}
```

**响应示例：**

```json
{
  "success": true,
  "message": "检测完成",
  "contains": true,
  "data": [
    {
      "text": "歌曲标题",
      "contains": false,
      "words": []
    },
    {
      "text": "歌手名",
      "contains": true,
      "words": ["示例"]
    }
  ]
}
```

顶层 `contains` 表示是否**任意一条**命中；`data` 中每项结构同单条检测。

### 限制与错误

| 项 | 说明 |
|----|------|
| 单条长度 | 最多 2000 字符 |
| 批量条数 | 最多 20 条 |
| 空白 | 检测前会去除空格与零宽字符（与后端其它校验一致） |

**错误响应：**

```json
{
  "message": "请提供 text 或 texts 字段"
}
```

HTTP 状态码：`400` 参数错误。

### curl 示例

```bash
curl -s -X POST 'https://music.nekocore.cn/api/sensitive-word/check' \
  -H 'Content-Type: application/json' \
  -d '{"text":"爱上你"}'
```

```bash
curl -s -X POST 'https://music.nekocore.cn/api/sensitive-word/check' \
  -H 'Content-Type: application/json' \
  -d '{"texts":["标题","歌手"]}'
```

### 说明

- 本接口仅做检测，不写库；实际上传、注册、歌单等接口仍会再次校验。
- 修改词表后需重新部署后端 jar 后生效。

## 歌曲评论

每首歌都有评论与楼层回复（Web 播放页顶栏的评论按钮打开；回复再回复仍归入同一楼层并标记 @ 对象）。接口只有 **一个端点** `/api/comments`：

- `GET /api/comments?musicId=&page=&pageSize=` 列表（含回复）
- `POST /api/comments` `{musicId, content, parentId?}` 发表 / 回复（需登录）
- `DELETE /api/comments?id=` 删除自己的评论（管理员可删任意一条）

删除是**物理删除**：删楼层会连同该楼层下的全部回复一起删掉，列表里不会再出现「该评论已删除」这类占位。

评论返回发表时间（东八区墙钟）、IP 归属地与作者，头像由客户端按 `/api/user/avatar/{userId}` 取。完整字段与示例见 [Neko歌姬计划文档/README.md](Neko歌姬计划文档/README.md#歌曲评论-api)。

### IP 归属地（本地 ASN 优先 + MaxMind / qqwry 兜底，v4/v6）

归属地**不使用任何第三方接口**：先查询本地 ASN 库，按省级运营商 ASN（如 `AS56045` → 江西）确定省份；全国性 ASN 或未命中时，回退到 MaxMind 地理库判国家与省市，IPv4 再由 qqwry 补充。默认随 JAR 分发免费的 GeoLite2 兜底库，运行目录没有库文件时会在启动阶段自动释放，磁盘不可写时退回内存读取：

- `backend/src/main/resources/GeoLite2-City.mmdb`（GeoLite2 City，v4/v6）
- `backend/src/main/resources/GeoLite2-ASN.mmdb`（GeoLite2 ASN，v4/v6）
- `backend/src/main/resources/GeoIP/asn_regions.tsv`（省级运营商 ASN → 省份映射）
- `backend/src/main/resources/GeoIP/qqwry.dat`（纯真，IPv4）

地理库和 ASN 库会依次查找 `GeoIP/…`（运行目录）、`/app/GeoIP/…`、系统 GeoIP 目录和 JAR 内资源；qqwry 同样优先读取 `GeoIP/`（容器内 `/app/GeoIP/`），缺失时再从 JAR 加载。运维可把 MaxMind 商业版数据库放到上述位置覆盖内置版本，代码按以下优先级选择：

- 地理库：`GeoIP2-Enterprise.mmdb` → `GeoIP2-City.mmdb` → `GeoIP2-Country.mmdb` → 对应的 `GeoLite2-*`
- ASN 库：`GeoIP2-ISP.mmdb`（读取其中的 ASN 字段）→ `GeoLite2-ASN.mmdb`

商业版数据库需要自行购买并遵守 MaxMind 授权，**不要提交到仓库或公开构建产物**；把文件挂载到 `GeoIP/` 或 `/app/GeoIP/` 即可在启动时优先加载。ASN 映射只收录省级运营商网络，全国性 ASN（如中国移动 `9808`、电信 `4134`、联通 `4837`）不会强行映射到注册地。

展示规则：国内普通省份只显示省级行政区（如 `江西`、`广东`），不显示普通地级市；北京、上海、天津、重庆因本身就是省级行政区，显示为 `北京` / `上海` / `天津` / `重庆`。港澳台统一带「中国」前缀（`中国香港` / `中国澳门` / `中国台湾`）；国外只显示国家名，不显示省 / 市；内网 / 回环地址显示「本地」；国内省级信息解析不到时显示「中国」，所有来源都无法识别时显示「其他」。数据库缺失时服务照常运行。`ip_region` 是评论发表时的快照，历史评论不会随数据库升级自动重算，但历史 `unknown` / `未知` 会在接口输出时显示为「其他」。原始客户端 IP 另存于 `music_comments.client_ip`，仅用于服务端复核与后续重新解析，不通过评论接口返回。

## 每日推荐（Redis）

后端按用户收藏风格做规则化召回与排序，生成每日推荐列表，不依赖任何外部服务。

### 核心行为

- 每天 **UTC+8 00:00** 自动执行全量日更任务（服务启动后注册定时器）。
- 推荐结果只存 **Redis**（Redis-only），不依赖 MySQL 推荐结果表。
- **已收藏歌曲硬过滤**：推荐列表不会出现 `user_favorites` 中的曲目。
- **歌单内歌曲降权**：用户自建歌单、已收藏歌单中的曲目仍可能入选，但规则打分降低并在排序中靠后。
- 若当天缓存不存在，接口会按需即时生成并写回 Redis。

### 配置项（管理后台「系统设置 → 每日推荐」）

- `recommendation.daily_limit`：每个用户每天产出的推荐条数（默认 300）

### 存储设计（Redis）

- Key：`daily_reco:{yyyy-MM-dd}:{userId}`
- Value：JSON 数组（包含 `rank/musicId/title/artist/album/language/tags/score/source/reason`）
- TTL：72 小时（跨日容错）

### 接口

- `GET /api/user/recommendations/daily`
  - 需要用户登录 token（`Authorization`）
  - 返回当天推荐结果
- `GET /api/user/recommendations/daily?refresh=true`
  - 强制重算当前用户当天推荐并覆盖 Redis

### 返回示例

```json
{
  "success": true,
  "date": "2026-05-28",
  "count": 30,
  "data": [
    {
      "rank": 1,
      "musicId": 13751,
      "title": "天使ロード中…^_−☆",
      "artist": "三Z-STUDIO&HOYO-MiX",
      "album": "绝区零-天使加载中…^_−☆",
      "language": "日语",
      "tags": "二次元，日语，游戏",
      "score": 4.93,
      "source": "rule",
      "reason": "基于收藏风格匹配"
    }
  ]
}
```

### 运维说明

- Redis 需开启持久化（RDB 或 AOF），避免重启后丢失当日推荐缓存。
- `daily_limit` 提升会线性增加候选召回量与响应体大小。

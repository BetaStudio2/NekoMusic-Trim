import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { installRequestHeaders } from './config/requestHeaders.js'
import { installAdminUnauthorizedHandler } from './utils/adminUnauthorized.js'
import './assets/main.css'
import './design/tokens.css'
import './design/reset.css'
import VueToastification from 'vue-toastification'
import 'vue-toastification/dist/index.css'
import { installBenignPlayAbortGuard } from './utils/benignPlayAbort'
import { clearUser, getToken, setUser } from './utils/userStore.js'
import { initPwa } from './composables/usePwa.js'
import { installI18n, t } from './i18n'
import API_CONFIG from './config/apiConfig.js'

const SHARED_SESSION_TOKEN_PREFIX = 'nekosso_'
const AUTH_SESSION_HINT_STORAGE_KEY = 'nekoAuthSessionHint'
const AUTH_SHARED_REDIRECT_STORAGE_KEY = 'nekoAuthSharedRedirectAt'
const AUTH_SHARED_REDIRECT_COOLDOWN_MS = 5 * 60_000
let sharedSessionRefreshInFlight = false

/**
 * 首屏骨架（index.html 的 #app-boot）在 JS 执行前就已可见，其中文案写死为中文。
 * 这里在挂载前按已确定的语言替换品牌名与提示语；Vue 一旦挂载，整个 #app 会被替换掉。
 */
function applyBootSplashLocale() {
  const brand = document.querySelector('#app-boot .app-splash__brand')
  const hint = document.querySelector('#app-boot .app-splash__hint')
  if (brand) brand.textContent = t('common.appName')
  if (hint) hint.textContent = t('common.loadingApp')
}

const app = createApp(App)

// 前端多语言：注册全局 $t，并按浏览器/用户偏好确定初始语言。
// 必须早于任何视图创建——顶栏、路由标题都直接读它。
installI18n(app)
applyBootSplashLocale()

// 所有 fetch / XHR / axios 请求统一补语言与用户令牌
installRequestHeaders()

// 管理员会话失效：后台管理请求返回 401 时清理登录态并回到登录页（须在最外层请求补丁之后安装）
installAdminUnauthorizedHandler(router)

// PWA：尽早注册安装提示监听与 Service Worker（仅生产注册 SW）
initPwa()

/**
 * 过滤 vue-toastification@2.0.0-rc.5 的上游告警
 * ------------------------------------------------------------
 * 该 rc 版（2021 年，已停止维护）在渲染吐司容器时会报
 *   Property "positions" was accessed during render but is not defined on instance
 * 其源码 data() 里确实声明了 positions，属该版本与新版 Vue 的兼容性问题，
 * 功能正常。
 *
 * 注意：不能用 app.config.warnHandler —— 该库内部【自建了一个 Vue app 实例】
 * 来渲染吐司容器（dist 里有独立的 createApp/render），因此拿不到主 app 的
 * config，只能在 console.warn 这一层拦。
 * 匹配串足够具体，不会掩盖其它警告；生产构建不生效。
 */
if (import.meta.env.DEV) {
  const rawWarn = console.warn.bind(console)

  /**
   * AMLL 的 WebGL1 背景渲染器在初始化时会去找这几个【WebGL2 专有】扩展名：
   *   EXT_color_buffer_float / EXT_float_blend / OES_texture_float_linear / OES_texture_float
   * 但它的上下文本来就是 canvas.getContext('webgl')（WebGL1），必然取不到，
   * 于是每次进播放页都刷一遍 "… not supported"。这些扩展与 WebGL1 的渲染
   * 路径无关，属上游误报；降级与否由 useLiteMode 的 WebGL1 探测决定，
   * 这里只负责别让它们污染控制台。
   */
  const AMLL_WEBGL1_BENIGN_WARNINGS = [
    'EXT_color_buffer_float not supported',
    'EXT_float_blend not supported',
    'OES_texture_float_linear not supported',
    'OES_texture_float not supported',
  ]

  console.warn = (...args) => {
    const first = args[0]
    if (typeof first === 'string') {
      if (first.includes('Property "positions" was accessed during render')) {
        return
      }
      if (AMLL_WEBGL1_BENIGN_WARNINGS.some((msg) => first.includes(msg))) {
        return
      }
    }
    rawWarn(...args)
  }
}

/**
 * 收敛「play() 被新的 load 请求打断」的 AbortError（详见该模块内注释）。
 * 应用层的 play() 已全部经 GlobalPlayer 的 safePlay 收敛，这里是最后一道兜底。
 */
installBenignPlayAbortGuard()

app.use(router)
app.use(VueToastification, {
  position: 'top-right',
  timeout: 3000,
  // 与 useToast 的限量保持一致：即使有页面直接走 raw 接口，也不会把吐司堆爆
  maxToasts: 4,
  newestOnTop: true,
  closeOnClick: true,
  pauseOnFocusLoss: true,
  pauseOnHover: true,
  draggable: true,
  showCloseButtonOnHover: false,
  hideProgressBar: false,
  icon: true,
  rtl: false
})

function clearLocalSession() {
  const previous = getToken()
  localStorage.removeItem('userToken')
  localStorage.removeItem('userIdToken')
  localStorage.removeItem(AUTH_SESSION_HINT_STORAGE_KEY)
  clearUser()
  if (!previous) return
  window.dispatchEvent(new StorageEvent('storage', {
    key: 'userToken',
    oldValue: previous,
    newValue: null,
  }))
}

function isSharedSessionToken(token) {
  return typeof token === 'string' && token.startsWith(SHARED_SESSION_TOKEN_PREFIX)
}

function readAuthSessionHint() {
  if (typeof document === 'undefined') return ''
  const prefix = 'nekoauth_session_hint='
  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : ''
}

/**
 * Auth 已有浏览器会话、但尚未写入共享会话 Cookie 时，经 Auth 登录页完成一次
 * 普通回跳。Auth 会在 bootstrap 刷新时补发共享 Cookie；这不是 OIDC 授权。
 */
async function redirectToAuthForSharedSession() {
  if (!readAuthSessionHint()) return false
  try {
    const lastAttempt = Number(sessionStorage.getItem(AUTH_SHARED_REDIRECT_STORAGE_KEY) || 0)
    if (Date.now() - lastAttempt < AUTH_SHARED_REDIRECT_COOLDOWN_MS) return false
    sessionStorage.setItem(AUTH_SHARED_REDIRECT_STORAGE_KEY, String(Date.now()))
  } catch {
    return false
  }

  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const returnTo = current.startsWith('/oauth/nekocore/') || current.startsWith('/sso/') ? '/' : current
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/sso/login-entry`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store',
      body: JSON.stringify({ client: 'web', returnTo: returnTo || '/' }),
    })
    const body = await response.json().catch(() => ({}))
    const url = body?.data?.url
    if (response.ok && body?.success && typeof url === 'string' && /^https?:\/\//i.test(url)) {
      window.location.assign(url)
      return true
    }
  } catch {
    /* Auth 暂时不可用时保留当前未登录状态，稍后再试 */
  }
  sessionStorage.removeItem(AUTH_SHARED_REDIRECT_STORAGE_KEY)
  return false
}

/** 当前用户的 OAuth access token 是否仍被 Auth 接受。 */
async function validateSharedSession() {
  const token = getToken()
  if (!token) return false
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/info`, {
      credentials: 'include',
      cache: 'no-store',
    })
    if (response.status === 401 || response.status === 403) {
      clearLocalSession()
      // 本地共享令牌可能因 Redis 重启或会话轮换失效，但 Auth 浏览器会话
      // 仍有效；立即重新兑换一次，避免要等到下一轮轮询才恢复登录态。
      return syncSharedSsoLogin()
    }
    const body = await response.json().catch(() => ({}))
    if (response.ok && body?.success && body?.data?.user) {
      // 只校验状态码会让“Token 已恢复、资料仍为空”的页面进入半登录态，
      // 账号页会把它误判为未登录并反复跳 Auth。这里必须同时恢复内存资料。
      setUser(body.data.user)
    }
    return response.ok
  } catch {
    return true
  }
}

async function refreshSharedSessionIfVisible() {
  if (document.visibilityState !== 'visible') return
  if (sharedSessionRefreshInFlight) return
  sharedSessionRefreshInFlight = true
  try {
    const token = getToken()
    if (isSharedSessionToken(token)) {
      const hint = readAuthSessionHint()
      if (!hint) {
        // Auth 清除了父域提示 Cookie，说明共享会话已经退出；不要等到业务
        // 请求失败后才让顶栏回到未登录状态。
        clearLocalSession()
        return
      }
      if (localStorage.getItem(AUTH_SESSION_HINT_STORAGE_KEY) !== hint) {
        await syncSharedSsoLogin()
        return
      }
      await validateSharedSession()
    } else if (!token) {
      await syncSharedSsoLogin()
    } else {
      // 旧页面可能还持有 OIDC access token；看到父域登录提示后主动换成
      // 共享会话令牌，让已有用户也立即进入新的跨子域退出链路。
      const hint = readAuthSessionHint()
      if (hint && localStorage.getItem(AUTH_SESSION_HINT_STORAGE_KEY) !== hint) {
        await syncSharedSsoLogin()
      } else {
        await validateSharedSession()
      }
    }
  } finally {
    sharedSessionRefreshInFlight = false
  }
}

/** 读取父域共享会话，直接恢复 Web 登录态；不进入 OIDC 授权流程。 */
async function syncSharedSsoLogin() {
  const previousToken = getToken()
  const hint = readAuthSessionHint()
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/sso/shared-session`, {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    })
    const body = await response.json().catch(() => ({}))
    const token = body?.data?.token
    const user = body?.data?.user
    if (response.ok && body?.success && typeof token === 'string' && token && user) {
      localStorage.setItem('userToken', token)
      if (hint) localStorage.setItem(AUTH_SESSION_HINT_STORAGE_KEY, hint)
      else localStorage.removeItem(AUTH_SESSION_HINT_STORAGE_KEY)
      sessionStorage.removeItem(AUTH_SHARED_REDIRECT_STORAGE_KEY)
      setUser(user)
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'userToken',
        oldValue: previousToken,
        newValue: token,
      }))
      return true
    }
    if ((response.status === 401 || response.status === 403)
        && previousToken
        && isSharedSessionToken(previousToken)) {
      clearLocalSession()
    }
    if (response.status === 401 || response.status === 403) {
      await redirectToAuthForSharedSession()
    }
  } catch {
    /* Auth 暂时不可用时保留当前本地登录态 */
  }
  return false
}

/**
 * 昵称等用户资料不再写进 localStorage：已登录时先拉一次
 * GET /api/user/info 再挂载，保证首屏各页面读到的都是服务端最新资料。
 * 网络慢最多等 3 秒，拉不到就照常挂载（页面会显示兜底提示）。
 */
async function bootstrap() {
  const token = getToken()
  if (isSharedSessionToken(token)) {
    const hint = readAuthSessionHint()
    if (!hint) {
      clearLocalSession()
    } else if (localStorage.getItem(AUTH_SESSION_HINT_STORAGE_KEY) !== hint) {
      await Promise.race([
        syncSharedSsoLogin(),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ])
    } else {
      await Promise.race([
        validateSharedSession(),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ])
    }
  } else if (!token) {
    await syncSharedSsoLogin()
  } else {
    const hint = readAuthSessionHint()
    if (hint && localStorage.getItem(AUTH_SESSION_HINT_STORAGE_KEY) !== hint) {
      await Promise.race([
        syncSharedSsoLogin(),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ])
    } else {
      await Promise.race([
        validateSharedSession(),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ])
    }
  }
  document.addEventListener('visibilitychange', refreshSharedSessionIfVisible)
  window.addEventListener('focus', refreshSharedSessionIfVisible)
  window.addEventListener('pageshow', refreshSharedSessionIfVisible)
  window.setInterval(refreshSharedSessionIfVisible, 60_000)
  app.mount('#app')
}

bootstrap()

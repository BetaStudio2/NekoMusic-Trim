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

/** Auth 登录后写入父域共享会话 Cookie；子域后端直接兑换登录态，不发生跳转。 */
async function syncSharedSsoLogin() {
  const previousToken = getToken()
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/sso/cookie-login`, {
      method: 'POST',
      cache: 'no-store',
    })
    const body = await response.json().catch(() => ({}))
    const token = body?.data?.token
    const user = body?.data?.user
    if (response.ok && body?.success && typeof token === 'string' && token && user) {
      localStorage.setItem('userToken', token)
      setUser(user)
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'userToken',
        oldValue: previousToken,
        newValue: token,
      }))
      return true
    }
    if (response.status === 401 && previousToken) {
      localStorage.removeItem('userToken')
      clearUser()
      window.dispatchEvent(new StorageEvent('storage', {
        key: 'userToken',
        oldValue: previousToken,
        newValue: null,
      }))
    }
  } catch {
    /* Auth 暂时不可用时保留当前本地登录态 */
  }
  return false
}

async function refreshSharedSsoSessionIfVisible() {
  if (document.visibilityState !== 'visible' || !getToken()) return
  await syncSharedSsoLogin()
}

/**
 * 昵称等用户资料不再写进 localStorage：已登录时先拉一次
 * GET /api/user/info 再挂载，保证首屏各页面读到的都是服务端最新资料。
 * 网络慢最多等 3 秒，拉不到就照常挂载（页面会显示兜底提示）。
 */
async function bootstrap() {
  await Promise.race([
    syncSharedSsoLogin(),
    new Promise((resolve) => setTimeout(resolve, 3000)),
  ])
  document.addEventListener('visibilitychange', refreshSharedSsoSessionIfVisible)
  window.setInterval(refreshSharedSsoSessionIfVisible, 60_000)
  app.mount('#app')
}

bootstrap()

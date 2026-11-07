/**
 * useAuthDialog —— 全局登录弹窗开关
 * ------------------------------------------------------------
 * 兼容旧调用名：现在不再打开本地弹窗，而是直接进入 NekoAuth。
 */
import { ref, readonly } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'

const isOpen = ref(false)
const initialTab = ref('login')
let redirecting = false

/** 直接跳转 NekoAuth；tab='register' 时打开 Auth 注册页。 */
export async function openAuthDialog(tab = 'login') {
  if (redirecting || typeof window === 'undefined') return
  redirecting = true
  initialTab.value = tab === 'register' ? 'register' : 'login'
  isOpen.value = false
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const returnTo = current.startsWith('/oauth/nekocore/') || current.startsWith('/sso/') ? '/' : current
  if (tab !== 'register') {
    window.location.assign(`/sso/nekocore/start?return_to=${encodeURIComponent(returnTo || '/')}`)
    return
  }
  try {
    const response = await fetch(
      `${API_CONFIG.BASE_URL}/api/user/sso/portal-url?target=register`,
      { cache: 'no-store' }
    )
    const body = await response.json().catch(() => ({}))
    const url = body?.data?.url
    if (response.ok && body?.success && typeof url === 'string' && /^https?:\/\//i.test(url)) {
      window.location.assign(url)
      return
    }
  } catch {
    /* 失败时回到 Auth 登录入口 */
  }
  window.location.assign(`/sso/nekocore/start?return_to=${encodeURIComponent(returnTo || '/')}`)
}

export function closeAuthDialog() {
  isOpen.value = false
}

export function useAuthDialog() {
  return {
    isOpen: readonly(isOpen),
    initialTab: readonly(initialTab),
    openAuthDialog,
    closeAuthDialog,
  }
}

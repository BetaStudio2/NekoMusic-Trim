/**
 * userStore —— 内存中的用户资料
 * ------------------------------------------------------------
 * 本地只持久化 Token（localStorage.userToken）；昵称、邮箱、会员等资料
 * 不再写进 localStorage，登录/刷新时用 GET /api/user/info 拉取，
 * 避免本地缓存过期（改了昵称但页面还显示旧的）。
 *
 * 资料变化时会派发 key 为 'user' 的 StorageEvent，
 * 沿用各页面既有的 storage 事件刷新逻辑，无需额外改动。
 */
import { ref } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'

/** 用户资料变化的额外事件（与 storage 事件互补，便于新代码使用） */
export const USER_INFO_CHANGED_EVENT = 'neko-user-info-changed'

/** 响应式用户资料（仅内存），未登录或未拉到时为 null */
export const user = ref(null)

let loaded = false
let loadedToken = null
let loadInFlight = null
let loadInFlightToken = null

/** 已登录时返回持久化的 Token，未登录返回 null */
export function getToken() {
  return typeof window === 'undefined' ? null : localStorage.getItem('userToken')
}

/** 同步读取内存中的用户资料 */
export function getUser() {
  return user.value
}

/** 响应式读取：`const user = useUser()` */
export function useUser() {
  return user
}

function notify(previousValue) {
  if (typeof window === 'undefined') return
  const nextValue = user.value ? JSON.stringify(user.value) : null
  window.dispatchEvent(
    new StorageEvent('storage', { key: 'user', oldValue: previousValue, newValue: nextValue })
  )
  window.dispatchEvent(new CustomEvent(USER_INFO_CHANGED_EVENT, { detail: user.value }))
}

/** 覆盖内存中的用户资料（不落盘） */
export function setUser(next) {
  const previous = user.value ? JSON.stringify(user.value) : null
  user.value = next || null
  loaded = !!next
  loadedToken = next ? getToken() : null
  notify(previous)
}

/** 局部更新内存中的用户资料（不落盘），如改昵称、VIP 合并 */
export function patchUser(patch) {
  if (!user.value || !patch) return
  const previous = JSON.stringify(user.value)
  user.value = { ...user.value, ...patch }
  notify(previous)
}

/** 清空内存中的用户资料（登出） */
export function clearUser() {
  const previous = user.value ? JSON.stringify(user.value) : null
  user.value = null
  loaded = false
  loadedToken = null
  notify(previous)
}

/**
 * 用 Token 拉取最新用户资料。
 * 网络异常时保留内存中已有数据，401 时不做处理（保留 Token，由页面自行提示）。
 * @param {{ force?: boolean }} options force=true 时忽略「本次已加载」标记重新拉取
 */
export async function loadUserInfo({ force = false } = {}) {
  const token = getToken()
  if (!token) {
    clearUser()
    return null
  }
  // 首屏、顶栏和账号页会同时请求资料；合并成一次请求，避免重复触发
  // NekoAuth 共享会话校验与 IP 限流。
  if (loadInFlight) {
    if (loadInFlightToken === token) return loadInFlight
    try {
      await loadInFlight
    } catch {
      /* 旧请求失败不影响新 Token 的加载 */
    }
    if (getToken() !== token) return null
  }
  if (loaded && loadedToken === token && !force) return user.value

  loadInFlight = (async () => {
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/info`, {
        method: 'GET',
        credentials: 'include',
        headers: { Authorization: token }
      })
      const data = await response.json().catch(() => ({}))
      if (getToken() === token && response.ok && data?.success && data?.data?.user) {
        setUser(data.data.user)
      }
    } catch {
      /* 网络异常：保留内存中已有资料 */
    }
    return user.value
  })()
  loadInFlightToken = token

  try {
    return await loadInFlight
  } finally {
    if (loadInFlightToken === token) {
      loadInFlight = null
      loadInFlightToken = null
    }
  }
}

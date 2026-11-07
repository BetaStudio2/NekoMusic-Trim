import API_CONFIG from '@/config/apiConfig.js'

/**
 * 会话登出：先让服务端吊销当前令牌，再清本地状态。
 *
 * 只清 localStorage 不算登出——令牌在服务端仍有效到 TTL 到期，被复制走的令牌会继续可用。
 * 本模块只负责发起吊销请求：网络失败或令牌已失效都不应阻塞本地登出（下次联网时会补上）。
 */

async function postLogout(path, token) {
  if (!token) return false
  try {
    const res = await fetch(`${API_CONFIG.BASE_URL}${path}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })
    return res.ok
  } catch {
    return false
  }
}

/** 吊销当前用户会话（localStorage.userToken）。 */
export function logoutUserSession() {
  const token = typeof window === 'undefined' ? null : localStorage.getItem('userToken')
  return postLogout('/api/user/logout', token)
}

/** 全局退出：使用标准 OIDC RP-Initiated Logout 退出 Auth 浏览器会话。 */
export async function logoutUserGlobally(tokenOverride = null) {
  if (typeof window === 'undefined') return false
  const token = tokenOverride || localStorage.getItem('userToken')
  await postLogout('/api/user/logout', token)
  const idToken = localStorage.getItem('userIdToken')
  if (!idToken) return false
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/sso/logout-url`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    })
    const body = await response.json().catch(() => ({}))
    const url = body?.data?.url
    if (response.ok && body?.success && typeof url === 'string' && /^https?:\/\//i.test(url)) {
      window.location.assign(url)
      return true
    }
  } catch {
    /* 仅吊销当前 access token */
  }
  return false
}

/** 吊销当前管理员会话（localStorage.adminToken）。 */
export function logoutAdminSession() {
  const token = typeof window === 'undefined' ? null : localStorage.getItem('adminToken')
  return postLogout('/api/admin/logout', token)
}

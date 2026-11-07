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

/** 全局退出：NekoMusic 后端会同时撤销 Auth 的父域共享会话。 */
export async function logoutUserGlobally(tokenOverride = null) {
  if (typeof window === 'undefined') return false
  const token = tokenOverride || localStorage.getItem('userToken')
  await postLogout('/api/user/logout', token)
  return false
}

/** 吊销当前管理员会话（localStorage.adminToken）。 */
export function logoutAdminSession() {
  const token = typeof window === 'undefined' ? null : localStorage.getItem('adminToken')
  return postLogout('/api/admin/logout', token)
}

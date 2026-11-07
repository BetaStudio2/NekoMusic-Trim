import API_CONFIG from '@/config/apiConfig.js'
import { AUTH_ID_TOKEN_STORAGE_KEY } from '@/utils/nekoAuthSilent.js'

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

/**
 * 全局退出：先吊销 NekoMusic 当前 access token，再用 OIDC RP-Initiated Logout
 * 以 POST 表单注销 NekoAuth 浏览器会话。id_token_hint 放表单体，不进入 URL 或历史记录。
 */
export async function logoutUserGlobally(tokenOverride = null) {
  if (typeof window === 'undefined') return false
  const token = tokenOverride || localStorage.getItem('userToken')
  const idToken = localStorage.getItem(AUTH_ID_TOKEN_STORAGE_KEY) || ''
  const revoke = postLogout('/api/user/logout', token)
  let config = null
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/sso/logout-config`, {
      cache: 'no-store',
    })
    const body = await response.json().catch(() => ({}))
    if (response.ok && body?.success) config = body.data
  } catch {
    /* 配置不可用时仍执行本地 token 吊销 */
  }

  if (config?.endpoint && config?.clientId && config?.postLogoutRedirectUri) {
    let endpoint
    try {
      endpoint = new URL(String(config.endpoint))
      if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') throw new Error('invalid')
    } catch {
      endpoint = null
    }
    if (endpoint) {
      await revoke
      localStorage.removeItem(AUTH_ID_TOKEN_STORAGE_KEY)
      const form = document.createElement('form')
      form.method = 'POST'
      form.action = endpoint.toString()
      form.style.display = 'none'
      const fields = {
        client_id: String(config.clientId),
        post_logout_redirect_uri: String(config.postLogoutRedirectUri),
        ...(idToken ? { id_token_hint: idToken } : {}),
      }
      for (const [name, value] of Object.entries(fields)) {
        const input = document.createElement('input')
        input.type = 'hidden'
        input.name = name
        input.value = value
        form.appendChild(input)
      }
      document.body.appendChild(form)
      form.submit()
      return true
    }
  }

  await revoke
  localStorage.removeItem(AUTH_ID_TOKEN_STORAGE_KEY)
  return false
}

/** 吊销当前管理员会话（localStorage.adminToken）。 */
export function logoutAdminSession() {
  const token = typeof window === 'undefined' ? null : localStorage.getItem('adminToken')
  return postLogout('/api/admin/logout', token)
}

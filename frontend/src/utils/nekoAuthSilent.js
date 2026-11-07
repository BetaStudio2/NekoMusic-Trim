/** NekoAuth 父域会话提示；仅用于决定是否尝试 prompt=none，不是认证凭据。 */
export const AUTH_SESSION_HINT_COOKIE = 'nekoauth_session_hint'
export const AUTH_ID_TOKEN_STORAGE_KEY = 'nekoAuthIdToken'

const SILENT_ATTEMPT_KEY = 'nekoauthSilentHint'

export function readAuthSessionHint() {
  if (typeof document === 'undefined') return ''
  const prefix = `${AUTH_SESSION_HINT_COOKIE}=`
  for (const part of document.cookie.split(';')) {
    const item = part.trim()
    if (!item.startsWith(prefix)) continue
    try {
      return decodeURIComponent(item.slice(prefix.length))
    } catch {
      return item.slice(prefix.length)
    }
  }
  return ''
}

export function silentAttemptedFor(hint) {
  if (!hint) return false
  try {
    return localStorage.getItem(SILENT_ATTEMPT_KEY) === hint
  } catch {
    return false
  }
}

/**
 * 同一 Auth 会话只自动尝试一次。新的 Auth 登录会生成新的提示值，
 * 因此用户重新登录后无需手动清理该标记。
 */
export function rememberSilentAttempt(hint) {
  if (!hint) return
  try {
    localStorage.setItem(SILENT_ATTEMPT_KEY, hint)
  } catch {
    /* 隐私模式下只影响去重，不影响登录 */
  }
}

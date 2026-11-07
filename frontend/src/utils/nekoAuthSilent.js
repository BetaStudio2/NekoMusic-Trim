/** NekoAuth 父域会话提示；仅用于决定是否尝试 prompt=none，不是认证凭据。 */
export const AUTH_SESSION_HINT_COOKIE = 'nekoauth_session_hint'
export const AUTH_ID_TOKEN_STORAGE_KEY = 'nekoAuthIdToken'

// V2：旧版会永久记住一次静默失败，导致 Auth 修复或重新登录后仍不再尝试。
const SILENT_ATTEMPT_KEY = 'nekoauthSilentAttemptV2'
const EXPLICIT_LOGOUT_KEY = 'nekoauthExplicitLogoutHintV2'
const SILENT_RETRY_COOLDOWN_MS = 60_000

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
    if (localStorage.getItem(EXPLICIT_LOGOUT_KEY) === hint) return true
    const value = localStorage.getItem(SILENT_ATTEMPT_KEY)
    if (!value) return false
    const attempt = JSON.parse(value)
    return attempt?.hint === hint
      && Number.isFinite(attempt?.at)
      && Date.now() - attempt.at < SILENT_RETRY_COOLDOWN_MS
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
    localStorage.setItem(SILENT_ATTEMPT_KEY, JSON.stringify({ hint, at: Date.now() }))
  } catch {
    /* 隐私模式下只影响去重，不影响登录 */
  }
}

/** 用户主动退出后，同一个 Auth 会话不得被静默登录立刻拉回。 */
export function rememberExplicitLogout(hint) {
  if (!hint) return
  try {
    localStorage.setItem(EXPLICIT_LOGOUT_KEY, hint)
  } catch {
    /* 隐私模式下只影响去重，不影响退出 */
  }
}

// 通用请求头（Web）
// ------------------------------------------------------------
// 所有发往本站后端的请求统一携带：
//   - Accept-Language: <当前界面语言>
//   - Authorization: Bearer <用户令牌>（已登录且调用方未显式设置时）
import axios from 'axios'
import { locale } from '@/i18n'
import API_CONFIG from './apiConfig.js'

export const ACCEPT_LANGUAGE_HEADER = 'Accept-Language'
export const AUTHORIZATION_HEADER = 'Authorization'

const USER_TOKEN_STORAGE_KEY = 'userToken'
let installed = false

function requestHeaders() {
  return {
    [ACCEPT_LANGUAGE_HEADER]: locale.value,
  }
}

function userAuthorizationValue() {
  if (typeof window === 'undefined' || !window.localStorage) return ''
  try {
    const token = window.localStorage.getItem(USER_TOKEN_STORAGE_KEY)
    return token ? `Bearer ${token}` : ''
  } catch {
    return ''
  }
}

function shouldAttachHeader(url) {
  if (!url || typeof window === 'undefined') return false
  let target
  try {
    target = new URL(String(url), window.location.href)
  } catch {
    return false
  }
  if (target.origin === window.location.origin) return true
  const base = API_CONFIG.BASE_URL
  if (!base) return false
  try {
    return target.origin === new URL(base, window.location.href).origin
  } catch {
    return false
  }
}

function applyBaseRequestHeaders(headers) {
  for (const [name, value] of Object.entries(requestHeaders())) {
    if (!headers.has(name)) headers.set(name, value)
  }
  if (!headers.has(AUTHORIZATION_HEADER)) {
    const authorization = userAuthorizationValue()
    if (authorization) headers.set(AUTHORIZATION_HEADER, authorization)
  }
}

function patchFetch() {
  if (typeof window === 'undefined' || typeof window.fetch !== 'function') return
  const previousFetch = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const rawUrl =
      typeof input === 'string' || input instanceof URL
        ? String(input)
        : input instanceof Request
          ? input.url
          : ''
    if (!rawUrl || !shouldAttachHeader(rawUrl)) return previousFetch(input, init)
    try {
      const request = input instanceof Request ? new Request(input, init) : new Request(rawUrl, init)
      const headers = new Headers(request.headers)
      applyBaseRequestHeaders(headers)
      return previousFetch(new Request(request, { headers }))
    } catch (error) {
      console.warn('[client] 补客户端基础标头失败，按原请求发送', error)
      return previousFetch(input, init)
    }
  }
}

function patchXhr() {
  if (typeof window === 'undefined' || typeof XMLHttpRequest === 'undefined') return
  const proto = XMLHttpRequest.prototype
  const originalOpen = proto.open
  const originalSend = proto.send
  const originalSetRequestHeader = proto.setRequestHeader

  proto.open = function (method, url, ...rest) {
    this.__nekoRequestUrl = url
    this.__nekoRequestHeaders = new Set()
    return originalOpen.call(this, method, url, ...rest)
  }

  proto.setRequestHeader = function (name, value) {
    this.__nekoRequestHeaders = this.__nekoRequestHeaders || new Set()
    this.__nekoRequestHeaders.add(String(name).toLowerCase())
    return originalSetRequestHeader.call(this, name, value)
  }

  proto.send = function (...args) {
    if (shouldAttachHeader(this.__nekoRequestUrl)) {
      const set = this.__nekoRequestHeaders || new Set()
      for (const [name, value] of Object.entries(requestHeaders())) {
        if (!set.has(name.toLowerCase())) originalSetRequestHeader.call(this, name, value)
      }
      if (!set.has(AUTHORIZATION_HEADER.toLowerCase())) {
        const authorization = userAuthorizationValue()
        if (authorization) originalSetRequestHeader.call(this, AUTHORIZATION_HEADER, authorization)
      }
    }
    return originalSend.apply(this, args)
  }
}

/** 安装语言与用户令牌注入。 */
export function installRequestHeaders() {
  if (installed || typeof window === 'undefined') return
  installed = true
  for (const [name, value] of Object.entries(requestHeaders())) {
    axios.defaults.headers.common[name] = value
  }
  patchFetch()
  patchXhr()
}

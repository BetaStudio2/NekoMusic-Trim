// 播放地址解析
// ------------------------------------------------------------
// 后端 GET /api/music/file/{id} 返回 JSON：
//   {"success":true,"data":{"url":"/media/music/..."}}
// 该接口直接返回可播放地址，由前端按普通 JSON 请求处理。
//
// 返回的 /media/music/... 是固定地址、由 CDN 共享缓存，缓存语义不变。
import API_CONFIG from '@/config/apiConfig.js'
import { t } from '@/i18n'

/**
 * 解析指定音乐的可用媒体地址。
 * @param {number|string} musicId 音乐 ID
 * @param {{ quality?: string }} [options]
 * @returns {Promise<string>} 媒体地址（绝对 URL）
 */
export async function resolveMediaUrl(musicId, options = {}) {
  if (musicId == null || musicId === '') {
    throw new Error(t('api.musicIdRequired'))
  }
  const query = options.quality ? `?quality=${encodeURIComponent(options.quality)}` : ''
  const response = await fetch(
    `${API_CONFIG.BASE_URL}/api/music/file/${musicId}${query}`,
    { cache: 'no-store' }
  )
  if (!response.ok) {
    throw new Error(t('api.playUrlFailed', { status: response.status }))
  }
  const json = await response.json()
  const url = json?.data?.url
  if (!url) {
    throw new Error(t('api.playUrlMissing'))
  }
  return url.startsWith('http') ? url : `${API_CONFIG.BASE_URL}${url}`
}

export default resolveMediaUrl

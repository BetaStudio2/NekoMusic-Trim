import API_CONFIG from '@/config/apiConfig.js'

/**
 * 后台日志查询：访问日志（安全分析 / 访问量统计）与操作日志（管理动作留痕）。
 *
 * 只有管理后台使用；权限由服务端把关：
 *  - 访问日志：除普通用户外的角色均可查看（审核员及以上）
 *  - 操作日志：仅管理员及以上
 */

function adminAuthHeader() {
  const token = localStorage.getItem('adminToken')
  if (!token) {
    throw new Error('请先登录管理后台')
  }
  return { Authorization: `Bearer ${token}` }
}

async function readJson(res) {
  const data = await res.json().catch(() => ({}))
  if (!res.ok || !data.success) {
    throw new Error(data.message || `请求失败 (${res.status})`)
  }
  return data.data
}

/** 去掉空值，拼查询串 */
function toQuery(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') return
    search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

/**
 * 访问日志分页检索。
 * @param {{page?:number,size?:number,ip?:string,path?:string,status?:number,threatLevel?:string,from?:number,to?:number,keyword?:string}} params
 * @returns {Promise<{total:number,page:number,size:number,items:Array<object>}>}
 */
export async function fetchAccessLogs(params) {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/logs/access${toQuery(params)}`, {
    headers: { 'Content-Type': 'application/json', ...adminAuthHeader() }
  })
  return readJson(res)
}

/**
 * 访问量概览与聚合榜。
 * @param {number} days 统计窗口（天）
 */
export async function fetchAccessLogStats(days) {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/logs/access/stats${toQuery({ days })}`, {
    headers: { 'Content-Type': 'application/json', ...adminAuthHeader() }
  })
  return readJson(res)
}

/**
 * 操作日志分页检索。
 * @param {{page?:number,size?:number,adminId?:number,module?:string,from?:number,to?:number,keyword?:string}} params
 * @returns {Promise<{total:number,page:number,size:number,items:Array<object>}>}
 */
export async function fetchOperationLogs(params) {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/logs/operations${toQuery(params)}`, {
    headers: { 'Content-Type': 'application/json', ...adminAuthHeader() }
  })
  return readJson(res)
}

/**
 * 打开访问日志实时流（SSE）。
 *
 * EventSource 无法携带自定义请求头，因此令牌只能走查询串（服务端对该端点同时接受
 * `Authorization` 与 `?token=`）。连接最长存活 5 分钟，服务端到点主动断开，浏览器自带重连；
 * 实时只是「提前告知」，断线期间漏掉的行用列表接口补全即可。
 *
 * @param {(row: object) => void} [options.onRecord] 每来一条访问日志回调一次
 * @param {(state: 'connecting'|'open'|'error') => void} [options.onState] 连接状态变化
 * @returns {() => void} 关闭函数
 */
export function openAccessLogStream({ onRecord, onState } = {}) {
  const token = localStorage.getItem('adminToken')
  if (!token || typeof EventSource === 'undefined') {
    onState?.('error')
    return () => {}
  }
  const source = new EventSource(
    `${API_CONFIG.BASE_URL}/api/admin/logs/access/stream?token=${encodeURIComponent(token)}`
  )
  onState?.('connecting')
  source.addEventListener('ready', () => onState?.('open'))
  source.addEventListener('record', (event) => {
    try {
      const row = JSON.parse(event.data)
      if (row && typeof row === 'object') onRecord?.(row)
    } catch {
      // 单条坏帧不该打断整条流
    }
  })
  source.onerror = () => onState?.('error')
  return () => source.close()
}

import API_CONFIG from '@/config/apiConfig.js'

/**
 * 后台「网易云补全」扫码登录。
 *
 * 后端负责与网易云交互并保存登录 Cookie（写入 system_settings），前端只负责展示二维码与轮询状态。
 * 属后台管理接口，凭管理员令牌访问。
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
    const error = new Error(data.message || `请求失败 (${res.status})`)
    error.status = res.status
    throw error
  }
  return data.data
}

/**
 * 当前网易云登录状态。
 * @returns {Promise<{loggedIn:boolean,configured:boolean,nickname:string,avatarUrl:string,userId:number}>}
 */
export async function fetchNeteaseLoginStatus() {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/netease/status`, {
    headers: adminAuthHeader()
  })
  return readJson(res)
}

/**
 * 申请扫码登录二维码。
 * @returns {Promise<{key:string,qrUrl:string,qrImage:string}>} qrImage 为服务端渲染的 PNG data URL
 */
export async function createNeteaseQrKey() {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/netease/qr/key`, {
    method: 'POST',
    headers: adminAuthHeader()
  })
  return readJson(res)
}

/**
 * 轮询扫码状态。
 * @param {string} key 二维码 key
 * @returns {Promise<{code:number,status:'waiting'|'scanned'|'expired'|'confirmed',loggedIn:boolean,nickname?:string,avatarUrl?:string,userId?:number}>}
 */
export async function checkNeteaseQr(key) {
  const res = await fetch(
    `${API_CONFIG.BASE_URL}/api/admin/netease/qr/check?key=${encodeURIComponent(key)}`,
    { headers: adminAuthHeader() }
  )
  return readJson(res)
}

/** 退出网易云登录（清空登录 Cookie）。 */
export async function logoutNetease() {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/netease/logout`, {
    method: 'POST',
    headers: adminAuthHeader()
  })
  return readJson(res)
}

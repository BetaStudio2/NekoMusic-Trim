import API_CONFIG from '@/config/apiConfig.js'

const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

function adminAuthHeader() {
  const token = localStorage.getItem('adminToken')
  if (!token) {
    throw new Error('请先登录管理后台')
  }
  return { Authorization: `Bearer ${token}` }
}

/**
 * @returns {Promise<{ androidVer: string|null, pcVer: string|null, packages: Array }>}
 */
export async function fetchAdminClientReleases() {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/releases`, {
    headers: {
      'Content-Type': 'application/json',
      ...adminAuthHeader()
    }
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok || !data.success) {
    throw new Error(data.message || `加载失败 (${res.status})`)
  }
  return data.data || { androidVer: null, pcVer: null, packages: [] }
}

/**
 * @param {{ androidVer: string, pcVer: string }} payload
 */
export async function saveAdminClientReleaseVersions(payload) {
  const res = await fetch(`${API_CONFIG.BASE_URL}/api/admin/releases`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...adminAuthHeader()
    },
    body: JSON.stringify(payload)
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok || !data.success) {
    throw new Error(data.message || `保存失败 (${res.status})`)
  }
  return data.data
}

const PLATFORM_EXT = {
  android: '.apk',
  windows: '.exe',
  linux: '.deb',
  mac: '.pkg'
}

/**
 * @param {File} file
 * @param {'android'|'windows'|'linux'|'mac'} platform
 * @param {(loaded: number, total: number) => void} [onProgress]
 */
export async function uploadAdminClientRelease(file, platform, onProgress) {
  if (!file) {
    throw new Error('请选择文件')
  }
  const requiredExt = PLATFORM_EXT[platform]
  if (!requiredExt) {
    throw new Error('无效的平台')
  }
  const name = file.name.toLowerCase()
  if (!name.endsWith(requiredExt)) {
    throw new Error(`该平台仅支持 ${requiredExt.substring(1)} 文件`)
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    throw new Error('安装包不得超过 50MiB')
  }

  const form = new FormData()
  form.append('platform', platform)
  form.append('file', file)

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_CONFIG.BASE_URL}/api/admin/releases/upload`)
    const headers = adminAuthHeader()
    xhr.setRequestHeader('Authorization', headers.Authorization)

    if (onProgress) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          onProgress(e.loaded, e.total)
        }
      }
    }

    xhr.onload = () => {
      let data = {}
      try {
        data = JSON.parse(xhr.responseText || '{}')
      } catch {
        /* ignore */
      }
      if (xhr.status >= 200 && xhr.status < 300 && data.success) {
        resolve(data.data)
        return
      }
      reject(new Error(data.message || `上传失败 (${xhr.status})`))
    }

    xhr.onerror = () => reject(new Error('网络错误，上传失败'))
    xhr.send(form)
  })
}

export { MAX_UPLOAD_BYTES }

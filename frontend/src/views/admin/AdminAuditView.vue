<template>
  <div class="subpage">
    <header class="subpage__head">
      <h1 class="subpage__title">审核管理</h1>
      <p class="subpage__desc">审核用户上传的音乐，通过审核后将自动添加到音乐库中</p>
    </header>

    <div v-if="isLoading" class="state">
      <NSpinner :size="28" />
      <p>正在加载待审核列表…</p>
    </div>

    <NCard v-else-if="pendingUploads.length === 0" pad="lg" class="state state--empty">
      <NIcon name="clipboard-check" :size="26" />
      <p>暂无待审核的音乐</p>
    </NCard>

    <div v-else class="list">
      <NCard v-for="upload in pendingUploads" :key="upload.id" pad="none" class="audit">
        <button
          type="button"
          class="audit__record-ribbon"
          title="复制上传记录 ID"
          :aria-label="`复制上传记录 ID #${upload.id}`"
          @click="copyText(upload.id, '上传记录 ID')"
        >
          #{{ upload.id }}
        </button>

        <!-- 头部 -->
        <header class="audit__head">
          <div class="audit__cover">
            <img
              v-if="upload.coverFilePath && coverUrls[upload.id]"
              :src="coverUrls[upload.id]"
              class="audit__cover-img"
              alt="专辑封面"
              @error="handleCoverError"
            />
            <div v-else class="audit__cover-ph">
              <NSpinner v-if="loadingCovers[upload.id]" :size="18" />
              <NIcon v-else name="music" :size="22" />
            </div>
          </div>

          <div class="audit__title-block">
            <h2 class="audit__title">{{ upload.title }}</h2>
            <p class="audit__artist">{{ upload.artist }}</p>
          </div>

          <div class="audit__meta">
            <button
              type="button"
              class="audit__user"
              :title="`在用户管理中查看用户 ${upload.userNickname || upload.userId}`"
              :aria-label="`在用户管理中查看用户 ${upload.userNickname || upload.userId}`"
              @click="openUserManagement(upload.userId)"
            >
              <span class="audit__user-text">
                <span class="audit__uploader">{{ upload.userNickname || '未知用户' }}</span>
                <span class="audit__user-meta">
                  ID {{ upload.userId }} · 提交于 {{ formatDateTime(upload.createdAt) }}
                </span>
              </span>
              <NIcon name="chevron-right" :size="14" class="audit__user-chevron" />
            </button>
            <button
              type="button"
              class="audit__copy"
              title="复制用户 ID"
              aria-label="复制用户 ID"
              @click="copyText(upload.userId, '用户 ID')"
            >
              <NIcon name="copy" :size="13" />
            </button>
          </div>
        </header>

        <!-- 详情 -->
        <div class="audit__body">
          <dl class="song-meta" aria-label="歌曲信息">
            <div class="song-meta__item song-meta__item--album">
              <dt>专辑</dt>
              <dd>{{ upload.album || '未知专辑' }}</dd>
            </div>
            <div class="song-meta__item">
              <dt>语言</dt>
              <dd>{{ upload.language }}</dd>
            </div>
            <div class="song-meta__item">
              <dt>时长</dt>
              <dd>{{ formatDuration(upload.duration) }}</dd>
            </div>
            <div class="song-meta__item">
              <dt>格式</dt>
              <dd><NTag variant="accent" size="sm">{{ getFileFormat(upload.musicFilePath) }}</NTag></dd>
            </div>
            <div class="song-meta__item song-meta__item--wide">
              <dt>标签</dt>
              <dd>{{ upload.tags || '无' }}</dd>
            </div>
          </dl>

          <!-- 歌词预览 -->
          <section class="lyrics">
            <header class="lyrics__head">
              <span class="lyrics__title">歌词预览</span>
              <NButton
                size="sm"
                variant="ghost"
                :icon="showLyricsPreviewId === upload.id ? 'eye-off' : 'eye'"
                @click="toggleLyricsPreview(upload.id)"
              >
                {{ showLyricsPreviewId === upload.id ? '隐藏' : '显示' }}
              </NButton>
            </header>

            <div v-if="showLyricsPreviewId === upload.id" class="lyrics__body">
              <div v-if="uploadLyrics[upload.id]" class="lyrics__grid">
                <div class="lyrics__panel">
                  <p class="lyrics__panel-title">解析后歌词</p>
                  <div
                    :ref="(el) => setLyricsScrollRef(upload.id, 'left', el)"
                    class="lyrics__scroll"
                    @scroll="handleLyricsScroll(upload.id, 'left', $event)"
                  >
                    <div
                      v-for="(line, index) in uploadLyrics[upload.id]"
                      :key="index"
                      class="lyric-line"
                    >
                      <div class="lyric-text">{{ line.text }}</div>
                      <div v-if="line.translation" class="lyric-translation">{{ line.translation }}</div>
                    </div>
                  </div>
                </div>

                <div class="lyrics__panel">
                  <p class="lyrics__panel-title">纯文本歌词</p>
                  <div
                    :ref="(el) => setLyricsScrollRef(upload.id, 'right', el)"
                    class="lyrics__scroll"
                    @scroll="handleLyricsScroll(upload.id, 'right', $event)"
                  >
                    <pre class="lyrics__raw">{{ uploadRawLyrics[upload.id] || '无歌词' }}</pre>
                  </div>
                </div>
              </div>

              <p v-else class="lyrics__empty">
                <template v-if="loadingLyrics[upload.id]">加载歌词中…</template>
                <template v-else>无歌词</template>
              </p>
            </div>
          </section>
        </div>

        <!-- 底部：试听 + 操作 -->
        <footer class="audit__foot">
          <div v-if="currentPlayingId === upload.id" class="audit__player">
            <audio
              :ref="(el) => setAudioPlayerRef(upload.id, el)"
              :src="currentAudioUrl"
              controls
              @ended="handleAudioEnded"
              @error="handleAudioError"
            />
          </div>

          <div class="audit__actions">
            <NButton
              variant="secondary"
              icon="play"
              :disabled="currentPlayingId === upload.id || loadingAudios[upload.id]"
              :loading="loadingAudios[upload.id]"
              @click="playPreview(upload.id, upload.musicFilePath)"
            >
              {{ currentPlayingId === upload.id ? '播放中' : '试听' }}
            </NButton>
            <NButton
              variant="primary"
              icon="check"
              :loading="actionLoading[upload.id] === 'approve'"
              :disabled="Boolean(actionLoading[upload.id])"
              @click="approveUpload(upload.id)"
            >
              通过
            </NButton>
            <NButton
              variant="danger"
              icon="close"
              :disabled="Boolean(actionLoading[upload.id])"
              @click="showRejectModal(upload.id)"
            >
              拒绝
            </NButton>
          </div>
        </footer>
      </NCard>
    </div>

    <!-- 拒绝弹窗 -->
    <NModal v-model="showRejectConfirm" title="拒绝审核" size="sm" @close="closeRejectModal">
      <div class="field field--last">
        <label class="field__label" for="reject-reason">拒绝原因</label>
        <NInput
          id="reject-reason"
          v-model="rejectReason"
          textarea
          :rows="4"
          placeholder="请输入拒绝原因（可选）"
        />
      </div>
      <template #footer>
        <NButton variant="ghost" :disabled="isRejecting" @click="closeRejectModal">取消</NButton>
        <NButton variant="danger" :loading="isRejecting" :disabled="isRejecting" @click="confirmReject">
          确认拒绝
        </NButton>
      </template>
    </NModal>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from '@/composables/useToast'
import { parseLyricsPayload } from '@/utils/lyrics.js'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NModal, NSpinner, NTag } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'

const toast = useToast()
const router = useRouter()

const adminInfo = ref({ username: '' })

// 切换侧边栏
const pendingUploads = ref([])
const isLoading = ref(true)
const currentPlayingId = ref(null)
const currentAudioUrl = ref(null)
const audioPlayers = ref({})
const loadingAudios = ref({})
const showLyricsPreviewId = ref(null)
const uploadLyrics = ref({})
const uploadRawLyrics = ref({})
const loadingLyrics = ref({})
const lyricsScrollRefs = ref({})
const showRejectConfirm = ref(false)
const rejectUploadId = ref(null)
const rejectReason = ref('')
const rejectModalRef = ref(null)
const coverUrls = ref({})
const loadingCovers = ref({})
const actionLoading = ref({})

const isRejecting = computed(() =>
  Boolean(rejectUploadId.value && actionLoading.value[rejectUploadId.value] === 'reject')
)

// 设置audio player的动态ref
const setAudioPlayerRef = (uploadId, el) => {
  if (el) {
    audioPlayers.value[uploadId] = el
  } else {
    delete audioPlayers.value[uploadId]
  }
}

// 获取管理员信息
const getAdminInfo = () => {
  const adminData = localStorage.getItem('adminInfo')
  if (adminData) {
    adminInfo.value = JSON.parse(adminData)
  }
}

const openUserManagement = (userId) => {
  window.open(`https://auth.nekocore.cn/admin?user=${encodeURIComponent(userId)}`, '_blank', 'noopener')
}

const copyText = async (value, label) => {
  const text = String(value ?? '')
  if (!text) return

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      const copied = document.execCommand('copy')
      document.body.removeChild(textarea)
      if (!copied) throw new Error('execCommand copy failed')
    }
    toast.success(`${label}已复制`)
  } catch (error) {
    console.error(`复制${label}失败:`, error)
    toast.error(`复制${label}失败，请手动复制`)
  }
}

// 获取待审核列表
const fetchPendingUploads = async () => {
  isLoading.value = true
  try {
    const token = localStorage.getItem('adminToken')
    if (!token) {
      toast.error('请先登录管理员账号')
      router.push('/admin/login')
      return
    }
    
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/audit/pending`, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    const result = await response.json()
    
    if (result.success) {
      pendingUploads.value = result.data || []
      
      // 加载所有封面图片
      pendingUploads.value.forEach(upload => {
        if (upload.coverFilePath) {
          loadCoverImage(upload.id, upload.coverFilePath)
        }
      })
    } else {
      toast.error(result.message || '获取待审核列表失败')
    }
  } catch (error) {
    console.error('获取待审核列表失败:', error)
    toast.error('获取待审核列表失败')
  } finally {
    isLoading.value = false
  }
}

// 审核通过
const approveUpload = async (uploadId) => {
  if (actionLoading.value[uploadId]) return

  const token = localStorage.getItem('adminToken')
  if (!token) {
    toast.error('请先登录管理员账号')
    router.push('/admin/login')
    return
  }

  actionLoading.value[uploadId] = 'approve'

  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/audit/approve/${uploadId}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    const result = await response.json()
    
    if (result.success) {
      toast.success('审核通过，音乐已添加到库中')
      pendingUploads.value = pendingUploads.value.filter(u => u.id !== uploadId)
      
      // 清理相关缓存
      if (currentPlayingId.value === uploadId) {
        stopPreview()
      }
      if (showLyricsPreviewId.value === uploadId) {
        showLyricsPreviewId.value = null
      }
      delete uploadLyrics.value[uploadId]
      delete loadingLyrics.value[uploadId]
      delete loadingAudios.value[uploadId]
      
      // 清理封面图片URL
      if (coverUrls.value[uploadId]) {
        URL.revokeObjectURL(coverUrls.value[uploadId])
        delete coverUrls.value[uploadId]
      }
      delete loadingCovers.value[uploadId]
    } else {
      toast.error(result.message || '审核通过失败')
    }
  } catch (error) {
    console.error('审核通过失败:', error)
    toast.error('审核通过失败')
  } finally {
    delete actionLoading.value[uploadId]
  }
}

// 显示拒绝模态框
const showRejectModal = (uploadId) => {
  rejectUploadId.value = uploadId
  rejectReason.value = ''
  showRejectConfirm.value = true
}

// 关闭拒绝模态框
const closeRejectModal = () => {
  showRejectConfirm.value = false
  rejectUploadId.value = null
  rejectReason.value = ''
}

// 确认拒绝
const confirmReject = async () => {
  const uploadId = rejectUploadId.value
  if (!uploadId || actionLoading.value[uploadId]) return

  const token = localStorage.getItem('adminToken')
  if (!token) {
    toast.error('请先登录管理员账号')
    router.push('/admin/login')
    return
  }

  actionLoading.value[uploadId] = 'reject'
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/audit/reject/${uploadId}`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        reason: rejectReason.value || '管理员拒绝审核'
      })
    })
    
    const result = await response.json()
    
    if (result.success) {
      toast.success('审核拒绝成功')
      pendingUploads.value = pendingUploads.value.filter(u => u.id !== uploadId)
      
      // 清理相关缓存
      closeRejectModal()
      if (currentPlayingId.value === uploadId) {
        stopPreview()
      }
      if (showLyricsPreviewId.value === uploadId) {
        showLyricsPreviewId.value = null
      }
      delete uploadLyrics.value[uploadId]
      delete loadingLyrics.value[uploadId]
      delete loadingAudios.value[uploadId]
      
      // 清理封面图片URL
      if (coverUrls.value[uploadId]) {
        URL.revokeObjectURL(coverUrls.value[uploadId])
        delete coverUrls.value[uploadId]
      }
      delete loadingCovers.value[uploadId]
    } else {
      toast.error(result.message || '审核拒绝失败')
    }
  } catch (error) {
    console.error('审核拒绝失败:', error)
    toast.error('审核拒绝失败')
  } finally {
    delete actionLoading.value[uploadId]
  }
}

// 试听音乐
const playPreview = async (uploadId, musicFilePath) => {
  if (currentPlayingId.value === uploadId) {
    return
  }
  
  // 先停止当前播放
  stopPreview()
  
  currentPlayingId.value = uploadId
  loadingAudios.value[uploadId] = true
  
  try {
    const token = localStorage.getItem('adminToken')
    if (!token) {
      toast.error('请先登录管理员账号')
      currentPlayingId.value = null
      loadingAudios.value[uploadId] = false
      return
    }
    
    // 使用Fetch请求获取音频文件
    const response = await fetch(getMusicPreviewUrl(musicFilePath), {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }
    
    // 创建Blob URL
    const blob = await response.blob()
    currentAudioUrl.value = URL.createObjectURL(blob)
    
    // 等待DOM更新后再播放
    await new Promise(resolve => setTimeout(resolve, 150))
    
    // 播放音频
    const player = audioPlayers.value[uploadId]
    if (player) {
      player.play().catch(error => {
        console.error('播放失败:', error)
        toast.error('播放失败，请检查网络或文件状态')
        currentPlayingId.value = null
        loadingAudios.value[uploadId] = false
      })
    } else {
      console.error('无法找到audio player')
      toast.error('播放器初始化失败')
      currentPlayingId.value = null
      loadingAudios.value[uploadId] = false
    }
    
    loadingAudios.value[uploadId] = false
    
  } catch (error) {
    console.error('加载音频失败:', error)
    toast.error('加载音频失败: ' + error.message)
    currentPlayingId.value = null
    loadingAudios.value[uploadId] = false
  }
}

// 停止播放
const stopPreview = () => {
  const playingId = currentPlayingId.value
  
  if (playingId && audioPlayers.value[playingId]) {
    const player = audioPlayers.value[playingId]
    player.pause()
    player.currentTime = 0
  }
  
  // 释放Blob URL
  if (currentAudioUrl.value) {
    URL.revokeObjectURL(currentAudioUrl.value)
    currentAudioUrl.value = null
  }
  
  // 清理加载状态
  currentPlayingId.value = null
  if (playingId) {
    loadingAudios.value[playingId] = false
  }
}

// 音频播放结束处理
const handleAudioEnded = () => {
  const playingId = currentPlayingId.value
  currentPlayingId.value = null
  if (playingId) {
    loadingAudios.value[playingId] = false
  }
}

// 音频播放错误处理
const handleAudioError = (error) => {
  console.error('音频加载错误:', error)
  toast.error('音频加载失败，请检查文件是否存在')
  const playingId = currentPlayingId.value
  currentPlayingId.value = null
  if (playingId) {
    loadingAudios.value[playingId] = false
  }
}

// 获取音乐预览URL
const getMusicPreviewUrl = (filePath) => {
  return `${API_CONFIG.BASE_URL}/api/user/upload/preview?path=${encodeURIComponent(filePath)}`
}

// 获取封面预览URL
const getCoverPreviewUrl = (coverFilePath) => {
  if (!coverFilePath) return ''
  return `${API_CONFIG.BASE_URL}/api/user/upload/preview?path=${encodeURIComponent(coverFilePath)}`
}

// 加载封面图片
const loadCoverImage = async (uploadId, coverFilePath) => {
  if (!coverFilePath || coverUrls.value[uploadId] || loadingCovers.value[uploadId]) {
    return
  }

  loadingCovers.value[uploadId] = true

  try {
    const token = localStorage.getItem('adminToken')
    if (!token) {
      console.error('未找到管理员token')
      return
    }

    const response = await fetch(getCoverPreviewUrl(coverFilePath), {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const blob = await response.blob()
    coverUrls.value[uploadId] = URL.createObjectURL(blob)

  } catch (error) {
    console.error('加载封面失败:', error)
  } finally {
    loadingCovers.value[uploadId] = false
  }
}

// 处理封面加载错误
const handleCoverError = (event) => {
  event.target.style.display = 'none'
  const placeholder = event.target.nextElementSibling
  if (placeholder) {
    placeholder.style.display = 'flex'
    const span = placeholder.querySelector('span')
    if (span) {
      span.textContent = '🎵'
    }
  }
}

// 获取文件名
const getFileName = (filePath) => {
  if (!filePath) return ''
  const parts = filePath.split(/[/\\]/)
  return parts[parts.length - 1]
}

// 获取音频文件格式
const getFileFormat = (filePath) => {
  if (!filePath) return '未知'
  const match = filePath.match(/\.([a-z0-9]+)$/i)
  return match ? match[1].toUpperCase() : '未知'
}

// 格式化日期时间
const formatDateTime = (dateTime) => {
  if (!dateTime) return ''
  const date = new Date(dateTime)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

// 格式化时长
const formatDuration = (seconds) => {
  if (!seconds) return '0:00'
  const minutes = Math.floor(seconds / 60)
  const secs = seconds % 60
  return `${minutes}:${secs.toString().padStart(2, '0')}`
}

// 切换歌词预览
const toggleLyricsPreview = async (uploadId) => {
  if (showLyricsPreviewId.value === uploadId) {
    // 如果已经显示，则隐藏
    showLyricsPreviewId.value = null
    return
  }
  
  // 显示歌词并加载
  showLyricsPreviewId.value = uploadId
  
  // 如果还没有加载过歌词，则加载
  if (!uploadLyrics.value[uploadId] && !loadingLyrics.value[uploadId]) {
    await loadLyricsForUpload(uploadId)
  }
}

// 加载上传的歌词
const loadLyricsForUpload = async (uploadId) => {
  loadingLyrics.value[uploadId] = true
  
  try {
    const token = localStorage.getItem('adminToken')
    if (!token) {
      toast.error('请先登录管理员账号')
      return
    }
    
    // 获取上传记录中的歌词文件路径
    const upload = pendingUploads.value.find(u => u.id === uploadId)
    if (!upload || !upload.lyricsFilePath) {
      uploadLyrics.value[uploadId] = []
      return
    }
    
    // 获取歌词文件
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/upload/preview?path=${encodeURIComponent(upload.lyricsFilePath)}`, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }
    
    const lyricsText = await response.text()
    
    // 保存原始歌词
    uploadRawLyrics.value[uploadId] = lyricsText
    
    // .neko 直接按 neko-v1 解析；.lrc 继续走行级 LRC 解析。
    uploadLyrics.value[uploadId] = parseUploadedLyrics(lyricsText)
    
  } catch (error) {
    console.error('加载歌词失败:', error)
    uploadLyrics.value[uploadId] = []
  } finally {
    loadingLyrics.value[uploadId] = false
  }
}

// 解析LRC歌词格式
const parseLrcLyrics = (lrcText) => {
  if (!lrcText) {
    return []
  }
  
  const lines = lrcText.split('\n')
  const parsed = []
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim()
    
    // 跳过空行
    if (!line) {
      continue
    }
    
    // 匹配时间戳歌词行 [mm:ss.xx] 或 [mm:ss.xxx]
    const timeRegex = /\[(\d{2}):(\d{2})\.(\d{2,3})\]/
    const timeMatch = line.match(timeRegex)
    
    if (timeMatch) {
      // 这是歌词行，提取时间和文本
      const minutes = parseInt(timeMatch[1])
      const seconds = parseInt(timeMatch[2])
      const milliseconds = parseInt(timeMatch[3])
      
      // 根据毫秒部分的位数正确计算秒数
      let millisecondsDivisor
      if (milliseconds.toString().length === 2) {
        millisecondsDivisor = 100 // 两位毫秒，如 .25
      } else {
        millisecondsDivisor = 1000 // 三位毫秒，如 .250
      }
      const timeInSeconds = minutes * 60 + seconds + (milliseconds / millisecondsDivisor)
      const text = line.replace(timeRegex, '').trim()
      
      // 查找下一行是否有翻译
      let translation = ''
      if (i + 1 < lines.length) {
        const nextLine = lines[i + 1].trim()
        // 检查是否是JSON格式的翻译行
        const jsonMatch = nextLine.match(/^\{["\'](.+)["\']\}$/)
        if (jsonMatch) {
          translation = jsonMatch[1]
        }
      }
      
      parsed.push({
        time: timeInSeconds,
        text: text,
        translation: translation
      })
    }
  }
  
  // 按时间排序
  parsed.sort((a, b) => a.time - b.time)
  return parsed
}

const parseUploadedLyrics = (content) => {
  const text = String(content || '').trim()
  if (text.startsWith('{')) {
    try {
      const rich = JSON.parse(text)
      if (Array.isArray(rich?.lines)) {
        return parseLyricsPayload({ lyricsRich: rich })
      }
    } catch {
      // 非 JSON 内容继续按普通 LRC 处理。
    }
  }
  return parseLrcLyrics(text)
}

// 设置歌词滚动区域的ref
const setLyricsScrollRef = (uploadId, side, el) => {
  if (!lyricsScrollRefs.value[uploadId]) {
    lyricsScrollRefs.value[uploadId] = {}
  }
  lyricsScrollRefs.value[uploadId][side] = el
}

// 处理歌词滚动同步
const handleLyricsScroll = (uploadId, sourceSide, event) => {
  const targetSide = sourceSide === 'left' ? 'right' : 'left'
  
  if (!lyricsScrollRefs.value[uploadId] || 
      !lyricsScrollRefs.value[uploadId][sourceSide] || 
      !lyricsScrollRefs.value[uploadId][targetSide]) {
    return
  }
  
  const sourceElement = lyricsScrollRefs.value[uploadId][sourceSide]
  const targetElement = lyricsScrollRefs.value[uploadId][targetSide]
  
  // 计算滚动比例
  const sourceScrollTop = sourceElement.scrollTop
  const sourceScrollHeight = sourceElement.scrollHeight - sourceElement.clientHeight
  const targetScrollHeight = targetElement.scrollHeight - targetElement.clientHeight
  
  if (sourceScrollHeight > 0 && targetScrollHeight > 0) {
    const scrollRatio = sourceScrollTop / sourceScrollHeight
    const targetScrollTop = scrollRatio * targetScrollHeight
    targetElement.scrollTop = targetScrollTop
  }
}

// 退出登录

onMounted(() => {
  getAdminInfo()
  fetchPendingUploads()
})
</script>

<style scoped>
.subpage {
  width: 100%;
}

.subpage__head {
  margin-bottom: var(--n-space-6);
}

.subpage__title {
  margin: 0 0 var(--n-space-1);
  font-size: clamp(1.25rem, 2.6vw, 1.6rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.02em;
  color: var(--n-text);
}

.subpage__desc {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== 状态 ==================== */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-3);
  padding: var(--n-space-12) var(--n-space-6);
  text-align: center;
  color: var(--n-text-muted);
}

.state :deep(.n-icon) {
  color: var(--n-text-faint);
}

.state p {
  margin: 0;
  font-size: var(--n-text-sm);
}

.state--empty :deep(.n-icon) {
  color: var(--n-text-faint);
}

/* ==================== 审核卡片 ==================== */
.list {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-5);
}

.audit {
  position: relative;
  overflow: hidden;
}

.audit__record-ribbon {
  position: absolute;
  top: var(--n-space-4);
  right: calc((var(--n-space-12) + var(--n-space-2)) * -1);
  z-index: var(--n-z-content);
  width: 12rem;
  padding: var(--n-space-1) 0;
  transform: rotate(45deg);
  background: var(--n-accent-strong);
  border: 0;
  color: var(--n-text-inverse);
  cursor: pointer;
  font-family: inherit;
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
  line-height: var(--n-leading-tight);
  letter-spacing: 0.04em;
  text-align: center;
  transition: background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .audit__record-ribbon:hover {
    background: var(--n-accent);
  }
}

.audit__record-ribbon:focus-visible {
  outline: 2px solid var(--n-text);
  outline-offset: -2px;
}

.audit__head {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  padding: var(--n-space-5);
  padding-right: calc(var(--n-space-16) + var(--n-space-8));
  border-bottom: 1px solid var(--n-line-subtle);
}

.audit__cover {
  flex: none;
  width: 64px;
  height: 64px;
  border-radius: var(--n-radius-sm);
  overflow: hidden;
  border: 1px solid var(--n-line-subtle);
  background: var(--n-surface-soft);
}

.audit__cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.audit__cover-ph {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--n-text-faint);
}

.audit__title-block {
  flex: 1;
  min-width: 0;
}

.audit__title {
  margin: 0;
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
  overflow-wrap: anywhere;
}

.audit__artist {
  margin: 2px 0 0;
  color: var(--n-accent-strong);
  font-size: var(--n-text-sm);
}

.audit__meta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--n-space-2);
  flex: none;
}

.audit__user {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
  max-width: 20rem;
  padding: 0;
  border: 0;
  margin: 0;
  background: none;
  color: var(--n-text-faint);
  cursor: pointer;
  font: inherit;
  text-align: right;
}

.audit__user-text {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 0;
}

.audit__uploader {
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  overflow-wrap: anywhere;
  transition: color var(--n-duration-fast) var(--n-ease);
}

.audit__user-meta {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  overflow-wrap: anywhere;
}

.audit__user-chevron {
  flex: none;
  color: var(--n-text-faint);
  transition:
    color var(--n-duration-fast) var(--n-ease),
    transform var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .audit__user:hover .audit__uploader,
  .audit__user:hover .audit__user-chevron {
    color: var(--n-accent-strong);
  }

  .audit__user:hover .audit__user-chevron {
    transform: translateX(2px);
  }
}

.audit__user:focus-visible,
.audit__copy:focus-visible {
  outline: 2px solid var(--n-accent-line);
  outline-offset: 2px;
}

.audit__copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: var(--n-space-6);
  height: var(--n-space-6);
  padding: 0;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xs);
  background: var(--n-surface-soft);
  color: var(--n-text-faint);
  cursor: pointer;
  transition:
    color var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .audit__copy:hover {
    border-color: var(--n-accent-line);
    background: var(--n-accent-soft);
    color: var(--n-accent-strong);
  }
}

/* ==================== 详情 ==================== */
.audit__body {
  padding: var(--n-space-5);
}

.song-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3) var(--n-space-5);
  margin: 0 0 var(--n-space-5);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line-subtle);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-sunken);
}

.song-meta__item {
  display: flex;
  flex: 1 1 120px;
  flex-direction: column;
  gap: var(--n-space-1);
  min-width: 0;
}

.song-meta__item--album {
  flex: 2 1 220px;
}

.song-meta__item--wide {
  flex-basis: 100%;
}

.song-meta dt {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.song-meta dd {
  margin: 0;
  min-width: 0;
  color: var(--n-text);
  font-size: var(--n-text-sm);
  overflow-wrap: anywhere;
}

/* ==================== 歌词预览 ==================== */
.lyrics__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-3);
}

.lyrics__title {
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
}

.lyrics__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--n-space-4);
}

.lyrics__panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  overflow: hidden;
}

.lyrics__panel-title {
  margin: 0;
  padding: var(--n-space-2) var(--n-space-4);
  border-bottom: 1px solid var(--n-line-subtle);
  background: var(--n-accent-soft);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.lyrics__scroll {
  max-height: 260px;
  overflow-y: auto;
  padding: var(--n-space-3) var(--n-space-4);
}

.lyric-line {
  padding: 2px 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.lyric-text {
  overflow-wrap: anywhere;
}

.lyric-translation {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lyrics__raw {
  margin: 0;
  color: var(--n-text-muted);
  font-family: var(--n-font-mono);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.lyrics__empty {
  margin: 0;
  padding: var(--n-space-6) 0;
  text-align: center;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

/* ==================== 底部 ==================== */
.audit__foot {
  padding: var(--n-space-4) var(--n-space-5);
  border-top: 1px solid var(--n-line-subtle);
  background: var(--n-surface-sunken);
}

.audit__player {
  margin-bottom: var(--n-space-4);
}

.audit__player audio {
  width: 100%;
}

.audit__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3);
  justify-content: flex-end;
}

/* ==================== 弹窗 ==================== */
.field--last {
  margin-bottom: 0;
}

.field__label {
  display: block;
  margin-bottom: var(--n-space-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-medium);
}

@media (max-width: 560px) {
  .audit__head {
    flex-wrap: wrap;
    padding-top: var(--n-space-10);
    padding-right: var(--n-space-5);
  }

  .audit__record-ribbon {
    top: 0;
  }

  .audit__meta {
    align-items: flex-start;
    justify-content: flex-start;
    width: 100%;
  }

  .audit__user {
    flex: 1;
    align-items: flex-start;
    max-width: none;
    text-align: left;
  }

  .audit__user-text {
    align-items: flex-start;
  }

  .audit__actions :deep(.n-btn) {
    flex: 1 1 auto;
    justify-content: center;
  }
}
</style>

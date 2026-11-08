<template>
  <AmbientBackdrop />

  <PageShell width="default">
    <header class="head">
      <div>
        <h1 class="head__title">{{ $t('playlists.title') }}</h1>
        <p class="head__sub">{{ $t('playlists.sub') }}</p>
      </div>
      <div class="head__actions">
        <NButton variant="secondary" icon="file-up" @click="openImportDialog">
          {{ $t('playlists.importLxmc') }}
        </NButton>
        <NButton variant="primary" icon="plus" @click="goToCreatePlaylist">{{ $t('playlists.create') }}</NButton>
      </div>
    </header>

    <div v-if="loading" class="state">
      <NSpinner :size="28" />
    </div>

    <div v-else-if="playlists.length > 0" class="grid">
      <NCard
        v-for="playlist in playlists"
        :key="playlist.id"
        hoverable
        pad="none"
        class="pl"
        tabindex="0"
        role="link"
        @click="goToPlaylistDetail(playlist.id)"
        @keydown.enter.prevent="goToPlaylistDetail(playlist.id)"
      >
        <div class="pl__cover">
          <img :src="getPlaylistCover(playlist)" :alt="playlist.name" loading="lazy" @error="handleCoverError($event)" />
        </div>
        <div class="pl__body">
          <h2 class="pl__title">{{ playlist.name }}</h2>
          <p class="pl__meta">
            <NIcon name="music" :size="13" />
            {{ $t('track.songCount', { count: playlist.musicCount }) }}
          </p>
          <p v-if="playlist.description" class="pl__desc">{{ playlist.description }}</p>
        </div>
        <div v-if="isPlaylistOwner(playlist.userId)" class="pl__actions" @click.stop>
          <NButton size="sm" variant="secondary" icon="pencil" @click="showEditDialog(playlist)">{{ $t('playlists.edit') }}</NButton>
          <NButton size="sm" variant="danger" icon="trash-2" @click="confirmDelete(playlist)">{{ $t('playlists.delete') }}</NButton>
        </div>
      </NCard>
    </div>

    <NCard v-else pad="lg" class="empty">
      <span class="empty__icon"><NIcon name="list-music" :size="26" /></span>
      <h2 class="empty__title">{{ $t('playlists.emptyTitle') }}</h2>
      <p class="empty__text">{{ $t('playlists.emptyText') }}</p>
      <NButton variant="primary" icon="plus" @click="goToCreatePlaylist">{{ $t('playlists.create') }}</NButton>
    </NCard>

    <!-- 编辑歌单 -->
    <NModal v-model="showEdit" :title="$t('playlists.editTitle')">
      <div class="field">
        <label class="field__label" for="edit-name">{{ $t('playlists.nameLabel') }}</label>
        <NInput id="edit-name" v-model="editForm.name" maxlength="255" required />
      </div>
      <div class="field field--last">
        <label class="field__label" for="edit-desc">{{ $t('playlists.descLabel') }}</label>
        <NInput id="edit-desc" v-model="editForm.description" textarea :rows="4" maxlength="500" />
      </div>
      <template #footer>
        <NButton variant="ghost" @click="closeEditDialog">{{ $t('common.cancel') }}</NButton>
        <NButton variant="primary" @click="handleEditPlaylist">{{ $t('playlists.save') }}</NButton>
      </template>
    </NModal>

    <!-- 删除确认 -->
    <NModal v-model="showDeleteConfirm" :title="$t('playlists.deleteConfirmTitle')" size="sm">
      <p class="del-text">{{ $t('playlists.deleteConfirmText', { name: playlistToDelete?.name }) }}</p>
      <p class="del-warn">{{ $t('playlists.deleteWarn') }}</p>
      <template #footer>
        <NButton variant="ghost" @click="closeDeleteConfirm">{{ $t('common.cancel') }}</NButton>
        <NButton variant="danger" @click="handleDeletePlaylist">{{ $t('playlists.delete') }}</NButton>
      </template>
    </NModal>

    <!-- 落雪 .lxmc 歌单导入 -->
    <NModal
      v-model="showImport"
      :title="$t('playlists.lxmc.title')"
      size="lg"
      :mask-closable="!importing"
      :esc-closable="!importing"
      :show-close="!importing"
      @close="resetImportFlow"
    >
      <p class="lxmc__lede">{{ $t('playlists.lxmc.lede') }}</p>

      <div
        class="lxmc-drop"
        :class="{ 'lxmc-drop--over': importDragging, 'lxmc-drop--ready': importFile }"
        @dragenter.prevent="importDragging = true"
        @dragover.prevent="importDragging = true"
        @dragleave.prevent="importDragging = false"
        @drop.prevent="handleImportDrop"
      >
        <input
          ref="importFileInput"
          class="lxmc-drop__input"
          type="file"
          accept=".lxmc,application/gzip,application/json"
          @change="handleImportFileChange"
        />
        <button type="button" class="lxmc-drop__hit" :disabled="parsingFile || importing" @click="selectImportFile">
          <span class="lxmc-drop__icon">
            <NSpinner v-if="parsingFile" :size="24" />
            <NIcon v-else name="file-up" :size="28" />
          </span>
          <span class="lxmc-drop__title">
            {{ parsingFile ? $t('playlists.lxmc.parsing') : $t('playlists.lxmc.chooseFile') }}
          </span>
          <span class="lxmc-drop__hint">
            {{ importFile ? importFile.name : $t('playlists.lxmc.chooseFileHint') }}
          </span>
        </button>
      </div>

      <template v-if="lxmcPlaylists.length">
        <div class="field">
          <span class="field__label">{{ $t('playlists.lxmc.sourcePlaylist') }}</span>
          <NSelect
            v-model="importForm.playlistId"
            :options="sourcePlaylistOptions"
            :aria-label="$t('playlists.lxmc.sourcePlaylist')"
            :disabled="importing"
            @change="handleSourcePlaylistChange"
          />
          <div v-if="selectedSourceSummary" class="lxmc-sources">
            <span class="lxmc-sources__label">{{ $t('playlists.lxmc.sources') }}</span>
            <NTag
              v-for="(count, source) in selectedSourceSummary.sources"
              :key="source"
              size="sm"
              variant="default"
            >
              {{ sourceLabel(source) }} {{ count }}
            </NTag>
          </div>
        </div>

        <div class="field field--last">
          <span class="field__label">{{ $t('playlists.lxmc.target') }}</span>
          <div class="lxmc-targets">
            <label class="lxmc-target" :class="{ 'lxmc-target--active': importForm.targetMode === 'new' }">
              <input v-model="importForm.targetMode" type="radio" value="new" :disabled="importing" />
              <span>
                <strong>{{ $t('playlists.lxmc.targetNew') }}</strong>
                <small>{{ $t('playlists.lxmc.targetNewHint') }}</small>
              </span>
            </label>
            <label
              v-if="playlists.length"
              class="lxmc-target"
              :class="{ 'lxmc-target--active': importForm.targetMode === 'existing' }"
            >
              <input v-model="importForm.targetMode" type="radio" value="existing" :disabled="importing" />
              <span>
                <strong>{{ $t('playlists.lxmc.targetExisting') }}</strong>
                <small>{{ $t('playlists.lxmc.targetExistingHint') }}</small>
              </span>
            </label>
          </div>

          <NInput
            v-if="importForm.targetMode === 'new'"
            id="lxmc-target-name"
            v-model="importForm.targetPlaylistName"
            class="lxmc-target-name"
            maxlength="255"
            :placeholder="$t('playlists.lxmc.targetNamePlaceholder')"
            :disabled="importing"
          />
          <NSelect
            v-else
            v-model="importForm.targetPlaylistId"
            class="lxmc-target-name"
            :options="targetPlaylistOptions"
            :placeholder="$t('playlists.lxmc.selectTarget')"
            :aria-label="$t('playlists.lxmc.targetExisting')"
            :disabled="importing"
          />
        </div>
      </template>

      <div v-if="importing || importSummary || importError" class="lxmc-progress">
        <div class="lxmc-progress__head">
          <span>{{ importError ? $t('playlists.lxmc.failed') : importSummary ? $t('playlists.lxmc.done') : $t('playlists.lxmc.importing') }}</span>
          <span>{{ importProgress.finished }} / {{ importProgress.total || selectedTrackCount }}</span>
        </div>
        <div class="lxmc-progress__bar" role="progressbar" :aria-valuenow="importPercent" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: `${importPercent}%` }" />
        </div>
        <p v-if="importCurrent && !importSummary && !importError" class="lxmc-progress__current">
          <strong>{{ importCurrent.title || $t('playlists.lxmc.unknownTrack') }}</strong>
          <span>{{ importCurrent.artist }}</span>
          <NTag size="sm" variant="default">{{ statusLabel(importCurrent.status) }}</NTag>
        </p>
        <div v-if="importSummary" class="lxmc-stats">
          <span>{{ $t('playlists.lxmc.statImported', { count: importSummary.imported }) }}</span>
          <span>{{ $t('playlists.lxmc.statExisted', { count: importSummary.existed }) }}</span>
          <span>{{ $t('playlists.lxmc.statFailed', { count: importSummary.failed }) }}</span>
        </div>
        <p v-if="importError" class="lxmc-progress__error">{{ importError }}</p>
        <p v-if="importing" class="lxmc-progress__hint">{{ $t('playlists.lxmc.backgroundHint') }}</p>
      </div>

      <template #footer>
        <NButton variant="ghost" @click="closeImportDialog">
          {{ importing ? $t('playlists.lxmc.runInBackground') : importSummary ? $t('common.close') : $t('common.cancel') }}
        </NButton>
        <NButton
          v-if="importSummary"
          variant="secondary"
          icon="refresh"
          @click="resetImportFlow"
        >
          {{ $t('playlists.lxmc.importAnother') }}
        </NButton>
        <NButton
          v-if="!importSummary"
          variant="primary"
          icon="sparkles"
          :loading="importing"
          :disabled="!canStartImport"
          @click="startLxmcImport"
        >
          {{ $t('playlists.lxmc.start') }}
        </NButton>
      </template>
    </NModal>
  </PageShell>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { applyVipFromPlaylistsResponse } from '@/utils/userVip.js'
import { getUser } from '@/utils/userStore.js'
import { avatarUrl } from '@/utils/userAvatar.js'
import { useToast } from '@/composables/useToast'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NModal, NSelect, NSpinner, NTag } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { t } from '@/i18n'

const toast = useToast()
const router = useRouter()
const route = useRoute()

// 响应式数据
const playlists = ref([])
const loading = ref(true)
const showEdit = ref(false)
const showDeleteConfirm = ref(false)
const currentPlaylist = ref(null)
const playlistToDelete = ref(null)
const editForm = ref({
  id: null,
  name: '',
  description: ''
})

const showImport = ref(false)
const importFileInput = ref(null)
const importFile = ref(null)
const parsingFile = ref(false)
const importDragging = ref(false)
const lxmcPlaylists = ref([])
const importing = ref(false)
const importSummary = ref(null)
const importError = ref('')
const importCurrent = ref(null)
const importProgress = ref(emptyImportProgress())
const importForm = ref({
  playlistId: '',
  targetMode: 'new',
  targetPlaylistId: '',
  targetPlaylistName: ''
})
const importFinishedIndexes = new Set()
let importAbortController = null
const ALL_PLAYLISTS = 'all'

const selectedSourcePlaylist = computed(() =>
  lxmcPlaylists.value.find((playlist) => playlist.id === importForm.value.playlistId) || null
)

const sourcePlaylistOptions = computed(() => {
  const totalTracks = lxmcPlaylists.value.reduce((sum, playlist) => sum + Number(playlist.trackCount || 0), 0)
  return [
    {
      value: ALL_PLAYLISTS,
      label: t('playlists.lxmc.allPlaylists'),
      description: t('playlists.lxmc.allPlaylistsMeta', {
        count: lxmcPlaylists.value.length,
        tracks: totalTracks,
      }),
    },
    ...lxmcPlaylists.value.map((playlist) => ({
      value: playlist.id,
      label: playlist.name || playlist.id,
      description: t('track.songCount', { count: playlist.trackCount }),
    })),
  ]
})

const targetPlaylistOptions = computed(() =>
  playlists.value.map((playlist) => ({
    value: String(playlist.id),
    label: playlist.name,
    description: t('track.songCount', { count: playlist.musicCount || 0 }),
  })),
)

const selectedSourceSummary = computed(() => {
  if (importForm.value.playlistId === ALL_PLAYLISTS) {
    const sources = {}
    let trackCount = 0
    for (const playlist of lxmcPlaylists.value) {
      trackCount += Number(playlist.trackCount || 0)
      for (const [source, count] of Object.entries(playlist.sources || {})) {
        sources[source] = (sources[source] || 0) + Number(count || 0)
      }
    }
    return { trackCount, sources }
  }
  return selectedSourcePlaylist.value
})

const selectedTrackCount = computed(() => Number(selectedSourceSummary.value?.trackCount || 0))

const importPercent = computed(() => {
  const total = Number(importProgress.value.total || selectedTrackCount.value || 0)
  if (!total) return importSummary.value ? 100 : 0
  return Math.min(100, Math.round((importProgress.value.finished * 100) / total))
})

const canStartImport = computed(() => {
  if (!importFile.value || !selectedSourceSummary.value || parsingFile.value || importing.value) {
    return false
  }
  if (importForm.value.targetMode === 'existing') {
    return !!importForm.value.targetPlaylistId
  }
  return !!importForm.value.targetPlaylistName.trim()
})

// 获取当前用户信息
const getCurrentUser = () => getUser()

// 检查是否是歌单所有者
const isPlaylistOwner = (playlistUserId) => {
  const currentUser = getCurrentUser()
  return currentUser && currentUser.id === playlistUserId
}

// 获取Token
const getToken = () => {
  return localStorage.getItem('userToken')
}

// 获取歌单列表
const fetchPlaylists = async () => {
  loading.value = true
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/playlists`, {
      method: 'GET',
      headers: {
        'Authorization': token
      }
    })
    
    const data = await response.json()
    if (data.success) {
      playlists.value = data.playlists || []
      applyVipFromPlaylistsResponse(data)

      // 为每个有音乐的歌单异步获取第一首音乐的封面
      playlists.value.forEach(playlist => {
        if (playlist.musicCount > 0) {
          fetchPlaylistFirstMusicCover(playlist.id)
        }
      })
      
    } else {
      toast.error(data.message || t('playlists.loadFailed'))
    }
  } catch (error) {
    console.error('获取歌单列表失败:', error)
    toast.error(t('playlists.loadFailed'))
  } finally {
    loading.value = false
  }
}

// 跳转到创建歌单页面
const goToCreatePlaylist = () => {
  router.push('/playlist/create')
}

// 跳转到歌单详情页
const goToPlaylistDetail = (playlistId) => {
  router.push(`/playlist/${playlistId}`)
}

// 显示编辑对话框
const showEditDialog = (playlist) => {
  currentPlaylist.value = playlist
  editForm.value = {
    id: playlist.id,
    name: playlist.name,
    description: playlist.description || ''
  }
  showEdit.value = true
}

// 关闭编辑对话框
const closeEditDialog = () => {
  showEdit.value = false
  currentPlaylist.value = null
  editForm.value = { id: null, name: '', description: '' }
}

// 处理编辑歌单
const handleEditPlaylist = async () => {
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/playlist/update`, {
      method: 'POST',
      headers: {
        'Authorization': token,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        id: editForm.value.id,
        name: editForm.value.name,
        description: editForm.value.description
      })
    })
    
    const data = await response.json()
    if (data.success) {
      toast.success(t('playlists.updateSuccess'))
      closeEditDialog()
      await fetchPlaylists()
    } else {
      toast.error(data.message || t('playlists.updateFailed'))
    }
  } catch (error) {
    console.error('歌单更新失败:', error)
    toast.error(t('playlists.updateFailed'))
  }
}

// 显示删除确认对话框
const confirmDelete = (playlist) => {
  playlistToDelete.value = playlist
  showDeleteConfirm.value = true
}

// 关闭删除确认对话框
const closeDeleteConfirm = () => {
  showDeleteConfirm.value = false
  playlistToDelete.value = null
}

// 处理删除歌单
const handleDeletePlaylist = async () => {
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/playlist/delete`, {
      method: 'POST',
      headers: {
        'Authorization': token,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        id: playlistToDelete.value.id
      })
    })
    
    const data = await response.json()
    if (data.success) {
      toast.success(t('playlists.deleteSuccess'))
      closeDeleteConfirm()
      await fetchPlaylists()
    } else {
      toast.error(data.message || t('playlists.deleteFailed'))
    }
  } catch (error) {
    console.error('歌单删除失败:', error)
    toast.error(t('playlists.deleteFailed'))
  }
}

// 获取歌单封面
const getPlaylistCover = (playlist) => {
  if (playlist.firstMusicId && playlist.firstMusicCover) {
    return `${API_CONFIG.BASE_URL}/api/music/cover/${playlist.firstMusicId}`
  }
  if (playlist.musicCount > 0) {
    fetchPlaylistFirstMusicCover(playlist.id)
  }
  const u = getCurrentUser()
  const userId = u ? u.id : 'default'
  return avatarUrl(userId)
}

// 异步获取歌单第一首音乐的封面
const fetchPlaylistFirstMusicCover = async (playlistId) => {
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/playlist/music/${playlistId}`, {
      method: 'GET',
      headers: {
        'Authorization': token
      }
    })
    
    const data = await response.json()
    if (data.success && data.musicList && data.musicList.length > 0) {
      const firstMusic = data.musicList[0]
      const playlist = playlists.value.find(p => p.id === playlistId)
      if (playlist) {
        playlist.firstMusicId = firstMusic.id
        playlist.firstMusicCover = firstMusic.coverPath
      }
    }
  } catch (error) {
    console.error('获取歌单第一首音乐封面失败:', error)
  }
}

// 处理封面加载错误
const handleCoverError = (event) => {
  const u = getCurrentUser()
  const userId = u ? u.id : 'default'
  event.target.src = avatarUrl(userId)
}

// 格式化时间
const formatTime = (timeStr) => {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  const now = new Date()
  const diff = now - date
  
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)
  
  if (minutes < 1) return t('time.justNow')
  if (minutes < 60) return t('time.minutesAgo', { count: minutes })
  if (hours < 24) return t('time.hoursAgo', { count: hours })
  if (days < 7) return t('time.daysAgo', { count: days })
  
  return date.toLocaleDateString('zh-CN')
}

function emptyImportProgress() {
  return {
    total: 0,
    finished: 0,
    imported: 0,
    existed: 0,
    failed: 0
  }
}

function resetImportResult() {
  importSummary.value = null
  importError.value = ''
  importCurrent.value = null
  importProgress.value = emptyImportProgress()
  importFinishedIndexes.clear()
}

function resetImportFlow() {
  importAbortController?.abort()
  importAbortController = null
  importFile.value = null
  parsingFile.value = false
  importDragging.value = false
  lxmcPlaylists.value = []
  importing.value = false
  importForm.value = {
    playlistId: '',
    targetMode: 'new',
    targetPlaylistId: '',
    targetPlaylistName: ''
  }
  resetImportResult()
  if (importFileInput.value) {
    importFileInput.value.value = ''
  }
}

function openImportDialog() {
  if (!getToken()) {
    openAuthDialog('login')
    return
  }
  resetImportFlow()
  showImport.value = true
}

function closeImportDialog() {
  showImport.value = false
  resetImportFlow()
}

const selectImportFile = () => {
  if (parsingFile.value || importing.value) return
  importFileInput.value?.click()
}

const handleImportFileChange = (event) => {
  const file = event.target.files?.[0]
  if (file) inspectImportFile(file)
}

const handleImportDrop = (event) => {
  importDragging.value = false
  if (parsingFile.value || importing.value) return
  const file = event.dataTransfer?.files?.[0]
  if (file) inspectImportFile(file)
}

const inspectImportFile = async (file) => {
  if (!file.name.toLowerCase().endsWith('.lxmc')) {
    toast.error(t('playlists.lxmc.invalidExtension'))
    return
  }
  importAbortController?.abort()
  const controller = new AbortController()
  importAbortController = controller
  importFile.value = file
  lxmcPlaylists.value = []
  resetImportResult()
  parsingFile.value = true

  try {
    const formData = new FormData()
    formData.append('file', file, file.name)
    const response = await fetch(`${API_CONFIG.BASE_URL}/loser/lxmc/inspect`, {
      method: 'POST',
      body: formData,
      cache: 'no-store',
      signal: controller.signal
    })
    const data = await response.json().catch(() => ({}))
    if (response.status === 401) {
      closeImportDialog()
      openAuthDialog('login')
      return
    }
    if (!response.ok || !data.success) {
      throw new Error(data.message || t('playlists.lxmc.parseFailed'))
    }

    lxmcPlaylists.value = Array.isArray(data.data?.playlists) ? data.data.playlists : []
    if (!lxmcPlaylists.value.length) {
      importError.value = t('playlists.lxmc.emptyFile')
      return
    }
    importForm.value.playlistId = lxmcPlaylists.value[0].id
    importForm.value.targetMode = playlists.value.length ? 'existing' : 'new'
    importForm.value.targetPlaylistId = playlists.value.length ? String(playlists.value[0].id) : ''
    importForm.value.targetPlaylistName = sourcePlaylistName(lxmcPlaylists.value[0])
  } catch (error) {
    if (error.name === 'AbortError') return
    console.error('落雪歌单解析失败:', error)
    importError.value = error.message || t('playlists.lxmc.parseFailed')
  } finally {
    if (importAbortController === controller) {
      parsingFile.value = false
    }
  }
}

const handleSourcePlaylistChange = () => {
  if (importForm.value.targetMode === 'new') {
    importForm.value.targetPlaylistName = importForm.value.playlistId === ALL_PLAYLISTS
      ? t('playlists.lxmc.allTargetName')
      : sourcePlaylistName(selectedSourcePlaylist.value)
  }
  importProgress.value = emptyImportProgress()
}

function sourcePlaylistName(playlist) {
  return playlist?.name?.trim() || t('playlists.lxmc.defaultName')
}

function sourceLabel(source) {
  return String(source || 'unknown').toUpperCase()
}

function statusLabel(status) {
  const key = {
    matching: 'statusMatching',
    downloading: 'statusDownloading',
    imported: 'statusImported',
    existed: 'statusExisted',
    failed: 'statusFailed'
  }[status]
  return key ? t(`playlists.lxmc.${key}`) : status
}

const startLxmcImport = async () => {
  if (!canStartImport.value || !selectedSourceSummary.value) return

  resetImportResult()
  importing.value = true
  importProgress.value = {
    ...emptyImportProgress(),
    total: selectedTrackCount.value
  }
  const controller = new AbortController()
  importAbortController = controller

  try {
    const formData = new FormData()
    formData.append('file', importFile.value, importFile.value.name)
    formData.append('playlistId', importForm.value.playlistId)
    if (importForm.value.targetMode === 'existing') {
      formData.append('targetPlaylistId', importForm.value.targetPlaylistId)
    } else {
      formData.append('targetPlaylistName', importForm.value.targetPlaylistName.trim())
    }

    const response = await fetch(`${API_CONFIG.BASE_URL}/loser/lxmc/pull`, {
      method: 'POST',
      body: formData,
      cache: 'no-store',
      signal: controller.signal
    })
    const contentType = response.headers.get('Content-Type') || ''
    if (!response.ok || contentType.includes('application/json')) {
      const data = await response.json().catch(() => ({}))
      if (response.status === 401) {
        closeImportDialog()
        openAuthDialog('login')
        return
      }
      throw new Error(data.message || t('playlists.lxmc.importFailed'))
    }
    if (!response.body) {
      throw new Error(t('playlists.lxmc.importFailed'))
    }

    await consumeSseStream(response.body, handleImportEvent)
    if (!importSummary.value && !importError.value) {
      throw new Error(t('playlists.lxmc.connectionClosed'))
    }
  } catch (error) {
    if (error.name === 'AbortError') return
    console.error('落雪歌单导入失败:', error)
    importError.value = error.message || t('playlists.lxmc.importFailed')
    toast.error(importError.value)
  } finally {
    if (importAbortController === controller) {
      importAbortController = null
      importing.value = false
    }
    if (importSummary.value) {
      await fetchPlaylists()
    }
  }
}

const handleImportEvent = (event, data) => {
  if (event === 'start') {
    importProgress.value = {
      ...importProgress.value,
      total: Number(data.total) || importProgress.value.total
    }
    return
  }
  if (event === 'track') {
    importCurrent.value = data
    const index = Number(data.index)
    const finished = ['imported', 'existed', 'failed'].includes(data.status)
    if (finished && !importFinishedIndexes.has(index)) {
      importFinishedIndexes.add(index)
      const next = {
        ...importProgress.value,
        finished: importProgress.value.finished + 1
      }
      if (data.status === 'imported') next.imported++
      if (data.status === 'existed') next.existed++
      if (data.status === 'failed') next.failed++
      importProgress.value = next
    }
    return
  }
  if (event === 'done') {
    importProgress.value = {
      ...importProgress.value,
      total: Number(data.total) || importProgress.value.total,
      finished: Number(data.total) || importProgress.value.finished,
      imported: Number(data.imported) || 0,
      existed: Number(data.existed) || 0,
      failed: Number(data.failed) || 0
    }
    importSummary.value = { ...data }
    toast.success(t('playlists.lxmc.done'))
    return
  }
  if (event === 'error') {
    importError.value = data.message || t('playlists.lxmc.importFailed')
    toast.error(importError.value)
  }
}

async function consumeSseStream(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    buffer = buffer.replace(/\r\n?/g, '\n')
    let boundary
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      emitSseBlock(block, onEvent)
    }
    if (done) break
  }
  if (buffer.trim()) emitSseBlock(buffer, onEvent)
}

function emitSseBlock(block, onEvent) {
  let event = 'message'
  const dataLines = []
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    if (field === 'data') dataLines.push(value)
  }
  if (!dataLines.length) return
  const raw = dataLines.join('\n')
  try {
    onEvent(event, JSON.parse(raw))
  } catch (error) {
    console.error('落雪歌单进度事件解析失败:', raw, error)
  }
}

onMounted(async () => {
  if (route.query.import === 'lxmc' && !getToken()) {
    openAuthDialog('login')
    return
  }
  await fetchPlaylists()
  if (route.query.import !== 'lxmc') return
  router.replace({ path: '/playlists' })
  openImportDialog()
})
</script>

<style scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-6);
}

.head__title {
  margin: 0 0 var(--n-space-1);
  font-size: clamp(1.45rem, 3vw, 1.85rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
}

.head__sub {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.head__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3);
}

.state {
  display: flex;
  justify-content: center;
  padding: var(--n-space-16) 0;
}

/* ==================== 卡片网格 ==================== */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--n-space-5);
}

.pl {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  cursor: pointer;
  outline-offset: 3px;
}

.pl__cover {
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: var(--n-surface-soft);
  border-bottom: 1px solid var(--n-line-subtle);
}

.pl__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

@media (hover: hover) {
  .pl:hover .pl__cover img {
    transform: scale(1.05);
  }
}

.pl__body {
  flex: 1;
  padding: var(--n-space-4);
}

.pl__title {
  margin: 0 0 var(--n-space-1);
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pl__meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.pl__desc {
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.pl__actions {
  display: flex;
  gap: var(--n-space-2);
  padding: var(--n-space-3) var(--n-space-4);
  border-top: 1px solid var(--n-line-subtle);
}

/* ==================== 空状态 ==================== */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: var(--n-space-3);
  padding: var(--n-space-12) var(--n-space-6);
}

.empty__icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: var(--n-radius);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.empty__title {
  margin: 0;
  font-size: var(--n-text-lg);
  font-weight: var(--n-weight-semibold);
}

.empty__text {
  margin: 0 0 var(--n-space-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== 弹窗字段 ==================== */
.field {
  margin-bottom: var(--n-space-5);
}

.field--last {
  margin-bottom: 0;
}

.field__label {
  display: block;
  margin-bottom: var(--n-space-2);
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
}

.del-text {
  margin: 0 0 var(--n-space-2);
  color: var(--n-text);
  line-height: var(--n-leading-normal);
}

.del-warn {
  margin: 0;
  color: var(--n-warning);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

/* ==================== 落雪歌单导入 ==================== */
.lxmc__lede {
  margin: 0 0 var(--n-space-5);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.lxmc-drop {
  position: relative;
  margin-bottom: var(--n-space-5);
  border: 1px dashed var(--n-line-strong);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface-soft);
  overflow: hidden;
  transition:
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease),
    box-shadow var(--n-duration-fast) var(--n-ease);
}

.lxmc-drop--ready {
  border-color: var(--n-accent-line);
  background: var(--n-accent-soft);
}

.lxmc-drop--over {
  border-color: var(--n-accent);
  background: var(--n-accent-soft);
  box-shadow: 0 0 0 3px rgba(95, 208, 224, 0.12);
}

.lxmc-drop__input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.lxmc-drop__hit {
  display: flex;
  width: 100%;
  min-height: 148px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-2);
  padding: var(--n-space-6);
  color: var(--n-text);
  text-align: center;
  cursor: pointer;
}

.lxmc-drop__hit:disabled {
  cursor: wait;
}

.lxmc-drop__icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: var(--n-space-1);
  border-radius: var(--n-radius-circle);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.lxmc-drop__title {
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
}

.lxmc-drop__hint {
  max-width: 100%;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lxmc-sources {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--n-space-2);
  margin-top: var(--n-space-3);
}

.lxmc-sources__label {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lxmc-targets {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-3);
}

.lxmc-target {
  display: flex;
  align-items: flex-start;
  gap: var(--n-space-2);
  padding: var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  cursor: pointer;
  transition:
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

.lxmc-target--active {
  border-color: var(--n-accent-line);
  background: var(--n-accent-soft);
}

.lxmc-target input {
  margin-top: 3px;
  accent-color: var(--n-accent);
}

.lxmc-target span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.lxmc-target strong {
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
}

.lxmc-target small {
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
}

.lxmc-target-name {
  margin-top: var(--n-space-2);
}

.lxmc-progress {
  margin-top: var(--n-space-5);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line-subtle);
  border-radius: var(--n-radius);
  background: var(--n-surface-soft);
}

.lxmc-progress__head {
  display: flex;
  justify-content: space-between;
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-2);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
}

.lxmc-progress__head span:last-child {
  color: var(--n-text-muted);
  font-variant-numeric: tabular-nums;
}

.lxmc-progress__bar {
  height: 8px;
  overflow: hidden;
  border-radius: var(--n-radius-circle);
  background: var(--n-surface-active);
}

.lxmc-progress__bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--n-gradient-accent);
  transition: width var(--n-duration) var(--n-ease);
}

.lxmc-progress__current {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--n-space-2);
  margin: var(--n-space-3) 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.lxmc-progress__current strong {
  color: var(--n-text);
  font-weight: var(--n-weight-semibold);
}

.lxmc-stats {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-2);
  margin-top: var(--n-space-3);
}

.lxmc-stats span {
  padding: 4px var(--n-space-2);
  border-radius: var(--n-radius-xs);
  background: var(--n-surface-active);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
}

.lxmc-progress__hint,
.lxmc-progress__error {
  margin: var(--n-space-3) 0 0;
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
}

.lxmc-progress__hint {
  color: var(--n-text-faint);
}

.lxmc-progress__error {
  color: var(--n-danger);
}

@media (max-width: 560px) {
  .head__actions {
    width: 100%;
  }

  .head__actions :deep(.n-btn) {
    flex: 1;
  }

  .lxmc-targets {
    grid-template-columns: 1fr;
  }
}
</style>

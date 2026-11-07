<template>
  <div class="global-player" :class="{ 'global-player--chrome-dark': chromeDark }">
    <!-- 顶部细进度条：贴住播放条上沿，可点击/拖动跳转 -->
    <div v-if="currentMusic" class="gp-seek">
      <div class="gp-seek__track" aria-hidden="true">
        <div class="gp-seek__fill" :style="{ width: progressPercent + '%' }" />
      </div>
      <input
        type="range"
        class="gp-seek__input"
        :value="progress"
        :max="duration || 0"
        min="0"
        step="0.1"
        :aria-label="$t('player.progressAria')"
        @input="onProgressChange"
      />
    </div>

    <div class="gp-grid">
      <!-- 左：封面 + 曲名/歌手 + 收藏 -->
      <div class="gp-track">
        <!-- 封面与文字合成同一个按钮：手机上触摸目标更大（整块可点开播放页） -->
        <button
          type="button"
          class="gp-track__open"
          :disabled="!currentMusic"
          :aria-label="currentMusic ? $t('player.openPageAria', { title: currentMusic.title }) : $t('player.nothingPlaying')"
          @click="goToDetails"
        >
          <span class="gp-track__cover">
            <img
              v-if="currentMusic"
              :src="getCoverUrl(currentMusic.id)"
              :alt="currentMusic.title"
              class="gp-track__img"
              @error="handleImageError"
            />
            <span v-else class="gp-track__ph"><NIcon name="music-2" :size="20" /></span>
          </span>

          <span class="gp-track__meta">
            <span class="gp-track__title" :class="{ 'is-placeholder': !currentMusic }">
              {{ currentMusic ? currentMusic.title : $t('player.selectMusic') }}
            </span>
            <span class="gp-track__artist" :class="{ 'is-placeholder': !currentMusic }">
              {{ currentMusic ? currentMusic.artist : '—' }}
            </span>
          </span>
        </button>

        <button
          type="button"
          class="gp-icon gp-track__comment"
          :disabled="!currentMusic"
          :aria-label="$t('player.viewComments')"
          :title="$t('player.viewComments')"
          @click="goToComments"
        >
          <NIcon name="message" :size="17" />
          <span v-if="commentCount" class="gp-track__comment-badge">
            {{ commentCount > 99 ? '99+' : commentCount }}
          </span>
        </button>

        <button
          type="button"
          class="gp-icon gp-track__fav"
          :class="{ 'is-on': isFavorite }"
          :disabled="!currentMusic"
          :aria-label="isFavorite ? $t('track.unfavorite') : $t('track.favorite')"
          :aria-pressed="isFavorite"
          @click="toggleFavorite"
        >
          <NIcon :name="isFavorite ? 'heart' : 'heart-off'" :size="17" />
          <span v-if="favoriteCount !== null" class="gp-track__fav-count">
            {{ favoriteCount > 9999 ? '9999+' : favoriteCount }}
          </span>
        </button>
      </div>

      <!-- 中：播放控制 -->
      <div class="gp-controls">
        <button
          type="button"
          class="gp-icon gp-icon--lg"
          :title="$t('player.prev')"
          :aria-label="$t('player.prev')"
          :disabled="!currentMusic"
          @click="playPrevious()"
        >
          <NIcon name="skip-back" :size="20" />
        </button>

        <button
          type="button"
          class="gp-play"
          :disabled="!currentMusic"
          :aria-label="isPlaying && currentMusic ? $t('track.pause') : $t('track.play')"
          :aria-pressed="isPlaying && currentMusic"
          @click="togglePlayPause"
        >
          <NIcon :name="isPlaying && currentMusic ? 'pause' : 'play'" :size="21" />
        </button>

        <button
          type="button"
          class="gp-icon gp-icon--lg"
          :title="$t('player.next')"
          :aria-label="$t('player.next')"
          :disabled="!currentMusic"
          @click="playNext()"
        >
          <NIcon name="skip-forward" :size="20" />
        </button>
      </div>

      <!-- 右：时间 + 此刻（歌词优先，否则频谱）+ 播放列表
           对齐 ArchoeraMusic 的 _buildRightSection / _BarInfoArea：
           固定宽度列，时间在上，下方 120×12 一块「有歌词显示歌词，
           没歌词显示迷你频谱」。两块都是确定宽度，不会挤压同排控件。 -->
      <div class="gp-right">
        <div class="gp-now">
          <span class="gp-now__time">
            <b>{{ formatTime(currentTime) }}</b>
            <i>/</i>{{ formatTime(duration) }}
          </span>
          <div class="gp-now__viz">
            <MiniLyric
              v-if="barLyric"
              class="gp-now__lyric"
              :text="barLyric.display"
              :playing="isPlaying"
              :title="barLyric.full"
            />
            <SpectrumCanvas
              v-else-if="currentMusic"
              class="gp-now__spectrum"
              :bars="22"
              :height="12"
              :active="isPlaying"
            />
          </div>
        </div>

        <button
          type="button"
          class="gp-icon gp-icon--lg gp-mode"
          :title="getPlaybackModeTitle()"
          :aria-label="getPlaybackModeTitle()"
          @click="togglePlaybackMode"
        >
          <NIcon :name="modeIcon" :size="18" />
        </button>

        <button
          type="button"
          class="gp-icon gp-icon--lg"
          :title="$t('player.playlist')"
          :aria-label="$t('player.playlist')"
          @click="togglePlaylist"
        >
          <NIcon name="list-music" :size="20" />
        </button>
      </div>
    </div>

    <!-- 音频元素：全站唯一播放源（crossorigin 不可移除，跨域音频接 Web Audio 会静音） -->
    <audio
      v-if="currentMusic"
      ref="audioPlayer"
      :src="audioSrc"
      crossorigin="anonymous"
      @ended="onAudioEnded"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @error="onAudioError"
    />

    <!-- 播放列表弹层 -->
    <Transition name="gp-pop">
      <div v-if="showPlaylist" class="gp-pop">
        <header class="gp-pop__head">
          <h3 class="gp-pop__title">
            {{ $t('player.playlist') }}
            <span v-if="playlist.length" class="gp-pop__count">{{ $t('player.songCount', { count: playlist.length }) }}</span>
          </h3>
          <div class="gp-pop__head-actions">
            <button
              type="button"
              class="gp-pop__clear"
              :disabled="playlist.length === 0"
              @click="clearPlaylist"
            >
              {{ $t('player.clear') }}
            </button>
            <button
              type="button"
              class="gp-icon gp-icon--sm"
              :aria-label="$t('player.closePlaylist')"
              @click="togglePlaylist"
            >
              <NIcon name="close" :size="16" />
            </button>
          </div>
        </header>

        <div class="gp-pop__list">
          <p v-if="playlist.length === 0" class="gp-pop__empty">
            {{ $t('player.playlistEmpty') }}
          </p>
          <button
            v-for="(item, index) in playlist"
            :key="item.id"
            type="button"
            class="gp-pop__item"
            :class="{ 'is-current': currentMusic && item.id === currentMusic.id }"
            @click="playFromPlaylist(index)"
          >
            <span class="gp-pop__idx">
              <NIcon
                v-if="currentMusic && item.id === currentMusic.id"
                name="volume-2"
                :size="15"
              />
              <template v-else>{{ index + 1 }}</template>
            </span>
            <span class="gp-pop__info">
              <span class="gp-pop__name">{{ item.title }}</span>
              <span class="gp-pop__artist">{{ item.artist }}</span>
            </span>
          </button>
        </div>
      </div>
    </Transition>
  </div>

  <!-- 清空播放列表确认 -->
  <NModal v-model="showClearConfirm" :title="$t('player.clearConfirmTitle')" size="sm">
    <p>{{ $t('player.clearConfirmDesc') }}</p>
    <template #footer>
      <NButton variant="ghost" @click="showClearConfirm = false">{{ $t('common.cancel') }}</NButton>
      <NButton variant="danger" @click="confirmClearPlaylist">{{ $t('player.clearConfirmOk') }}</NButton>
    </template>
  </NModal>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { resolveMediaUrl } from '@/utils/mediaUrl.js'
import { useToast } from 'vue-toastification'
import { attachAudioElement, unlockAudioAnalyser } from '@/composables/useAudioAnalyser'
import { clearUrlHash } from '@/utils/routerHistory'
import SpectrumCanvas from '@/components/SpectrumCanvas.vue'
import MiniLyric from '@/components/MiniLyric.vue'
import { fetchComments } from '@/api/comments'
import { t } from '@/i18n'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NModal } from '@/ui'

defineProps({
  chromeDark: {
    type: Boolean,
    default: false
  }
})

const toast = useToast()

const router = useRouter()

// 从localStorage获取当前播放的音乐信息
const currentMusic = ref(JSON.parse(localStorage.getItem('currentPlayingMusic')) || null)
const audioPlayer = ref(null)
// 播放地址由签发接口一次性 challenge 换取，不再直接拼 /api/music/file/{id}
const audioSrc = ref('')
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)
const lyrics = ref('')
const parsedLyrics = ref([])

// 播放模式相关状态
const playbackMode = ref('list_repeat') // 'list_repeat', 'single_repeat', 'shuffle'
const playlist = ref([])
const isFavorite = ref(false) // 当前音乐是否已收藏
const favoriteCount = ref(null) // 当前音乐收藏数，未知时为 null
const commentCount = ref(0) // 当前音乐评论数
const showClearConfirm = ref(false) // 是否显示清空确认模态框

/** 当前播放模式对应的图标名（图标注册表语义名） */
const modeIcon = computed(() => {
  if (playbackMode.value === 'single_repeat') return 'repeat-1'
  if (playbackMode.value === 'shuffle') return 'shuffle'
  return 'repeat'
})

/** 进度百分比（顶部细进度条填充宽度） */
const progressPercent = computed(() => {
  const total = duration.value
  if (!total || !Number.isFinite(total) || total <= 0) return 0
  return Math.max(0, Math.min(100, (progress.value / total) * 100))
})

// 获取用户token
const getToken = () => {
  return localStorage.getItem('userToken')
}

// 切换收藏状态
const toggleFavorite = async () => {
  if (!currentMusic.value) return
  
  const token = getToken()
  if (!token) {
    toast.error(t('common.needLogin'))
    return
  }
  
  if (isFavorite.value) {
    // 取消收藏
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites/${currentMusic.value.id}`, {
        method: 'DELETE',
        headers: {
          'Authorization': token
        }
      })
      
      const data = await response.json()
      if (data.success) {
        isFavorite.value = false
        if (favoriteCount.value !== null) {
          favoriteCount.value = Math.max(0, favoriteCount.value - 1)
        }
        toast.success(t('player.unfavoriteSuccess'))
      } else {
        toast.error(t('player.unfavoriteFailed') + ': ' + data.message)
      }
    } catch (error) {
      console.error('取消收藏失败:', error)
      toast.error(t('player.unfavoriteFailed'))
    }
  } else {
    // 添加收藏
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
        method: 'POST',
        headers: {
          'Authorization': token,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ musicId: currentMusic.value.id })
      })
      
      const data = await response.json()
      if (data.success) {
        isFavorite.value = true
        if (favoriteCount.value !== null) {
          favoriteCount.value += 1
        }
        toast.success(t('player.favoriteSuccess'))
      } else {
        toast.error(t('player.favoriteFailed') + ': ' + data.message)
      }
    } catch (error) {
      console.error('收藏失败:', error)
      toast.error(t('player.favoriteFailed'))
    }
  }
}

// 检查当前音乐是否已收藏
const checkFavoriteStatus = async () => {
  if (!currentMusic.value) return
  
  const token = getToken()
  if (!token) {
    isFavorite.value = false
    return
  }
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
      method: 'GET',
      headers: {
        'Authorization': token
      }
    })
    
    const data = await response.json()
    if (data.success && data.favorites) {
      isFavorite.value = data.favorites.some(m => m.id === currentMusic.value.id)
    }
  } catch (error) {
    console.error('获取收藏状态失败:', error)
  }
}

// 播放/暂停控制
const togglePlayPause = () => {
  // 播放是明确的手势路径：在此解锁音频分析（建立 AudioContext 并 resume）
  if (!isPlaying.value) unlockAudioAnalyser()

  if (audioPlayer.value && currentMusic.value) {
    if (isPlaying.value) {
      // 暂停：直接暂停，避免重音
      isPlaying.value = false
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      // 立即暂停音频并静音
      pauseAudio()
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    } else {
      // 播放：立即更新状态，然后起播
      isPlaying.value = true
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      resumeAudio()
      safePlay(audioPlayer.value);
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    }
  }
}

/**
 * 恢复音量。
 * 暂停时会把音量置 0 以避免重音，因此每次起播前都要恢复。
 * 不做渐变：后台标签页里的任何逐帧/定时动画都可能被浏览器限流，
 * 一旦停在中间值就会出现「几乎没声音」的问题（上游 issue #115）。
 */
const resumeAudio = (audioElement = audioPlayer.value) => {
  if (audioElement) audioElement.volume = 1
}

/** 暂停并静音，避免暂停瞬间的重音。 */
const pauseAudio = (audioElement = audioPlayer.value) => {
  if (!audioElement) return
  audioElement.volume = 0
  audioElement.pause()
}

/**
 * 安全的 play()：必须消费返回的 Promise。
 *
 * 不 catch 的话，任何「被后续 load/play 打断」都会变成
 *   Uncaught (in promise) AbortError: The play() request was interrupted
 *   by a new load request
 * 这是浏览器正常行为（比如 <audio> 的 :src 刚被 Vue 改写），不是故障。
 *
 * NotAllowedError 才是真问题（无用户手势时的自动播放限制），
 * 此时要把播放态回滚，否则界面会显示「正在播放」却毫无声音。
 */
const safePlay = (el) => {
  if (!el) return
  let result
  try {
    result = el.play()
  } catch (err) {
    isPlaying.value = false
    updateGlobalPlayerState()
    broadcastPlayerStateChange()
    return
  }
  if (result?.catch) {
    result.catch((err) => {
      if (err?.name === 'AbortError') return // 被新的 load/play 取代，正常
      isPlaying.value = false
      updateGlobalPlayerState()
      broadcastPlayerStateChange()
    })
  }
}

// 音频结束事件
// 音频结束事件 - 现在根据播放模式处理
const onAudioEnded = () => {
  if (playbackMode.value === 'single_repeat') {
    // 单曲循环：重新播放当前歌曲
    if (audioPlayer.value && currentMusic.value) {
      audioPlayer.value.currentTime = 0.2
      safePlay(audioPlayer.value)
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    }
  } else if (playbackMode.value === 'shuffle' && playlist.value.length > 1) {
    // 随机播放：播放列表中的随机歌曲
    playNextInShuffle(true) // 标记：来自 ended
  } else {
    // 列表循环：播放下一首
    playNext(true) // 标记：来自 ended
  }
  
  updateGlobalPlayerState()
  // 广播播放状态变化
  broadcastPlayerStateChange()
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    progress.value = currentTime.value
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 歌词高亮由 activeLyricIndex / barLyric 两个 computed 派生，此处无需处理
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
    updateGlobalPlayerState()
    // 广播播放状态变化
    broadcastPlayerStateChange()
    
    
    // 加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
    }
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
  }
}

// 进度条变化
const onProgressChange = (event) => {
  seekTo(parseFloat(event.target.value))
}

/**
 * 跳转到指定秒数。
 * 底部播放条与全屏播放页（经 playerCommand）共用同一条 seek 路径，
 * 保证 seek 后状态广播、媒体会话位置都与既有行为一致。
 */
const seekTo = (seconds) => {
  if (!audioPlayer.value || !currentMusic.value) return
  const total = duration.value || audioPlayer.value.duration || 0
  const target = Math.max(0, Math.min(Number(seconds) || 0, total || Number(seconds) || 0))

  audioPlayer.value.currentTime = target
  currentTime.value = target
  progress.value = target
  updateGlobalPlayerState()
  // 广播播放状态变化
  broadcastPlayerStateChange()
  // 更新媒体会话播放位置
  updateMediaSessionPositionState()
}

/**
 * 播放指令分发（来自全屏播放页等外部 UI）。
 * 只做「动作 → 既有函数」的映射，不新增任何播放逻辑，
 * 因此不会改变既有契约与行为。
 */
const handlePlayerCommand = (e) => {
  const { action, time, index, track, musicId } = e?.detail || {}
  switch (action) {
    case 'playMusic':
      // 播放页直接进入 /detail/:id 时按 id 起播（上游契约）：
      // 队列里有就切过去，没有就取详情补进队列再切。统一直落 switchToTrack。
      playMusicById(musicId)
      break
    case 'toggle':
      togglePlayPause()
      break
    case 'play':
      if (!isPlaying.value) togglePlayPause()
      break
    case 'pause':
      if (isPlaying.value) togglePlayPause()
      break
    case 'next':
      playNext()
      break
    case 'prev':
      playPrevious()
      break
    case 'seek':
      seekTo(time)
      break
    case 'cycleMode':
      togglePlaybackMode()
      break
    case 'playTrack':
      // 全站统一入口：由页面经 usePlaybackBridge.playTrack/playTracks 发来
      switchToTrack(track)
      break
    case 'playIndex':
      playFromPlaylist(index)
      break
    case 'clearPlaylist':
      clearPlaylist()
      break
    default:
      break
  }
}

// 更新全局播放器状态
const updateGlobalPlayerState = () => {
  const state = {
    isPlaying: isPlaying.value,
    currentTime: currentTime.value,
    duration: duration.value,
    playbackMode: playbackMode.value
  };
  localStorage.setItem('globalPlayerState', JSON.stringify(state));
}

// 广播播放器状态变化
const broadcastPlayerStateChange = () => {
  // 创建自定义事件来通知播放状态变化。
  // source 标记：GlobalPlayer 自身也监听 playerStateChange（用于响应外部指令），
  // 必须能区分「自己的广播」与「外部指令」，否则处理自己的广播会形成回声，
  // 出现「界面已经切到新歌、又被旧事件拉回去」。
  const event = new CustomEvent('playerStateChange', {
    detail: {
      source: 'globalPlayer',
      isPlaying: isPlaying.value,
      currentTime: currentTime.value,
      duration: duration.value,
      playbackMode: playbackMode.value,
      currentMusic: currentMusic.value
    }
  });
  window.dispatchEvent(event);
}

// 格式化时间（秒转分:秒）
const formatTime = (seconds) => {
  if (isNaN(seconds) || seconds < 0) return '0:00'
  
  const min = Math.floor(seconds / 60)
  const sec = Math.floor(seconds % 60)
  return `${min}:${sec < 10 ? '0' : ''}${sec}`
}

// 跳转到播放页并展开评论
const goToComments = () => {
  if (currentMusic.value) {
    router.push({ path: `/detail/${currentMusic.value.id}`, query: { comments: '1' } })
  }
}

// 跳转到音乐详情页面
const goToDetails = () => {
  if (currentMusic.value) {
    // 跳转到音乐详情页面
    router.push(`/detail/${currentMusic.value.id}`)
  }
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 拉取当前音乐评论数；切歌后旧请求不写回
const loadCommentCount = async (musicId) => {
  try {
    const data = await fetchComments(musicId, { pageSize: 1 })
    if (String(currentMusic.value?.id) !== String(musicId)) return
    commentCount.value = Number(data?.totalComments) || 0
  } catch (error) {
    if (String(currentMusic.value?.id) !== String(musicId)) return
    commentCount.value = 0
    console.warn('加载评论数失败:', error)
  }
}

// 从音乐详情同步收藏数（未知时保持 null，不显示 0）
const syncFavoriteCount = (music) => {
  const count = Number(music?.favoriteCount)
  favoriteCount.value = Number.isFinite(count) ? count : null
}

// 通过音乐详情接口加载歌词
const loadLyrics = async (musicId) => {
  const current = currentMusic.value
  if (current && String(current.id) === String(musicId) && typeof current.lyrics === 'string') {
    lyrics.value = current.lyrics
    parseLrcLyrics(current.lyrics)
    syncFavoriteCount(current)
    return
  }

  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId}`)
    // 期间已经切歌：丢弃过期歌词，别把上一首的歌词盖到新曲目上
    if (String(currentMusic.value?.id) !== String(musicId)) return
    if (response.ok) {
      const data = await response.json()
      if (String(currentMusic.value?.id) !== String(musicId)) return
      if (data.success) {
        syncFavoriteCount(data.data)
        const lyricsText = typeof data.data?.lyrics === 'string' ? data.data.lyrics : ''
        lyrics.value = lyricsText
        parseLrcLyrics(lyricsText)
      } else {
        lyrics.value = ''
        parsedLyrics.value = []
      }
    } else {
      lyrics.value = ''
      parsedLyrics.value = []
    }
  } catch (error) {
    console.error('加载歌词失败:', error)
    lyrics.value = ''
    parsedLyrics.value = []
  }
}

// 解析LRC歌词格式
const parseLrcLyrics = (lrcText) => {
  if (!lrcText) {
    parsedLyrics.value = []
    return
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
  parsedLyrics.value = parsed
}

/**
 * 当前时间对应的歌词行下标（-1 = 前奏，还没到第一句）。
 * 播放条右侧「此刻」区域与全屏播放页共用同一份 parsedLyrics。
 */
const activeLyricIndex = computed(() => {
  const lines = parsedLyrics.value
  for (let i = lines.length - 1; i >= 0; i--) {
    if (currentTime.value >= lines[i].time) return i
  }
  return -1
})

/**
 * 播放条右侧「此刻」区域要显示的歌词。
 * 对齐 ArchoeraMusic 的 _BarInfoArea：有歌词就优先显示歌词，返回 null
 * 时调用方降级为迷你频谱。前奏与空行都返回 null，避免出现一块空白。
 */
const barLyric = computed(() => {
  const idx = activeLyricIndex.value
  if (idx < 0) return null
  const line = parsedLyrics.value[idx]
  const text = (line?.text || '').trim()
  if (!text) return null
  const translation = (line?.translation || '').trim()
  return {
    text,
    translation,
    // 对齐原版格式「原文（翻译）」；超宽时由 MiniLyric 自动循环滚动
    display: translation ? `${text}（${translation}）` : text,
    full: translation ? `${text}\n${translation}` : text,
  }
})

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`
}

// 清空播放列表
const clearPlaylist = () => {
  if (playlist.value.length === 0) {
    toast.warning(t('player.playlistAlreadyEmpty'))
    return
  }
  
  // 显示确认模态框
  showClearConfirm.value = true
}

// 确认清空播放列表
/**
 * 清空播放列表 —— 连同当前曲目一起清掉（「全部停止」语义）。
 * 注意：底部播放条的显隐由 App 依据 localStorage.currentPlayingMusic 决定，
 * 因此这里必须把它也一并清除，否则列表空了、播放条却仍停在那里。
 */
const confirmClearPlaylist = () => {
  // 先停掉正在播放的音频（<audio> 会在 currentMusic 置空后随之卸载）
  pauseAudio()

  playlist.value = []
  localStorage.setItem('globalPlaylist', JSON.stringify([]))

  // 清空当前曲目与所有派生状态
  currentMusic.value = null
  audioSrc.value = ''
  isPlaying.value = false
  currentTime.value = 0
  progress.value = 0
  duration.value = 0
  parsedLyrics.value = []
  lyrics.value = ''
  isFavorite.value = false
  favoriteCount.value = null
  commentCount.value = 0
  showPlaylist.value = false

  localStorage.removeItem('currentPlayingMusic')
  updateGlobalPlayerState()

  // 广播：currentMusic 为 null，播放页与 App 外壳据此收回播放条
  broadcastPlayerStateChange()
  window.dispatchEvent(
    new CustomEvent('playlistUpdated', { detail: { playlist: [] } })
  )

  // 媒体会话
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = null
    navigator.mediaSession.playbackState = 'none'
  }

  toast.success(t('player.playlistCleared'))
  showClearConfirm.value = false
}

// 播放模式切换函数
const togglePlaybackMode = () => {
  if (playbackMode.value === 'list_repeat') {
    playbackMode.value = 'single_repeat'
  } else if (playbackMode.value === 'single_repeat') {
    playbackMode.value = 'shuffle'
  } else {
    playbackMode.value = 'list_repeat'
  }
  // 保存播放模式到localStorage
  localStorage.setItem('playbackMode', playbackMode.value)
}

// 获取播放模式标题
const getPlaybackModeTitle = () => {
  if (playbackMode.value === 'list_repeat') {
    return t('player.modeListLoop')
  } else if (playbackMode.value === 'single_repeat') {
    return t('player.modeSingleLoop')
  } else {
    return t('player.modeShuffle')
  }
}

// 播放列表显示状态
const showPlaylist = ref(false)

// 切换播放列表显示
const togglePlaylist = () => {
  showPlaylist.value = !showPlaylist.value
}

// 从播放列表播放
const playFromPlaylist = (index) => {
  if (playlist.value[index]) {
    // 切歌 + 起播统一走 switchToTrack（唯一写入点）
    switchToTrack(playlist.value[index])
  }
  // 关闭播放列表
  showPlaylist.value = false
}

// 从localStorage加载播放模式
const loadPlaybackMode = () => {
  const savedMode = localStorage.getItem('playbackMode')
  if (savedMode && ['list_repeat', 'single_repeat', 'shuffle'].includes(savedMode)) {
    playbackMode.value = savedMode
  } else {
    // 默认设置为列表循环
    playbackMode.value = 'list_repeat'
    localStorage.setItem('playbackMode', 'list_repeat')
  }
}

/**
 * 播放下一首。
 * @param {boolean} fromEnded 是否由 ended 事件触发（仅影响随机模式是否单独处理）
 */
const playNext = (fromEnded = false) => {
  if (!currentMusic.value || playlist.value.length === 0) return

  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  let nextIndex

  if (playbackMode.value === 'shuffle') {
    nextIndex = getRandomIndex(currentIndex)
  } else {
    // currentIndex 为 -1（当前曲目不在列表里）时，从列表头开始
    nextIndex = currentIndex === -1 ? 0 : (currentIndex + 1) % playlist.value.length
  }

  if (nextIndex !== -1 && playlist.value[nextIndex]) {
    switchToTrack(playlist.value[nextIndex])
  }
}

// 播放上一首
const playPrevious = (fromEnded = false) => {
  if (!currentMusic.value || playlist.value.length === 0) return

  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  let prevIndex

  if (playbackMode.value === 'shuffle') {
    prevIndex = getRandomIndex(currentIndex)
  } else {
    prevIndex = currentIndex === -1
      ? playlist.value.length - 1
      : (currentIndex - 1 + playlist.value.length) % playlist.value.length
  }

  if (prevIndex !== -1 && playlist.value[prevIndex]) {
    switchToTrack(playlist.value[prevIndex])
  }
}

// 获取随机索引（排除当前索引）
const getRandomIndex = (currentIndex) => {
  if (playlist.value.length <= 1) return currentIndex
  
  let randomIndex
  do {
    randomIndex = Math.floor(Math.random() * playlist.value.length)
  } while (randomIndex === currentIndex && playlist.value.length > 1)
  
  return randomIndex
}

// 播放下一首（随机模式）
const playNextInShuffle = (fromEnded = false) => {
  if (!currentMusic.value || playlist.value.length === 0) return

  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  const nextIndex = getRandomIndex(currentIndex)

  if (nextIndex !== -1 && playlist.value[nextIndex]) {
    switchToTrack(playlist.value[nextIndex])
  }
}



// 监听localStorage变化，响应播放音乐的改变
const handleStorageChange = (e) => {
  if (e.key === 'currentPlayingMusic') {
    // 只有在音乐实际改变时才重置播放器
    const newMusic = e.newValue ? JSON.parse(e.newValue) : null;
    if (newMusic && (!currentMusic.value || String(newMusic.id) !== String(currentMusic.value.id))) {
      // 跨标签页切歌：同样收敛到 switchToTrack，避免再手写 src/load 的竞态。
      // 尊重另一个标签页写入的播放状态（可能是暂停着切歌）。
      let shouldPlay = true
      try {
        const stored = JSON.parse(localStorage.getItem('globalPlayerState') || 'null')
        if (stored && typeof stored.isPlaying === 'boolean') shouldPlay = stored.isPlaying
      } catch {
        /* ignore */
      }

      switchToTrack(newMusic).then(() => {
        if (!shouldPlay) {
          isPlaying.value = false
          pauseAudio()
          updateGlobalPlayerState()
          broadcastPlayerStateChange()
          updateMediaSessionPlaybackState()
        }
      })

      // 检查收藏状态
      checkFavoriteStatus()
    } else if (!e.newValue) {
      // 没有音乐了，暂停播放器
      currentMusic.value = null;
      audioSrc.value = '';
      if (audioPlayer.value) {
        audioPlayer.value.pause();
        // 重置播放时间
        currentTime.value = 0;
        progress.value = 0;
        isPlaying.value = false;
        duration.value = 0;
        updateGlobalPlayerState();
        // 广播播放状态变化
        broadcastPlayerStateChange();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
        // 更新媒体会话播放位置
        updateMediaSessionPositionState()
      }
      // 清空歌词
      lyrics.value = ''
      parsedLyrics.value = []
      favoriteCount.value = null
      commentCount.value = 0
      isFavorite.value = false
      // 清除媒体会话元数据
      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = null
      }
    }
  } else if (e.key === 'globalPlayerState') {
    // 从播放页面接收状态更新
    if (e.newValue) {
      const state = JSON.parse(e.newValue);
      // 更新播放器状态，无论audio元素是否准备好
      const previousIsPlaying = isPlaying.value;
      isPlaying.value = state.isPlaying;
      currentTime.value = state.currentTime;
      duration.value = state.duration;
      progress.value = state.currentTime;
      
      // 如果有audio元素则同步操作
      if (audioPlayer.value && currentMusic.value) {
        // 等待音频加载完成再执行操作
        const updateWhenReady = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying && !previousIsPlaying) {
              // 如果状态从暂停变为播放，则开始播放音频
              resumeAudio();
              safePlay(audioPlayer.value);
            } else if (!state.isPlaying) {
              // 如果状态变为暂停，则暂停音频
              pauseAudio()
            }
          }
        };
        
        if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
          updateWhenReady();
        } else {
          audioPlayer.value.addEventListener('loadeddata', updateWhenReady, { once: true });
        }
        updateGlobalPlayerState();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
      } else if (currentMusic.value) {
        // 如果audio元素还没准备好，等待并执行操作
        const handleMetadata = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying && !previousIsPlaying) {
              // 如果状态从暂停变为播放，则开始播放音频
              resumeAudio();
              safePlay(audioPlayer.value);
            } else if (!state.isPlaying) {
              // 如果状态变为暂停，则暂停音频
              pauseAudio()
            }
          }
        };
        
        audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState()
    }
  } else if (e.key === 'globalPlaylist') {
    // 当播放列表更新时，同步更新本地播放列表
    if (e.newValue) {
      try {
        const newPlaylist = JSON.parse(e.newValue);
        playlist.value = newPlaylist;
      } catch (error) {
        console.error('解析播放列表失败:', error);
      }
    }
  }
}

// 播放地址解析与失效自愈
// ------------------------------------------------------------
// 签发接口可能因网络抖动返回失败，此时重新取一次并恢复到原播放位置。
let audioSrcSeq = 0
let audioSrcRetry = 0

const refreshAudioSrc = async (track) => {
  if (!track || track.id == null) {
    audioSrc.value = ''
    return
  }
  const seq = ++audioSrcSeq
  try {
    const url = await resolveMediaUrl(track.id)
    if (seq !== audioSrcSeq) return
    audioSrc.value = url
    audioSrcRetry = 0
  } catch (error) {
    if (seq !== audioSrcSeq) return
    console.error('[player] 获取播放地址失败', error)
    audioSrc.value = ''
    toast.error(t('player.playUrlFailed'))
  }
}

const onAudioError = async () => {
  const track = currentMusic.value
  if (!track || audioSrcRetry >= 2) return
  audioSrcRetry += 1

  const el = audioPlayer.value
  const resumeAt = el && Number.isFinite(el.currentTime) && el.currentTime > 0 ? el.currentTime : currentTime.value
  const wasPlaying = isPlaying.value

  await refreshAudioSrc(track)
  await nextTick()

  if (!audioPlayer.value || !audioSrc.value) return
  const applyResume = () => {
    try {
      audioPlayer.value.currentTime = resumeAt
    } catch {
      /* ignore */
    }
  }
  applyResume()
  if (audioPlayer.value.readyState < 1) {
    audioPlayer.value.addEventListener('loadedmetadata', applyResume, { once: true })
  }
  if (wasPlaying) safePlay(audioPlayer.value)
}

// 强制播放处理函数
/**
 * 强制播放当前曲目（分享链接的 #play= / forcePlay 事件会走到这里）。
 *
 * 关键：<audio> 的 src 由模板的 :src 绑定到 currentMusic.id，Vue 会在
 * 下一次 DOM 补丁里写入。这里【不要】再手动 `src = ...` 或 `load()` ——
 * 那会和 Vue 的补丁互相打断：浏览器一开始新的资源加载，上一次挂起的
 * play() Promise 就会以
 *   AbortError: The play() request was interrupted by a new load request
 * 拒绝（而且可能谁都没在播）。等 DOM 打完补丁再起播即可。
 */
const handleForcePlay = async () => {
  await nextTick()
  const el = audioPlayer.value
  if (!el || !currentMusic.value) return

  isPlaying.value = true
  currentTime.value = 0.1
  progress.value = 0.1
  updateGlobalPlayerState()

  // 元数据未就绪时设置 currentTime 可能抛 InvalidStateError，等 loadedmetadata 再补
  const applyStart = () => {
    try {
      el.currentTime = 0.1
    } catch {
      /* ignore */
    }
  }
  applyStart()
  if (el.readyState < 1) {
    el.addEventListener('loadedmetadata', applyStart, { once: true })
  }

  resumeAudio(el)
  safePlay(el)
  broadcastPlayerStateChange()
  updateMediaSessionPlaybackState()
}

/**
 * 统一切歌 / 起播入口（本组件唯一允许改写「当前曲目」的地方）。
 * ------------------------------------------------------------
 * 为什么必须收敛到一处：此前「切歌」分散在 hash、外部 playerStateChange、
 * forcePlay 以及各页面直接写 localStorage 等多条路径里，每条都在不同时机
 * 改 currentMusic、手动改 <audio>.src 并调 load()，彼此抢同一个媒体元素，
 * 既报 AbortError，也会「点新歌又跳回上一首」。现在：
 *   - 只改响应式状态；
 *   - 资源加载完全交给模板的 :src 绑定；
 *   - 等 DOM 打完补丁再起播（handleForcePlay），不再手动 load()。
 */
const switchToTrack = async (track) => {
  if (!track || track.id == null) return

  const isSame = currentMusic.value && String(currentMusic.value.id) === String(track.id)

  if (!isSame) {
    currentMusic.value = track
    duration.value = Number(track.duration) || 0
    favoriteCount.value = null
    commentCount.value = 0
    loadLyrics(track.id)
    loadCommentCount(track.id)
    updateMediaSessionMetadata(track)
  }

  // 单曲入口（hash / 跨标签页）可能只给了曲目、没同步列表，这里补齐
  if (!playlist.value.some((item) => String(item?.id) === String(track.id))) {
    playlist.value.push(track)
    localStorage.setItem('globalPlaylist', JSON.stringify(playlist.value))
    window.dispatchEvent(
      new CustomEvent('playlistUpdated', { detail: { playlist: playlist.value } })
    )
  }

  localStorage.setItem('currentPlayingMusic', JSON.stringify(track))
  currentTime.value = 0.1
  progress.value = 0.1
  if (!duration.value) duration.value = Number(track.duration) || 0
  isPlaying.value = true
  updateGlobalPlayerState()

  // 先取到播放地址，再交给模板 :src 绑定（handleForcePlay 依赖 DOM 补丁后的 src）
  if (!isSame || !audioSrc.value) {
    await refreshAudioSrc(currentMusic.value)
  }

  // handleForcePlay 内部会 await nextTick、设置起始时间、恢复音量并起播
  await handleForcePlay()
}

/**
 * 按曲目 id 起播（播放页直接进入 /detail/:id 时由 playMusic 指令触发）。
 * 队列里有就切；没有就取详情补进队列再切。最终都落在 switchToTrack。
 */
const playMusicById = async (musicId) => {
  if (musicId == null || musicId === '') return

  const target = playlist.value.find((item) => String(item?.id) === String(musicId))
  if (target) {
    await switchToTrack(target)
    return
  }

  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId}`)
    const data = await response.json()
    if (!data?.success || !data.data) return
    const track = data.data
    if (!playlist.value.some((item) => String(item?.id) === String(track.id))) {
      playlist.value.push(track)
      localStorage.setItem('globalPlaylist', JSON.stringify(playlist.value))
      window.dispatchEvent(
        new CustomEvent('playlistUpdated', { detail: { playlist: playlist.value } })
      )
    }
    await switchToTrack(track)
  } catch (error) {
    console.warn('[player] 按 id 起播失败', error)
  }
}

// 处理自定义播放状态变化事件
const handlePlayerStateChange = (e) => {
  const state = e.detail;

  // 跳过本组件的自身广播：那是「状态通知」，不是「切歌指令」。
  // （此前会在这里处理自己刚发出的广播，与新曲目互相覆盖，是回声来源之一）
  if (state?.source === 'globalPlayer') return

  // 外部传入的 currentMusic 若与本组件当前曲目不一致，一律不在这里切歌：
  // 所有切歌请求都必须走 playTrack 指令 → switchToTrack（唯一写入点）。
  if (state.currentMusic && state.currentMusic.id != null) {
    if (`${currentMusic.value?.id}` !== `${state.currentMusic.id}`) return
  }

  // 同一首音乐的时间 / 播放态更新
  currentTime.value = state.currentTime;
  duration.value = state.duration;
  progress.value = state.currentTime;

  // 如果有audio元素则同步操作
  if (audioPlayer.value && currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    const previousIsPlaying = isPlaying.value;
    isPlaying.value = state.isPlaying;
    
    // 等待音频加载完成再执行操作
    const performStateChange = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying && !previousIsPlaying) {
          // 如果状态从暂停变为播放，则开始播放音频
          resumeAudio();
          safePlay(audioPlayer.value);
        } else if (!state.isPlaying) {
          // 如果是暂停状态，立即暂停并静音，避免重音
          pauseAudio()
        }
        updateGlobalPlayerState();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState();
    };

    if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
      performStateChange();
    } else {
      audioPlayer.value.addEventListener('loadeddata', performStateChange, { once: true });
    }
  } else if (currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    // 如果audio元素还没准备好，等待并执行操作
    const previousIsPlaying = isPlaying.value;
    isPlaying.value = state.isPlaying;
    
    const handleMetadata = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying && !previousIsPlaying) {
          // 如果状态从暂停变为播放，则开始播放音频
          resumeAudio();
          safePlay(audioPlayer.value);
        } else if (!state.isPlaying) {
          // 如果是暂停状态，立即暂停并静音，避免重音
          pauseAudio()
        }
        updateGlobalPlayerState();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState();
    };

    audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
  }
}

// 处理播放列表更新事件
const handlePlaylistUpdated = (e) => {
  if (e.detail && e.detail.playlist) {
    playlist.value = e.detail.playlist;
    // 同时保存到 localStorage 以确保持久化
    localStorage.setItem('globalPlaylist', JSON.stringify(playlist.value));
  }
}

/** 详情页试听片段时暂停底部播放器，避免双路音频与重复请求 */
const handlePauseGlobalPlayer = () => {
  if (audioPlayer.value && !audioPlayer.value.paused) {
    pauseAudio()
    isPlaying.value = false
    updateGlobalPlayerState()
    updateMediaSessionPlaybackState()
  }
}

onMounted(() => {
  // 把音频元素注册给频谱分析（Web Audio AnalyserNode 需挂在同一元素上）
  if (audioPlayer.value) attachAudioElement(audioPlayer.value)

  // audio 是 v-if="currentMusic" 渲染的：首次挂载时通常还不存在，
  // 因此监听 ref，等元素出现后再注册（元素被重建时也会重新注册）
  watch(audioPlayer, (el) => {
    if (el) attachAudioElement(el)
  })

  // 加载播放列表
  loadPlaylist()

  // 监听storage事件，以响应其他标签页的播放变化
  window.addEventListener('storage', handleStorageChange)
  // 监听自定义事件，以响应播放页面的状态变化
  window.addEventListener('playerStateChange', handlePlayerStateChange)
  // 监听强制播放事件
  window.addEventListener('forcePlay', handleForcePlay)
  // 监听播放列表更新事件
  window.addEventListener('playlistUpdated', handlePlaylistUpdated)
  // 监听URL hash变化，处理播放请求
  window.addEventListener('hashchange', handleHashChange)
  window.addEventListener('pauseGlobalPlayer', handlePauseGlobalPlayer)
  // 监听来自全屏播放页的播放指令（见 composables/usePlaybackBridge.js）
  window.addEventListener('playerCommand', handlePlayerCommand)
  // 初始检查hash
  handleHashChange()

  // 初始化当前播放音乐
  const storedMusic = localStorage.getItem('currentPlayingMusic')
  if (storedMusic) {
    currentMusic.value = JSON.parse(storedMusic)
    refreshAudioSrc(currentMusic.value)
    
    // 如果当前音乐不在播放列表中，则添加进去
    if (currentMusic.value && playlist.value) {
      const existingIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id);
      if (existingIndex === -1) {
        // 如果当前音乐不在播放列表中，则添加到列表中
        playlist.value.push(currentMusic.value);
        // 同时保存到 localStorage
        localStorage.setItem('globalPlaylist', JSON.stringify(playlist.value));
      }
    }
    
    // 初始化时加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
      loadCommentCount(currentMusic.value.id)
      // 初始化媒体会话
      initializeMediaSession(currentMusic.value)
      // 检查收藏状态
      checkFavoriteStatus()
    }
  }
  
  // 初始化时从localStorage获取播放状态
  const storedState = localStorage.getItem('globalPlayerState');
  if (storedState) {
    const state = JSON.parse(storedState);
    currentTime.value = state.currentTime;
    duration.value = state.duration;
    progress.value = state.currentTime;
    
    // 如果全局播放器应该正在播放，则同步播放状态
    isPlaying.value = state.isPlaying;

    // 恢复上次的播放：<audio> 是 v-if 渲染的，挂载这一刻 ref 通常还是 null，
    // 所以不能加 audioPlayer 判断；handleForcePlay 内部会 await nextTick 再取元素。
    if (currentMusic.value && isPlaying.value) {
      setTimeout(() => {
        handleForcePlay();
      }, 100); // 稍微延迟确保组件完全加载
    }
  }
  
  // 加载播放模式
  loadPlaybackMode()
  
  // 初始化媒体会话API
  initializeMediaSession()
})

// 加载播放列表
// 处理URL hash变化，响应播放请求
const handleHashChange = () => {
  const hash = window.location.hash

  if (hash.startsWith('#play=')) {
    // 单曲播放
    try {
      const musicData = JSON.parse(decodeURIComponent(hash.substring(6)))

      // 统一切歌入口：内部会把曲目补进播放列表、写状态、等 DOM 补丁后起播
      switchToTrack(musicData)

      // 清除hash（保留 vue-router 写入的 history.state，否则下次导航报 R0121）
      clearUrlHash()
    } catch (error) {
      console.error('解析播放数据失败:', error)
    }
  } else if (hash.startsWith('#playlist=')) {
    // 播放列表播放
    try {
      const params = hash.substring(1).split('&')
      const playlistData = JSON.parse(decodeURIComponent(params[0].substring(9)))
      const startIndex = parseInt(params[1].substring(6)) || 0

      // 更新播放列表
      playlist.value = playlistData
      localStorage.setItem('globalPlaylist', JSON.stringify(playlistData))

      // 播放指定索引的音乐
      if (playlistData[startIndex]) {
        switchToTrack(playlistData[startIndex])
      }

      // 清除hash（保留 vue-router 写入的 history.state，否则下次导航报 R0121）
      clearUrlHash()
    } catch (error) {
      console.error('解析播放列表数据失败:', error)
    }
  }
}

/**
 * 载入播放列表 —— 唯一来源是 localStorage。
 *
 * 原实现在「本地没有列表」时会去 POST /api/music/search 取一份默认列表，
 * 但传的是 `{ query: '' }`，而后端要求 query 非空（返回 400
 * 「请提供 query 或 items」）—— 这段兜底从来没成功过，只会在每次
 * 首次访问时打一条 400 红字。搜索接口也不该被当作「取全部歌曲」用。
 */
const loadPlaylist = () => {
  try {
    const storedPlaylist = localStorage.getItem('globalPlaylist')
    if (!storedPlaylist) {
      playlist.value = []
      return
    }
    const parsed = JSON.parse(storedPlaylist)
    playlist.value = Array.isArray(parsed) ? parsed : []
  } catch (error) {
    console.error('加载播放列表失败:', error)
    playlist.value = []
  }
}

// 初始化媒体会话API
const initializeMediaSession = (music = null) => {
  if ('mediaSession' in navigator) {
    try {
      // 设置媒体操作处理程序
      navigator.mediaSession.setActionHandler('play', () => {
        if (audioPlayer.value && currentMusic.value) {
          isPlaying.value = true
          updateGlobalPlayerState()
          resumeAudio()
          // 调用play()来开始播放
          safePlay(audioPlayer.value);
        }
      })
      
      navigator.mediaSession.setActionHandler('pause', () => {
        if (audioPlayer.value && currentMusic.value) {
          isPlaying.value = false
          updateGlobalPlayerState()
          // 立即暂停音频并静音，避免重音
          pauseAudio()
        }
      })
      
      navigator.mediaSession.setActionHandler('previoustrack', () => {
        playPrevious()
      })
      
      navigator.mediaSession.setActionHandler('nexttrack', () => {
        playNext() // 手动点击，使用完整流程
      })
      
      navigator.mediaSession.setActionHandler('seekbackward', () => {
        if (audioPlayer.value) {
          audioPlayer.value.currentTime = Math.max(audioPlayer.value.currentTime - 10, 0);
        }
      })
      
      navigator.mediaSession.setActionHandler('seekforward', () => {
        if (audioPlayer.value) {
          audioPlayer.value.currentTime = Math.min(audioPlayer.value.currentTime + 10, audioPlayer.value.duration);
        }
      })
      
      // 设置当前播放的音乐元数据
      if (music || currentMusic.value) {
        updateMediaSessionMetadata(music || currentMusic.value)
      }
    } catch (error) {
    }
  }
}

// 更新媒体会话元数据
const updateMediaSessionMetadata = (music) => {
  if ('mediaSession' in navigator && music) {
    try {
      const artwork = [
        { src: getCoverUrl(music.id), sizes: '96x96', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '128x128', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '192x192', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '256x256', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '384x384', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '512x512', type: 'image/jpeg' }
      ]
      
      navigator.mediaSession.metadata = new MediaMetadata({
        title: music.title || t('player.unknownTitle'),
        artist: music.artist || t('player.unknownArtist'),
        album: music.album || t('common.unknownAlbum'),
        artwork: artwork
      })
      
      // 更新播放状态
      navigator.mediaSession.playbackState = isPlaying.value ? 'playing' : 'paused'
    } catch (error) {
    }
  }
}

// 更新媒体会话播放状态
const updateMediaSessionPlaybackState = () => {
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.playbackState = isPlaying.value ? 'playing' : 'paused'
    } catch (error) {
    }
  }
}

// 更新媒体会话播放位置
const updateMediaSessionPositionState = () => {
  if ('mediaSession' in navigator && 'setPositionState' in navigator.mediaSession) {
    try {
      // 确保 currentTime 不大于 duration
      const safeCurrentTime = Math.min(currentTime.value, duration.value);
      navigator.mediaSession.setPositionState({
        duration: duration.value,
        playbackRate: 1.0,
        position: safeCurrentTime
      });
    } catch (error) {
    }
  }
}

// 组件卸载时移除事件监听
onUnmounted(() => {
  window.removeEventListener('storage', handleStorageChange)
  window.removeEventListener('playerStateChange', handlePlayerStateChange)
  window.removeEventListener('forcePlay', handleForcePlay)
  window.removeEventListener('playlistUpdated', handlePlaylistUpdated)
  window.removeEventListener('hashchange', handleHashChange)
  window.removeEventListener('pauseGlobalPlayer', handlePauseGlobalPlayer)
  window.removeEventListener('playerCommand', handlePlayerCommand)
  
  // 清除媒体会话
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = null
    navigator.mediaSession.playbackState = 'none'
  }
})
</script>

<style scoped>
/* ============================================================================
   底部播放条（停靠式）
   ----------------------------------------------------------------------------
   结构：顶部细进度线 + [ 封面/曲名/收藏 | 播放控制 | 时间/迷你频谱 ]
   形状：圆角矩形（不使用胶囊高亮、不使用侧边彩色条）
   主题：黑偏青，全部走 --n-* 令牌
   ========================================================================== */
.global-player {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: var(--n-z-player);
  /* 高度 = 内容高度 + 底部安全区：手势条/Home 指示条不会遮住控件 */
  height: calc(var(--n-player-height) + var(--n-safe-bottom));
  padding-bottom: var(--n-safe-bottom);
  color: var(--n-text);
  user-select: none;
  background:
    linear-gradient(180deg, rgba(9, 24, 29, 0.88), rgba(4, 12, 15, 0.97));
  backdrop-filter: blur(var(--n-blur)) saturate(140%);
  -webkit-backdrop-filter: blur(var(--n-blur)) saturate(140%);
  border-top: 1px solid var(--n-line);
}

.global-player--chrome-dark {
  background:
    linear-gradient(180deg, rgba(6, 17, 21, 0.92), rgba(2, 7, 9, 0.98));
}

/* ===== 顶部细进度线 ===== */
.gp-seek {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 14px;
  transform: translateY(-7px);
  z-index: 2;
}

.gp-seek__track {
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  height: 3px;
  margin-top: -1.5px;
  border-radius: var(--n-radius-pill);
  background: var(--n-line-strong);
  overflow: hidden;
  transition: height var(--n-duration-fast) var(--n-ease),
    margin-top var(--n-duration-fast) var(--n-ease);
}

.gp-seek__fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--n-accent-deep), var(--n-accent));
  transition: width 120ms linear;
}

/* 原生 range 铺满并透明化：保留键盘/拖动/无障碍，视觉完全自绘 */
.gp-seek__input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  opacity: 0;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  background: transparent;
}

@media (hover: hover) {
  .gp-seek:hover .gp-seek__track {
    height: 6px;
    margin-top: -3px;
  }
}

.gp-seek:focus-within .gp-seek__track {
  height: 6px;
  margin-top: -3px;
}

.gp-seek:focus-within .gp-seek__track {
  box-shadow: 0 0 0 3px var(--n-accent-soft);
}

/* ===== 主体三栏 ===== */
.gp-grid {
  height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: var(--n-space-4);
  padding: 0 var(--n-space-5);
}

/* ===== 左：封面 + 信息 + 收藏 ===== */
.gp-track {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  min-width: 0;
}

/* 封面 + 文字合成同一个按钮：触摸目标覆盖整块（手机上尤其重要） */
.gp-track__open {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: none;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.gp-track__open:disabled {
  cursor: default;
}

.gp-track__cover {
  flex: none;
  width: 52px;
  height: 52px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-sunken);
  overflow: hidden;
  display: grid;
  place-items: center;
  color: var(--n-text-faint);
  transition: transform var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .gp-track__open:hover:not(:disabled) .gp-track__cover {
    transform: translateY(-1px);
    border-color: var(--n-accent-line);
  }
}

.gp-icon.gp-track__fav {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: auto;
  min-width: 36px;
  padding: 0 var(--n-space-2);
  gap: 4px;
}

.gp-track__fav-count {
  font-size: 11px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.gp-icon.gp-track__comment {
  position: relative;
}

.gp-track__comment-badge {
  position: absolute;
  top: -3px;
  right: -3px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--n-radius-pill);
  background: var(--n-accent);
  color: var(--n-text-inverse, #fff);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.gp-track__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.gp-track__ph {
  display: grid;
  place-items: center;
}

.gp-track__meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.gp-track__title {
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
  line-height: var(--n-leading-tight);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gp-track__artist {
  font-size: var(--n-text-xs);
  color: var(--n-text-faint);
  line-height: var(--n-leading-tight);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.is-placeholder {
  color: var(--n-text-faint);
  font-weight: var(--n-weight-normal);
}

/* ===== 中：播放控制 ===== */
.gp-controls {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
}

.gp-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--n-radius-control);
  background: transparent;
  color: var(--n-text-muted);
  cursor: pointer;
  transition: background var(--n-duration-fast) var(--n-ease),
    color var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease);
}

.gp-icon--lg {
  width: 40px;
  height: 40px;
}

.gp-icon--sm {
  width: 30px;
  height: 30px;
}

@media (hover: hover) {
  .gp-icon:hover:not(:disabled) {
    background: var(--n-surface-hover);
    color: var(--n-text);
  }
}

.gp-icon:active:not(:disabled) {
  background: var(--n-surface-active);
}

.gp-icon:disabled {
  opacity: 0.36;
  cursor: default;
}

.gp-icon.is-on {
  color: var(--n-accent);
}

.gp-icon:focus-visible,
.gp-play:focus-visible,
.gp-track__cover:focus-visible,
.gp-pop__item:focus-visible,
.gp-pop__clear:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--n-accent-soft);
}

/* 主播放键：圆角矩形实底，不用圆形 */
.gp-play {
  flex: none;
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  padding: 0;
  border: 1px solid var(--n-accent-line);
  border-radius: var(--n-radius);
  background: linear-gradient(160deg, var(--n-accent), var(--n-accent-deep));
  color: var(--n-text-inverse);
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(47, 159, 178, 0.28);
  transition: transform var(--n-duration-fast) var(--n-ease),
    box-shadow var(--n-duration-fast) var(--n-ease), filter var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .gp-play:hover:not(:disabled) {
    transform: translateY(-1px);
    filter: brightness(1.08);
    box-shadow: 0 10px 24px rgba(47, 159, 178, 0.38);
  }
}

.gp-play:active:not(:disabled) {
  transform: translateY(0) scale(0.97);
}

.gp-play:disabled {
  background: var(--n-surface-active);
  border-color: var(--n-line);
  color: var(--n-text-faint);
  box-shadow: none;
  cursor: default;
}

/* ===== 右：时间 + 此刻（歌词优先，否则迷你频谱）+ 播放列表 ===== */
.gp-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--n-space-3);
  min-width: 0;
}

/* 固定宽度列：时间在上，「此刻」在下（对齐 ArchoeraMusic 的 150px 列） */
.gp-now {
  flex: none;
  width: 150px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.gp-now__time {
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
  color: var(--n-text-faint);
  white-space: nowrap;
}

.gp-now__time b {
  font-weight: var(--n-weight-medium);
  color: var(--n-text-muted);
}

.gp-now__time i {
  font-style: normal;
  margin: 0 4px;
  opacity: 0.5;
}

/* 120×12 的「此刻」槽位：有歌词显示歌词，否则显示频谱。
   ★ 两个子项都必须是确定宽度，否则尺寸会落到 <canvas> 的固有宽度上，
     把整条播放条挤爆（见 SpectrumCanvas 内的说明）。 */
.gp-now__viz {
  flex: none;
  width: 120px;
  height: 12px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  overflow: hidden;
}

.gp-now__lyric {
  width: 100%;
  height: 100%;
  font-size: var(--n-text-xs);
  line-height: 1;
  color: var(--n-text-muted);
}

.gp-now__spectrum {
  flex: none;
  width: 120px;
}

/* ===== 播放列表弹层 ===== */
.gp-pop {
  position: absolute;
  right: var(--n-space-5);
  bottom: calc(100% + 12px);
  width: min(370px, calc(100vw - 32px));
  max-height: min(54vh, 470px);
  display: flex;
  flex-direction: column;
  background: var(--n-surface-strong);
  backdrop-filter: blur(var(--n-blur-lg)) saturate(150%);
  -webkit-backdrop-filter: blur(var(--n-blur-lg)) saturate(150%);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  box-shadow: var(--n-shadow);
  overflow: hidden;
}

.gp-pop__head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  padding: var(--n-space-3) var(--n-space-3) var(--n-space-3) var(--n-space-4);
  border-bottom: 1px solid var(--n-line-subtle);
}

.gp-pop__title {
  margin: 0;
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
  display: inline-flex;
  align-items: center;
  gap: var(--n-space-2);
}

.gp-pop__count {
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-normal);
  color: var(--n-text-faint);
}

.gp-pop__head-actions {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
}

.gp-pop__clear {
  padding: 5px 10px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xs);
  background: transparent;
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  cursor: pointer;
  transition: color var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .gp-pop__clear:hover:not(:disabled) {
    color: var(--n-danger);
    border-color: var(--n-danger);
    background: var(--n-danger-soft);
  }
}

.gp-pop__clear:disabled {
  opacity: 0.4;
  cursor: default;
}

.gp-pop__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--n-space-2);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.gp-pop__empty {
  margin: 0;
  padding: var(--n-space-6) var(--n-space-4);
  text-align: center;
  font-size: var(--n-text-sm);
  color: var(--n-text-faint);
}

.gp-pop__item {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  width: 100%;
  padding: var(--n-space-2) var(--n-space-3);
  border: 1px solid transparent;
  border-radius: var(--n-radius-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .gp-pop__item:hover {
    background: var(--n-surface-hover);
  }
}

.gp-pop__item.is-current {
  background: var(--n-accent-soft);
  border-color: var(--n-accent-line);
}

.gp-pop__idx {
  flex: none;
  width: 22px;
  text-align: center;
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
  color: var(--n-text-faint);
  display: grid;
  place-items: center;
}

.gp-pop__item.is-current .gp-pop__idx {
  color: var(--n-accent);
}

.gp-pop__info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.gp-pop__name {
  font-size: var(--n-text-sm);
  color: var(--n-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gp-pop__item.is-current .gp-pop__name {
  color: var(--n-accent-strong);
}

.gp-pop__artist {
  font-size: var(--n-text-xs);
  color: var(--n-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 弹层动效：自下轻微上浮 */
.gp-pop-enter-active,
.gp-pop-leave-active {
  transition: opacity var(--n-duration) var(--n-ease),
    transform var(--n-duration) var(--n-ease);
}

.gp-pop-enter-from,
.gp-pop-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}

/* ===== 响应式 =====
   断点约定见 design/tokens.css：560 手机竖屏 / 900 平板 / 1200 桌面 */
@media (max-width: 1200px) {
  .gp-now {
    width: 112px;
  }

  .gp-now__viz,
  .gp-now__spectrum {
    width: 92px;
  }
}

@media (max-width: 900px) {
  .gp-grid {
    gap: var(--n-space-2);
    padding: 0 var(--n-space-4);
  }

  .gp-track__artist,
  .gp-now {
    display: none;
  }
}

/* 手机竖屏：压成「封面+曲名 · 上一曲/播放/下一曲 · 列表」，
   次级操作（播放模式、收藏）收进播放页，避免一排按钮挤爆 */
@media (max-width: 560px) {
  .gp-grid {
    /* 左栏吃掉剩余宽度，右栏按内容收紧（1fr/1fr 会把空间浪费在只有一个按钮的右栏） */
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: var(--n-space-1);
    padding: 0 var(--n-space-3);
  }

  /* 手机上收起次级操作：收藏与播放模式都在播放页里 */
  .gp-track__comment,
  .gp-track__fav,
  .gp-mode {
    display: none;
  }

  .gp-track__open {
    gap: var(--n-space-2);
  }

  .gp-track__cover {
    width: 40px;
    height: 40px;
  }

  .gp-icon--lg {
    width: var(--n-tap-min);
    height: var(--n-tap-min);
  }

  .gp-icon--sm {
    width: 36px;
    height: 36px;
  }

  .gp-play {
    width: 48px;
    height: 48px;
    margin: 0;
  }

  /* 顶部进度线加厚一点，手指更好按 */
  .gp-seek {
    height: 18px;
    transform: translateY(-9px);
  }

  .gp-seek__track {
    height: 4px;
    margin-top: -2px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .gp-seek__track,
  .gp-seek__fill,
  .gp-icon,
  .gp-play,
  .gp-track__cover,
  .gp-pop,
  .gp-pop__item {
    transition: none;
  }
}
</style>

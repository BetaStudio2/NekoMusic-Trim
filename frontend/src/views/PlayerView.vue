<template>
  <Teleport to="body">
    <!-- 全屏播放页：自底部滑入 / 滑出（关闭前先播完退出动效再换路由） -->
    <Transition name="np" appear @after-enter="onAfterEnter" @after-leave="onAfterLeave">
      <div
        v-show="!closing"
        class="np"
        :class="{ 'np--dragging': dragging, 'np--lite-bg': lite }"
        :style="dragY ? { transform: `translateY(${dragY}px)` } : undefined"
        role="dialog"
        aria-modal="true"
        :aria-label="$t('player.dialogAria')"
        @touchstart.passive="onTouchStart"
        @touchmove.passive="onTouchMove"
        @touchend="onTouchEnd"
        @touchcancel="onTouchEnd"
      >        <!-- 专辑流动背景（AMLL BackgroundRender，随低频起伏） -->
        <AlbumBackground
          v-if="currentMusic"
          :album="getCoverUrl(currentMusic.id)"
          :playing="isPlaying"
          :has-lyric="parsedLyrics.length > 0"
          :active="backgroundReady"
        />
        <div class="np__scrim" aria-hidden="true" />

        <!-- 顶栏：收起 + 曲名 + 次级操作 -->
        <header class="np__top">
          <button type="button" class="np-icon" :aria-label="$t('player.collapse')" @click="close">
            <NIcon name="chevron-down" :size="22" />
          </button>

          <div class="np__top-title">
            <span class="np__top-name">{{ currentMusic ? currentMusic.title : $t('common.loading') }}</span>
            <span v-if="currentMusic" class="np__top-artist">{{ currentMusic.artist }}</span>
          </div>

          <div class="np__top-actions">
            <button
              type="button"
              class="np-icon"
              :class="{ 'is-on': commentsOpen }"
              :disabled="!currentMusic"
              :aria-label="commentsOpen ? $t('player.closeComments') : $t('player.viewComments')"
              :title="commentsOpen ? $t('player.closeComments') : $t('player.viewComments')"
              @click="commentsOpen = !commentsOpen"
            >
              <NIcon name="message" :size="18" />
              <span v-if="commentCount" class="np__comment-badge">{{ commentCount > 99 ? '99+' : commentCount }}</span>
            </button>
            <button
              type="button"
              class="np-icon np-icon--favorite"
              :class="{ 'is-on': currentMusic && isFavorite(currentMusic.id) }"
              :disabled="!currentMusic"
              :aria-label="currentMusic && isFavorite(currentMusic.id) ? $t('track.unfavorite') : $t('track.favorite')"
              :title="currentMusic && isFavorite(currentMusic.id) ? $t('track.unfavorite') : $t('track.favorite')"
              @click="toggleFavorite"
            >
              <NIcon :name="currentMusic && isFavorite(currentMusic.id) ? 'heart' : 'heart-off'" :size="18" />
              <span v-if="favoriteCount !== null" class="np-icon__count">
                {{ favoriteCount > 9999 ? '9999+' : favoriteCount }}
              </span>
            </button>
            <button
              type="button"
              class="np-icon"
              :disabled="!currentMusic"
              :aria-label="$t('player.reportLyrics')"
              :title="$t('player.reportLyrics')"
              @click="openLyricsReport"
            >
              <NIcon name="file-text" :size="18" />
            </button>
            <button
              type="button"
              class="np-icon"
              :disabled="!currentMusic"
              :aria-label="$t('track.download')"
              :title="$t('track.download')"
              @click="downloadMusic"
            >
              <NIcon name="download" :size="18" />
            </button>
            <button
              type="button"
              class="np-icon"
              :disabled="!currentMusic || videoRenderBusy"
              :aria-label="$t('player.renderVideo')"
              :title="videoRenderBusy ? $t('player.rendering') : $t('player.renderVideo')"
              @click="openVideoRenderDialog"
            >
              <NIcon name="video" :size="18" />
            </button>

            <!-- 生成分享视频是会员相关能力：沿用重构前的权益提示 -->
            <p v-if="isLoggedIn()" class="np__video-hint">
              <template v-if="userIsVip">{{ $t('player.vipClipNote') }}</template>
              <template v-else>
                {{ $t('player.freeClipNote') }}
                <RouterLink to="/vip">{{ $t('player.openVip') }}</RouterLink>
              </template>
            </p>
          </div>
        </header>

        <!-- 移动设备下载提示：全屏覆盖层盖住了 App 的通栏横幅，这里用卡片式 -->
        <MobileAppBanner v-if="isMobile" variant="card" class="np__app-banner" />

        <!-- 主体 -->
        <div class="np__body">
          <!-- 左：封面 + 信息 + 操作 -->
          <section class="np__aside">
            <div v-if="!currentMusic" class="np__loading">
              <NSpinner :size="30" />
              <p>{{ $t('player.loadingTracks') }}</p>
            </div>

            <template v-else>
              <div class="np__cover">
                <img
                  :src="getCoverUrl(currentMusic.id)"
                  :alt="currentMusic.title"
                  @error="handleImageError"
                />
                <div class="np__cover-sheen" aria-hidden="true" />
              </div>

              <div class="np__meta">
                <h1 class="np__title">{{ currentMusic.title }}</h1>
                <p class="np__artist">{{ currentMusic.artist }}</p>
                <p v-if="currentMusic.album" class="np__album">{{ currentMusic.album }}</p>
                <p v-if="currentMusic.duration" class="np__dur">
                  <NIcon name="clock" :size="13" />
                  {{ formatDuration(currentMusic.duration) }}
                </p>
              </div>

              <SpectrumCanvas
                class="np__spectrum"
                :bars="44"
                :height="56"
                :active="isPlaying"
              />

              <div v-if="videoRenderSubmitted" class="np__notice">
                <NIcon name="circle-check" :size="16" />
                <div>
                  <p>{{ $t('player.renderSubmitted') }}</p>
                  <p
                    v-if="videoRenderRemainingToday != null && !userIsVip"
                    class="np__notice-meta"
                  >
                    {{ $t('player.remainingToday', { count: videoRenderRemainingToday }) }}
                  </p>
                </div>
              </div>

              <div v-if="videoRenderReady" class="np__notice np__notice--ready">
                <NIcon name="circle-check" :size="16" />
                <div class="np__notice-body">
                  <p>{{ $t('player.videoReady') }}</p>
                  <NButton size="sm" variant="primary" icon="download" @click="downloadRenderedVideo">
                    {{ $t('player.downloadMp4') }}
                  </NButton>
                </div>
              </div>
            </template>
          </section>

          <!-- 右：歌词墙 -->
          <section class="np__lyrics" :aria-label="$t('player.lyricsAria')">
            <LyricsWall
              v-if="parsedLyrics.length > 0"
              class="np__wall"
              :lines="parsedLyrics"
              :current-time="displayTime"
              :playing="isPlaying"
              @seek="onLyricSeek"
            />

            <div v-else class="np__lyrics-empty">
              <NIcon name="file-text" :size="28" />
              <p>{{ $t('ui.noLyrics') }}</p>
            </div>
          </section>
        </div>

        <!-- 底栏：进度 + 播放控制 -->
        <footer class="np__bottom">
          <div class="np__seek">
            <span class="np__time">{{ formatDuration(displayTime) }}</span>
            <div class="np__seek-track">
              <div class="np__seek-rail" aria-hidden="true">
                <div class="np__seek-fill" :style="{ width: progressPercent + '%' }" />
              </div>
              <input
                type="range"
                class="np__seek-input"
                :value="displayTime"
                :max="duration || 0"
                min="0"
                step="0.1"
                :aria-label="$t('player.progressAria')"
                @input="onSeekInput"
                @change="onSeekCommit"
              />
            </div>
            <span class="np__time">{{ formatDuration(duration) }}</span>
          </div>

          <div class="np__controls">
            <button
              type="button"
              class="np-icon np-icon--lg"
              :title="modeTitle"
              :aria-label="modeTitle"
              @click="sendPlayerCommand('cycleMode')"
            >
              <NIcon :name="modeIcon" :size="20" />
            </button>

            <button
              type="button"
              class="np-icon np-icon--lg"
              :title="$t('player.prev')"
              :aria-label="$t('player.prev')"
              :disabled="!currentMusic"
              @click="sendPlayerCommand('prev')"
            >
              <NIcon name="skip-back" :size="22" />
            </button>

            <button
              type="button"
              class="np-play"
              :disabled="!currentMusic"
              :aria-label="isPlaying ? $t('track.pause') : $t('track.play')"
              :aria-pressed="isPlaying"
              @click="sendPlayerCommand('toggle')"
            >
              <NIcon :name="isPlaying ? 'pause' : 'play'" :size="26" />
            </button>

            <button
              type="button"
              class="np-icon np-icon--lg"
              :title="$t('player.next')"
              :aria-label="$t('player.next')"
              :disabled="!currentMusic"
              @click="sendPlayerCommand('next')"
            >
              <NIcon name="skip-forward" :size="22" />
            </button>

            <span class="np__controls-spacer" aria-hidden="true" />
          </div>
        </footer>

        <!-- 评论抽屉：每首歌的评论 + 楼层回复 -->
        <Transition name="np-comments">
          <div v-if="commentsOpen" class="np__comments-layer">
            <div class="np__comments-mask" aria-hidden="true" @click="commentsOpen = false" />
            <aside class="np__comments" :aria-label="$t('comment.sectionAria')">
              <header class="np__comments-head">
                <span class="np__comments-title">{{ $t('comment.title') }}</span>
                <button type="button" class="np-icon" :aria-label="$t('player.closeComments')" @click="commentsOpen = false">
                  <NIcon name="close" :size="18" />
                </button>
              </header>
              <CommentPanel
                v-if="currentMusic"
                class="np__comments-body"
                :music-id="currentMusic.id"
                @count="commentCount = $event"
              />
            </aside>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>

  <!-- 分享视频弹窗 -->
  <NModal v-model="videoModalOpen" :title="$t('player.renderVideo')" size="md" @close="closeVideoModal">
    <p v-if="currentMusic" class="clip-song">{{ currentMusic.title }} · {{ currentMusic.artist }}</p>

    <label class="clip-option" :class="{ 'clip-option--locked': !userIsVip }">
      <input v-model="videoWatermarkChoice" type="checkbox" :disabled="!userIsVip" />
      <span>{{ $t('player.watermark') }}</span>
    </label>

    <div class="clip-range">
      <div class="clip-range__head">
        <span>{{ $t('player.clipStart') }}</span>
        <span class="clip-range__value">
          {{ formatClipTime(clipStartSec) }} → {{ formatClipTime(clipEndSec) }}
        </span>
      </div>
      <input
        v-model.number="clipStartSec"
        type="range"
        class="clip-range__slider"
        :min="0"
        :max="maxClipStartSec"
        :step="1"
        :disabled="trackDurationSec <= 0"
        @input="onClipRangeChange"
      />
      <p class="clip-sub">
        <template v-if="userIsVip">
          {{ $t('player.vipRange', { duration: formatClipTime(clipPreviewDurationSec) }) }}
        </template>
        <template v-else>{{ $t('player.freeRange') }}</template>
      </p>
      <NButton
        size="sm"
        variant="secondary"
        icon="play"
        :disabled="trackDurationSec <= 0 || clipPreviewDurationSec <= 0"
        @click="toggleClipPreview"
      >
        {{ clipPreviewPlaying ? $t('player.previewStop') : $t('player.previewPlay') }}
      </NButton>
    </div>

    <p class="clip-sub">
      <template v-if="userIsVip">{{ $t('player.watermarkVipNote') }}</template>
      <template v-else>{{ $t('player.watermarkFreeNote') }}</template>
    </p>
    <p class="clip-sub">{{ $t('player.clipSub') }}</p>

    <template #footer>
      <NButton variant="ghost" @click="closeVideoModal">{{ $t('common.cancel') }}</NButton>
      <NButton variant="primary" :loading="videoRenderBusy" @click="confirmVideoRender">
        {{ $t('player.startRender') }}
      </NButton>
    </template>
  </NModal>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { resolveMediaUrl } from '@/utils/mediaUrl.js'
import { createVideoRenderJob, fetchVideoRenderStatus, downloadVideoRenderFile } from '@/api/videoRender.js'
import { syncUserVipFromPlaylistsApi, USER_VIP_SYNC_EVENT } from '@/utils/userVip.js'
import { tryOpenMusicDetailInApp } from '@/utils/nativeAppOpen.js'
import { getUser } from '@/utils/userStore.js'
import { useToast } from '@/composables/useToast'
import { t } from '@/i18n'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NModal, NSpinner } from '@/ui'
import SpectrumCanvas from '@/components/SpectrumCanvas.vue'
import LyricsWall from '@/components/LyricsWall.vue'
import AlbumBackground from '@/components/AlbumBackground.vue'
import MobileAppBanner from '@/components/MobileAppBanner.vue'
import CommentPanel from '@/components/CommentPanel.vue'
import { useLiteMode } from '@/composables/useLiteMode'
import { isMobileDevice } from '@/utils/mobile.js'
import { usePlaybackBridge } from '@/composables/usePlaybackBridge'
import { applyPageSeo, DEFAULT_ROBOTS, PRIVATE_ROBOTS, SITE_ORIGIN } from '@/utils/seoMeta'
import { parseLyricsPayload } from '@/utils/lyrics.js'

const toast = useToast()

const route = useRoute()
const router = useRouter()

/**
 * 播放状态桥：本页不持有播放引擎，所有播放动作都以指令形式发给 GlobalPlayer，
 * 状态则从它广播的 playerStateChange / localStorage 回流。
 */
const { playback, sendPlayerCommand } = usePlaybackBridge()

/** 与 AlbumBackground 同源：轻量模式下背景是静态模糊封面，需要另配压暗强度 */
const { lite } = useLiteMode()

const currentMusic = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const lyrics = ref('')
const parsedLyrics = ref([])
const favoriteMusicIds = ref(new Set()) // 存储收藏的音乐ID
const favoriteCount = ref(null)
const isMobile = ref(false)
const userIsVip = ref(false)
const commentsOpen = ref(false)
const commentCount = ref(0)

const videoModalOpen = ref(false)
const videoRenderBusy = ref(false)
const videoRenderSubmitted = ref(false)
const videoRenderReady = ref(false)
const videoRenderJobId = ref('')
const videoRenderRemainingToday = ref(null)
const videoWatermarkChoice = ref(true)
const clipStartSec = ref(0)
const clipPreviewPlaying = ref(false)

/** 全屏页关闭中：先播退出动效，动效结束再切换路由 */
const closing = ref(false)
/**
 * 进场动效是否已结束。结束前背景只渲染静态模糊封面，结束后才把 WebGL
 * 渲染器挂起来 —— 避免在 .np 带 transform 的动效期间创建 canvas，
 * 那是「关一次播放页再打开就没背景」的根因（详见 AlbumBackground）。
 */
const backgroundReady = ref(false)
/** 兜底定时器：万一 after-enter 没触发，也要把背景放出来 */
let backgroundReadyTimer = null
/** 拖动进度条期间使用本地预览值，避免状态回流把滑块拽回去 */
const seeking = ref(false)
const seekPreview = ref(0)
/** 进入前 body 的 overflow，卸载时还原 */
let previousBodyOverflow = ''

const NON_VIP_CLIP_SEC = 30

// 用于定时器的引用
let timeUpdateInterval = null
let clipPreviewAudio = null
/** 试听用 blob URL，同页同曲只 fetch 一次，避免多次 Range 请求 */
const clipPreviewBlobUrlByMusicId = new Map()
let clipPreviewLoading = false

// 检测是否是移动设备
const checkMobile = () => isMobileDevice()

// 关闭横幅
/** 详情请求序号：连续快速切歌时，只允许最后一次请求写回，避免慢的旧响应覆盖新曲目 */
let detailRequestSeq = 0

/**
 * 路由「权威」目标曲目 id：显式进入 /detail/:id 后，播放引擎需要切到该曲目。
 * ------------------------------------------------------------
 * 为什么要有它：直接输入下一首的网址（或整页刷新到新地址）时，播放引擎在挂载
 * 瞬间仍持有 localStorage 里的上一首，并会把它广播给桥接层。若此时曲目跟随
 * 逻辑照单全收，就会把地址栏 replace 回上一首 —— 也就是「输入新歌网址却跳回
 * 上一首」。因此在 setup 阶段（早于桥接层在 onMounted 里的首次同步）就记下
 * 路由目标：目标未对齐前，忽略桥接层里上一首的残留状态。
 */
let routeTargetMusicId = null
/** 进入本页瞬间，播放引擎里残留的上一首 id；只忽略它这一次，不忽略之后真正的切歌 */
let stalePlaybackMusicId = null
if (route.params.id) {
  routeTargetMusicId = String(route.params.id)
  try {
    const stored = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null')
    if (stored?.id != null) stalePlaybackMusicId = String(stored.id)
  } catch {
    /* ignore */
  }
}

/**
 * 让播放引擎对齐当前路由曲目，并同步「路由目标」。
 * 引擎已在放该曲目 → 视为已对齐，清掉目标；否则发指令，等状态回流后再清。
 */
const playRouteMusic = () => {
  const id = String(route.params.id || '')
  if (!id) {
    routeTargetMusicId = null
    return
  }
  if (String(playback.currentMusic?.id || '') === id) {
    routeTargetMusicId = null
    return
  }
  routeTargetMusicId = id
  sendPlayerCommand('playMusic', { musicId: id })
}

/** 已持久化的「当前正在播放」曲目 id（权威来源，用于过滤过期状态） */
const getPersistedCurrentMusicId = () => {
  try {
    const stored = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null')
    return stored?.id ?? null
  } catch {
    return null
  }
}

// 获取音乐详情
const applyMusicSeo = (music) => {
  const id = music?.id
  if (!id) return
  const title = music.title || '未知歌曲'
  const artist = music.artist || '未知艺术家'
  const album = music.album ? `，专辑《${music.album}》` : ''
  const pageUrl = `${SITE_ORIGIN}/detail/${id}`
  const imageUrl = `${SITE_ORIGIN}/media/cover/${id}`
  const description =
    `免费在线播放《${title}》— ${artist}${album}。Neko歌姬计划提供高品质流媒体、歌词、收藏与分享，永久免费。`
  const recording = {
    '@type': 'MusicRecording',
    '@id': `${pageUrl}#recording`,
    name: title,
    url: pageUrl,
    image: imageUrl,
    description,
    inLanguage: ['zh-CN', 'en'],
    byArtist: { '@type': 'MusicGroup', name: artist },
  }
  if (music.album) {
    recording.inAlbum = { '@type': 'MusicAlbum', name: music.album }
  }
  if (Number(music.duration) > 0) {
    recording.duration = `PT${Number(music.duration)}S`
  }

  applyPageSeo({
    title: `${title} - ${artist} | Neko歌姬计划`,
    description,
    keywords: `${title},${artist}${music.album ? `,${music.album}` : ''},免费在线播放,歌词,在线听歌,Neko歌姬计划`,
    path: `/detail/${id}`,
    image: `/media/cover/${id}`,
    imageAlt: `${title} - ${artist} 封面`,
    type: 'music.song',
    robots: DEFAULT_ROBOTS,
    jsonLd: {
      '@context': 'https://schema.org',
      '@graph': [
        {
          '@type': 'WebPage',
          '@id': `${pageUrl}#webpage`,
          url: pageUrl,
          name: `${title} - ${artist} | Neko歌姬计划`,
          description,
          primaryImageOfPage: imageUrl,
          mainEntity: { '@id': `${pageUrl}#recording` },
          inLanguage: ['zh-CN', 'en'],
        },
        recording,
      ],
    },
  })
}

const fetchMusicDetail = async (musicId) => {
  const seq = ++detailRequestSeq
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json'
      }
    })
    
    const data = await response.json()
    // 期间已经切到了别的曲目：丢弃这次过期结果
    if (seq !== detailRequestSeq) return
    if (data.success) {
      currentMusic.value = data.data
      applyMusicSeo(data.data)
      const count = Number(data.data?.favoriteCount)
      favoriteCount.value = Number.isFinite(count) ? count : null
      const lyricsText = typeof data.data?.lyrics === 'string' ? data.data.lyrics : ''
      lyrics.value = lyricsText
      parsedLyrics.value = parseLyricsPayload(data.data)
      syncPlayStateFromStorage()
    } else {
      console.error('获取音乐详情失败:', data.message)
      applyPageSeo({
        title: '音乐不存在 - Neko歌姬计划',
        description: '该歌曲不存在或已下架。',
        path: `/detail/${musicId}`,
        robots: PRIVATE_ROBOTS,
        jsonLd: null,
      })
    }
  } catch (error) {
    if (seq === detailRequestSeq) console.error('请求音乐详情时出错:', error)
  }
}

/**
 * 按 id 载入一首曲目（详情 + 歌词），并复位与「上一首」强相关的派生状态。
 * 首帧进页与后续切歌（路由变化 / 全局播放器换曲）都走这里。
 */
const loadMusicById = async (musicId) => {
  const id = String(musicId)

  // 详情页展示 URL 指定的曲目；播放引擎只在用户明确操作后切换。
  currentMusic.value = null
  favoriteCount.value = null

  lyrics.value = ''
  parsedLyrics.value = []
  videoRenderSubmitted.value = false
  videoRenderReady.value = false
  videoRenderJobId.value = ''
  await fetchMusicDetail(id)
  // 启动定时器以持续更新歌词 / 对齐全局播放状态
  startTimer()
}

/** 进入详情或外部切歌后，与 localStorage 对齐本页「是否正在播当前曲」 */
const syncPlayStateFromStorage = () => {
  try {
    const playing = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null')
    const state = JSON.parse(localStorage.getItem('globalPlayerState') || 'null')
    // 统一用字符串比较，避免后端返回 number、列表/localStorage 存 string 时类型不一致
    // 导致同步被跳过（表现为播放条不随播放推进）。
    if (playing && currentMusic.value && String(playing.id) === String(currentMusic.value.id) && state) {
      isPlaying.value = !!state.isPlaying
      currentTime.value = state.currentTime ?? 0
      duration.value = state.duration ?? currentMusic.value.duration ?? 0
    } else {
      isPlaying.value = false
      currentTime.value = 0
      duration.value = currentMusic.value?.duration || 0
    }
  } catch {
    isPlaying.value = false
  }
}

// 监听全局播放器状态变化
const handlePlayerStateChange = (e) => {
  const state = e.detail
  const currentPlayingMusic = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null')
  if (!currentMusic.value) return
  // 统一用字符串比较，避免后端返回 number、列表/localStorage 存 string 时类型不一致
  // 导致同步被跳过（表现为播放条不随播放推进）。
  if (currentPlayingMusic && String(currentPlayingMusic.id) === String(currentMusic.value.id)) {
    isPlaying.value = state.isPlaying
    currentTime.value = state.currentTime
    duration.value = state.duration
  } else {
    isPlaying.value = false
  }
}

// 获取用户token
const getToken = () => {
  return localStorage.getItem('userToken');
}

// 检查用户是否登录
const isLoggedIn = () => {
  return !!getToken();
}

const loadUserVipFromStorage = () => {
  userIsVip.value = !!getUser()?.isVip
}

const handleVipSync = () => {
  loadUserVipFromStorage()
}

const trackDurationSec = computed(() => {
  const d = Number(currentMusic.value?.duration)
  return Number.isFinite(d) && d > 0 ? Math.floor(d) : 0
})

const maxClipStartSec = computed(() => {
  const dur = trackDurationSec.value
  if (dur <= 0) return 0
  if (userIsVip.value) {
    return Math.max(0, dur - 1)
  }
  return Math.max(0, dur - NON_VIP_CLIP_SEC)
})

const clipPreviewDurationSec = computed(() => {
  const dur = trackDurationSec.value
  if (dur <= 0) return 0
  const remain = dur - clipStartSec.value
  if (remain <= 0) return 0
  if (userIsVip.value) return remain
  return Math.min(NON_VIP_CLIP_SEC, remain)
})

const clipEndSec = computed(() => clipStartSec.value + clipPreviewDurationSec.value)

const formatClipTime = (sec) => {
  const s = Math.max(0, Math.floor(Number(sec) || 0))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${m}:${String(r).padStart(2, '0')}`
}

const clampClipStartSec = (value) => {
  const v = Math.floor(Number(value) || 0)
  return Math.min(Math.max(0, v), maxClipStartSec.value)
}

/** 若当前正在播放本页歌曲，从该时间点起剪；否则从 0 秒 */
const getDefaultClipStartSec = () => {
  try {
    const playing = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null')
    const state = JSON.parse(localStorage.getItem('globalPlayerState') || 'null')
    if (playing && currentMusic.value && String(playing.id) === String(currentMusic.value.id) && state?.currentTime > 0) {
      return Math.floor(state.currentTime)
    }
  } catch {
    /* ignore */
  }
  return 0
}

const revokeClipPreviewBlobs = () => {
  for (const url of clipPreviewBlobUrlByMusicId.values()) {
    URL.revokeObjectURL(url)
  }
  clipPreviewBlobUrlByMusicId.clear()
}

const waitAudioEvent = (audio, eventName, timeoutMs = 15000) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => {
    cleanup()
    reject(new Error(`${eventName} timeout`))
  }, timeoutMs)
  const cleanup = () => {
    clearTimeout(timer)
    audio.removeEventListener(eventName, onOk)
    audio.removeEventListener('error', onErr)
  }
  const onOk = () => {
    cleanup()
    resolve()
  }
  const onErr = () => {
    cleanup()
    reject(new Error('audio error'))
  }
  audio.addEventListener(eventName, onOk, { once: true })
  audio.addEventListener('error', onErr, { once: true })
})

const ensurePreviewBlobUrl = async (musicId) => {
  const cached = clipPreviewBlobUrlByMusicId.get(musicId)
  if (cached) return cached
  const res = await fetch(await resolveMediaUrl(musicId))
  if (!res.ok) {
    throw new Error(`fetch ${res.status}`)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  clipPreviewBlobUrlByMusicId.set(musicId, url)
  return url
}

const seekPreviewAudio = (audio, startSec) => new Promise((resolve, reject) => {
  const target = Math.min(startSec, Math.max(0, (audio.duration || startSec) - 0.05))
  if (!Number.isFinite(target) || Math.abs(audio.currentTime - target) <= 0.05) {
    resolve()
    return
  }
  const timer = setTimeout(() => {
    cleanup()
    reject(new Error('seek timeout'))
  }, 10000)
  const cleanup = () => {
    clearTimeout(timer)
    audio.removeEventListener('seeked', onSeeked)
    audio.removeEventListener('error', onErr)
  }
  const onSeeked = () => {
    cleanup()
    resolve()
  }
  const onErr = () => {
    cleanup()
    reject(new Error('seek error'))
  }
  audio.addEventListener('seeked', onSeeked, { once: true })
  audio.addEventListener('error', onErr, { once: true })
  try {
    audio.currentTime = target
  } catch (e) {
    cleanup()
    reject(e)
  }
})

const preparePreviewAudio = async (musicId, startSec) => {
  const blobUrl = await ensurePreviewBlobUrl(musicId)
  const audio = new Audio()
  audio.preload = 'auto'
  audio.src = blobUrl
  if (audio.readyState < 1) {
    await waitAudioEvent(audio, 'loadedmetadata')
  }
  await seekPreviewAudio(audio, startSec)
  return audio
}

const stopClipPreview = () => {
  clipPreviewLoading = false
  clipPreviewPlaying.value = false
  if (clipPreviewAudio) {
    clipPreviewAudio.ontimeupdate = null
    clipPreviewAudio.onended = null
    clipPreviewAudio.pause()
    clipPreviewAudio.removeAttribute('src')
    clipPreviewAudio.load()
    clipPreviewAudio = null
  }
}

const onClipRangeChange = () => {
  clipStartSec.value = clampClipStartSec(clipStartSec.value)
  if (clipPreviewPlaying.value) {
    stopClipPreview()
  }
}

const toggleClipPreview = async () => {
  if (clipPreviewLoading) return
  if (clipPreviewPlaying.value) {
    stopClipPreview()
    return
  }
  if (!currentMusic.value || clipPreviewDurationSec.value <= 0) return

  stopClipPreview()
  clipPreviewLoading = true
  const start = clipStartSec.value
  const end = clipEndSec.value
  const musicId = currentMusic.value.id

  try {
    window.dispatchEvent(new Event('pauseGlobalPlayer'))
    const audio = await preparePreviewAudio(musicId, start)
    clipPreviewAudio = audio

    audio.ontimeupdate = () => {
      if (audio.currentTime >= end - 0.05) {
        stopClipPreview()
      }
    }
    audio.onended = () => stopClipPreview()

    clipPreviewPlaying.value = true
    await audio.play()
  } catch (e) {
    // 用户拖动「成片起始」会 stopClipPreview() → 对试听音频触发新的 load，
    // 从而打断本次 play() 返回的 Promise —— 这是正常交互，不是失败，
    // 不该弹「试听失败」。
    if (e?.name !== 'AbortError') {
      console.error('clip preview failed:', e)
      toast.error(t('player.previewFailed'))
    }
    stopClipPreview()
  } finally {
    clipPreviewLoading = false
  }
}

const closeVideoModal = () => {
  stopClipPreview()
  revokeClipPreviewBlobs()
  videoModalOpen.value = false
}

const openVideoRenderDialog = () => {
  if (!currentMusic.value || videoRenderBusy.value) return
  if (!isLoggedIn()) {
    toast.error(t('common.needLogin'))
    return
  }
  videoWatermarkChoice.value = !userIsVip.value
  clipStartSec.value = clampClipStartSec(getDefaultClipStartSec())
  stopClipPreview()
  videoModalOpen.value = true
}

const checkVideoJobFromQuery = async (jobId) => {
  if (!jobId || !isLoggedIn()) return
  videoRenderJobId.value = jobId
  try {
    const data = await fetchVideoRenderStatus(jobId)
    if (data.status === 'done') {
      videoRenderReady.value = true
      videoRenderSubmitted.value = false
    } else if (data.status === 'failed') {
      toast.error(data.error || t('player.videoRenderFailed'))
    } else {
      videoRenderSubmitted.value = true
      toast.info(t('player.videoRendering'))
    }
  } catch (e) {
    toast.error(e.message || t('player.queryRenderFailed'))
  }
}

const confirmVideoRender = async () => {
  if (!currentMusic.value || videoRenderBusy.value) return
  if (!userIsVip.value && !videoWatermarkChoice.value) {
    toast.error(t('player.watermarkRequired'))
    return
  }

  videoRenderBusy.value = true
  videoRenderReady.value = false

  try {
    const startSec = clampClipStartSec(clipStartSec.value)
    if (clipPreviewDurationSec.value <= 0) {
      toast.error(t('player.invalidRange'))
      return
    }
    const watermarked = userIsVip.value ? videoWatermarkChoice.value : true
    const data = await createVideoRenderJob(currentMusic.value.id, startSec, watermarked)
    videoRenderJobId.value = data.jobId || ''
    if (typeof data.remainingToday === 'number') {
      videoRenderRemainingToday.value = data.remainingToday
    }
    if (!videoRenderJobId.value) {
      throw new Error(t('player.noTaskId'))
    }
    videoRenderSubmitted.value = true
    videoModalOpen.value = false
    toast.success(t('player.taskSubmitted'))
  } catch (e) {
    toast.error(e.message || t('player.createTaskFailed'))
  } finally {
    videoRenderBusy.value = false
  }
}

const downloadRenderedVideo = async () => {
  if (!videoRenderJobId.value) return
  try {
    const name = `${currentMusic.value?.title || 'clip'}.mp4`.replace(/[/\\?%*:|"<>]/g, '_')
    await downloadVideoRenderFile(videoRenderJobId.value, name)
    toast.success(t('player.downloadStarted'))
  } catch (e) {
    toast.error(e.message || t('player.downloadVideoFailed'))
  }
}

// 检查音乐是否已收藏
const isFavorite = (musicId) => {
  return favoriteMusicIds.value.has(musicId);
}

// 切换收藏状态
const toggleFavorite = async () => {
  if (!currentMusic.value) return;
  
  if (!isLoggedIn()) {
    toast.error(t('common.needLogin'));
    return;
  }
  
  const token = getToken();
  
  if (isFavorite(currentMusic.value.id)) {
    // 取消收藏
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites/${currentMusic.value.id}`, {
        method: 'DELETE',
        headers: {
          'Authorization': token
        }
      });
      
      const data = await response.json();
      if (data.success) {
        favoriteMusicIds.value.delete(currentMusic.value.id);
        favoriteCount.value = Math.max(0, (favoriteCount.value ?? 0) - 1);
        toast.success(t('player.unfavoriteSuccess'));
      } else {
        console.error('取消收藏失败:', data.message);
        toast.error(t('player.unfavoriteFailed') + ': ' + data.message);
      }
    } catch (error) {
      console.error('取消收藏失败:', error);
      toast.error(t('player.unfavoriteFailed'));
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
      });
      
      const data = await response.json();
      if (data.success) {
        favoriteMusicIds.value.add(currentMusic.value.id);
        favoriteCount.value = (favoriteCount.value ?? 0) + 1;
        toast.success(t('player.favoriteSuccess'));
      } else {
        console.error('收藏失败:', data.message);
        toast.error(t('player.favoriteFailed') + ': ' + data.message);
      }
    } catch (error) {
      console.error('收藏失败:', error);
      toast.error(t('player.favoriteFailed'));
    }
  }
}

// 获取收藏列表
const fetchFavorites = async () => {
  if (!isLoggedIn()) {
    return;
  }
  
  try {
    const token = getToken();
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
      method: 'GET',
      headers: {
        'Authorization': token
      }
    });
    
    const data = await response.json();
    if (data.success) {
      // 提取所有收藏的音乐ID
      favoriteMusicIds.value = new Set(data.favorites.map(m => m.id));
    }
  } catch (error) {
    console.error('获取收藏列表失败:', error);
  }
}

// 下载音乐
const downloadMusic = async () => {
  if (currentMusic.value) {
    try {
      // 使用fetch API获取音乐文件
      const response = await fetch(await resolveMediaUrl(currentMusic.value.id));
      const blob = await response.blob();

      // 从 Content-Type 响应头中提取正确的文件扩展名
      const contentType = response.headers.get('Content-Type') || 'audio/mpeg';
      const extension = mapContentTypeToExtension(contentType);

      // 创建下载链接
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = currentMusic.value.filename || `${currentMusic.value.title}.${extension}`;

      // 添加到DOM，点击并移除
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      // 释放URL对象
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error('下载音乐失败:', error);

      toast.error(t('track.downloadFailed'));
    }
  }
}

// 将 Content-Type 映射到文件扩展名
const mapContentTypeToExtension = (contentType) => {
  const type = contentType.toLowerCase();
  if (type.includes('flac')) return 'flac';
  if (type.includes('wav')) return 'wav';
  if (type.includes('ogg')) return 'ogg';
  if (type.includes('aac')) return 'aac';
  if (type.includes('m4a') || type.includes('mp4')) return 'm4a';
  if (type.includes('wma')) return 'wma';
  if (type.includes('ape')) return 'ape';
  if (type.includes('mpeg') || type.includes('mp3')) return 'mp3';
  console.warn('未知的 Content-Type:', contentType, '使用 mp3');
  return 'mp3';
}

// 格式化时长为分秒格式
const formatDuration = (duration) => {
  if (!duration || duration < 0) return '0:00'
  
  const minutes = Math.floor(duration / 60)
  const seconds = Math.floor(duration % 60)
  
  return `${minutes}:${seconds < 10 ? '0' : ''}${seconds}`
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = '/src/assets/default-cover.png'; // 使用默认封面
}

// 定期检查播放时间，确保歌词实时更新
const startTimer = () => {
  if (timeUpdateInterval) {
    clearInterval(timeUpdateInterval);
  }
  
  timeUpdateInterval = setInterval(() => {
    // 从localStorage获取当前播放状态
    const storedState = localStorage.getItem('globalPlayerState');
    if (storedState) {
      const state = JSON.parse(storedState);
      
      // 检查当前播放的音乐是否是本页面的音乐
      const currentPlayingMusic = JSON.parse(localStorage.getItem('currentPlayingMusic') || 'null');
      if (currentPlayingMusic && currentMusic.value && String(currentPlayingMusic.id) === String(currentMusic.value.id)) {
        // 与全局播放器对齐时间/时长/播放态（歌词墙自行按时间插值）
        currentTime.value = state.currentTime;
        duration.value = state.duration;
        isPlaying.value = state.isPlaying;
      } else if (currentMusic.value) {
        isPlaying.value = false
      }
    }
  }, 300); // 每300毫秒更新一次，平衡性能和流畅度
};

/* ============================================================================
   全屏播放页：进度、模式、开合
   -------------------------------------------------------------------------- */

/** 显示用时间：拖动中优先用本地预览值 */
const displayTime = computed(() => (seeking.value ? seekPreview.value : currentTime.value))

/** 进度百分比 */
const progressPercent = computed(() => {
  const total = duration.value
  if (!total || !Number.isFinite(total) || total <= 0) return 0
  return Math.max(0, Math.min(100, (displayTime.value / total) * 100))
})

/** 拖动中：只更新本地预览，不发指令 */
const onSeekInput = (event) => {
  seeking.value = true
  seekPreview.value = Number(event.target.value) || 0
}

/** 松手：提交 seek 指令 */
const onSeekCommit = (event) => {
  const time = Number(event.target.value) || 0
  seekPreview.value = time
  seeking.value = false
  sendPlayerCommand('seek', { time })
}

/**
 * 点击歌词行 → 跳到该行。
 * 与参考实现一致：暂停时点击也会接着播放（「从这句开始听」的预期），
 * 否则点了之后画面跳了却没声音，会让人以为没生效。
 */
const onLyricSeek = (seconds) => {
  const time = Number(seconds)
  if (!Number.isFinite(time) || time < 0) return
  sendPlayerCommand('seek', { time })
  if (!isPlaying.value) sendPlayerCommand('play')
}

const openLyricsReport = () => {
  if (!currentMusic.value) return
  router.push(`/lyrics-report/${currentMusic.value.id}`)
}

/** 播放模式图标与标题（状态来自桥接层；playback 是 reactive 对象，不是 ref） */
const modeIcon = computed(() => {
  if (playback.playbackMode === 'single_repeat') return 'repeat-1'
  if (playback.playbackMode === 'shuffle') return 'shuffle'
  return 'repeat'
})

const modeTitle = computed(() => {
  if (playback.playbackMode === 'single_repeat') return t('player.modeSingleLoop')
  if (playback.playbackMode === 'shuffle') return t('player.modeShuffle')
  return t('player.modeListLoop')
})

/** 收起播放页：先播退出动效，动效结束再离开路由 */
const close = () => {
  if (closing.value) return
  closing.value = true
}

const onAfterLeave = () => {
  const back = typeof window !== 'undefined' ? window.history.state?.back : null
  if (back && back !== route.fullPath) {
    router.back()
  } else {
    router.push('/')
  }
}

/** 进场动效结束：.np 不再有 transform，可以安全创建 WebGL 背景 */
const onAfterEnter = () => {
  if (backgroundReadyTimer) {
    clearTimeout(backgroundReadyTimer)
    backgroundReadyTimer = null
  }
  backgroundReady.value = true
}

/* ============================================================================
   手机端手势：下拉收起
   ----------------------------------------------------------------------------
   全屏播放页在手机上「下滑关闭」是通用预期。仅在窄屏启用：
   桌面拖拽没有意义，还会干扰文本选中与滑块操作。
   ========================================================================== */

/** 触发收起的下拉距离阈值（px） */
const SWIPE_CLOSE_THRESHOLD = 96

/** 仅在窄屏启用（随窗口尺寸变化） */
let swipeEnabled = false
/** 当前手指下拉位移，0 = 未在拖动 */
const dragY = ref(0)
/** 是否正在拖动（拖动期间关掉 transition，让画面即时跟手） */
const dragging = ref(false)
let touchStartY = 0
let touchStartX = 0
/**
 * 本次触摸是否属于「下拉收起」手势。
 * 起手落在进度条 / 按钮等交互控件上时保持 false，后续 move / end 一律不接管。
 * 否则会拿上一次手势的起点（或初始 0）算位移，把「拖进度条 / 点按钮」误判成
 * 下拉而收起播放页。
 */
let swipeTracking = false

/** 从这些区域起手不参与下拉收起（进度条、控制键、链接等） */
const SWIPE_IGNORE_SELECTOR = '.np__bottom, input, button, a, [role="slider"]'

function syncSwipeEnabled() {
  swipeEnabled = !!window.matchMedia?.('(max-width: 900px)')?.matches
}

function onTouchStart(e) {
  // 起手先复位：上一次手势若被多指/取消打断，别把位移残留到这一次
  swipeTracking = false
  dragY.value = 0
  dragging.value = false
  if (!swipeEnabled || closing.value || e.touches.length !== 1) return
  // 底栏与可交互控件自己要用触摸事件，别抢
  if (e.target?.closest?.(SWIPE_IGNORE_SELECTOR)) return
  swipeTracking = true
  touchStartY = e.touches[0].clientY
  touchStartX = e.touches[0].clientX
}

function onTouchMove(e) {
  if (!swipeTracking || !swipeEnabled || closing.value || e.touches.length !== 1) return
  const dy = e.touches[0].clientY - touchStartY
  const dx = Math.abs(e.touches[0].clientX - touchStartX)
  // 向上滑，或横向位移更大 → 交还给内部滚动 / 不接管
  if (dy <= 0 || dx > Math.abs(dy)) {
    if (dragY.value !== 0) dragY.value = 0
    dragging.value = false
    return
  }
  dragging.value = true
  // 阻尼：越往下越沉，避免「一根手指把整页拽走」的失重感
  dragY.value = Math.min(dy * 0.55, 200)
}

function onTouchEnd() {
  if (!swipeTracking) return
  swipeTracking = false
  const shouldClose = swipeEnabled && dragY.value > SWIPE_CLOSE_THRESHOLD
  dragging.value = false
  dragY.value = 0
  if (shouldClose) close()
}

// 初始化
onMounted(async () => {
  // 兜底：若无 CSS 过渡（或 after-enter 未触发），定时放出 WebGL 背景
  backgroundReadyTimer = setTimeout(onAfterEnter, 700)

  // 检测是否是移动设备
  isMobile.value = checkMobile()

  // 全屏播放页期间锁定页面滚动（背景仍在，但不该被滚动）
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'

  // 下拉收起手势只在窄屏启用，窗口尺寸变化时同步
  syncSwipeEnabled()
  window.addEventListener('resize', syncSwipeEnabled)

  // 监听自定义事件，以响应全局播放器的状态变化
  window.addEventListener('playerStateChange', handlePlayerStateChange)
  window.addEventListener(USER_VIP_SYNC_EVENT, handleVipSync)
  loadUserVipFromStorage()
  if (isLoggedIn()) {
    syncUserVipFromPlaylistsApi()
  }

  const musicId = route.params.id
  if (checkMobile() && musicId) {
    tryOpenMusicDetailInApp(musicId)
  }

  if (musicId) {
    playRouteMusic()
    await loadMusicById(String(musicId))
  }

  if (route.query.comments === '1') {
    commentsOpen.value = true
  }

  // 获取收藏列表
  await fetchFavorites();

  const videoJob = route.query.videoJob
  if (videoJob) {
    await checkVideoJobFromQuery(String(videoJob))
  }
})

// 组件卸载时移除事件监听和定时器
onUnmounted(() => {
  stopClipPreview()
  revokeClipPreviewBlobs()
  if (backgroundReadyTimer) {
    clearTimeout(backgroundReadyTimer)
    backgroundReadyTimer = null
  }
  document.body.style.overflow = previousBodyOverflow
  window.removeEventListener('resize', syncSwipeEnabled)
  window.removeEventListener('playerStateChange', handlePlayerStateChange)
  window.removeEventListener(USER_VIP_SYNC_EVENT, handleVipSync)
  if (timeUpdateInterval) {
    clearInterval(timeUpdateInterval);
    timeUpdateInterval = null;
  }
})

/* ============================================================================
   与全局播放器同步曲目
   ----------------------------------------------------------------------------
   全局播放条（GlobalPlayer）是全站唯一播放引擎。切歌可能来自三处：
     · 播放页底部的上一首 / 下一首（发 playerCommand 给 GlobalPlayer）
     · 播放条的上一首 / 下一首 / 播放列表点选
     · 一首播完自动切下一首
   无论哪条来源，播放页都要跟着切到新曲目 —— 否则会出现「页面还停在
   上一首、播放条已经下一首」的错位。
   ========================================================================== */

/**
 * 全局当前曲目变化（上一首 / 下一首 / 自动切歌）→ 播放页地址跟随。
 *
 * 两道守卫：
 *  1. 「路由目标未对齐」时忽略：显式进入 /detail/:id 后，引擎切过去之前桥接层
 *     可能还持有上一首，此时绝不能把地址栏 replace 回上一首。只忽略这一次残留，
 *     之后引擎真正的切歌（含用户点下一首）照常跟随。
 *  2. 只跟随「确实已经持久化的当前曲目」，避免旧事件短暂持有上一首时来回横跳。
 * replace 不污染历史。
 */
watch(
  () => route.query.comments,
  (value) => {
    if (value === '1') commentsOpen.value = true
  }
)

watch(
  () => playback.currentMusic?.id,
  (id) => {
    if (!id) return

    if (routeTargetMusicId) {
      const matchesTarget = String(routeTargetMusicId) === String(id)
      // 进入本页时残留的上一首：只忽略它一次
      const isEntryStale =
        stalePlaybackMusicId != null && String(stalePlaybackMusicId) === String(id)
      stalePlaybackMusicId = null

      if (matchesTarget) {
        // 引擎已切到路由目标 → 对齐完成
        routeTargetMusicId = null
      } else if (isEntryStale) {
        // 仍是上一首的残留状态 → 忽略，等引擎切到路由目标
        return
      } else {
        // 目标是别的、引擎却真的换了歌（如用户点了下一首）→ 放弃旧目标，下面照常跟随
        routeTargetMusicId = null
      }
    } else {
      stalePlaybackMusicId = null
    }

    const persisted = getPersistedCurrentMusicId()
    if (!persisted || String(persisted) !== String(id)) return
    if (String(route.params.id) === String(id)) return
    router.replace(`/detail/${id}`)
  }
)

/** 路由曲目变化（含上面 replace 的结果）→ 重新载入详情与歌词 */
watch(
  () => route.params.id,
  (id) => {
    if (!id) return
    playRouteMusic()
    if (String(currentMusic.value?.id) === String(id)) return
    loadMusicById(String(id))
  }
)
</script>

<style scoped>
/* ============================================================================
   全屏播放页
   ----------------------------------------------------------------------------
   结构与参考实现一致：整屏覆盖，自底部滑入/滑出。
   背景层（AlbumBackground）→ 压暗层 → 顶栏 / 主体 / 底栏。
   形状统一圆角矩形，黑偏青，全部走 --n-* 令牌。
   ========================================================================== */
.np {
  position: fixed;
  inset: 0;
  z-index: var(--n-z-player);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--n-text);
  background: var(--n-bg);
  /* 下拉收起：跟手时由 .np--dragging 关掉过渡，松手后靠它回弹 */
  transition: transform 240ms var(--n-ease);
}

/* 层叠：背景 0 · 压暗 1 · 内容 2 */
.np__scrim {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(4, 9, 11, 0.68), rgba(4, 9, 11, 0.9));
}

/* 轻量模式（手机 / 无浮点渲染目标）下背景是【静态模糊封面】，本身就不亮。
   这一层是按 WebGL 那种高亮动态背景调的，0.68→0.9 会把静态封面彻底盖掉，
   实测只透出 3%–13%，看起来就像「没有背景」。这里减半，
   顶/底栏的对比度交给 AlbumBackground 的 .bg__tint 上下压暗去保证。 */
.np--lite-bg .np__scrim {
  background: linear-gradient(180deg, rgba(4, 9, 11, 0.28), rgba(4, 9, 11, 0.46));
}

/* ===== 进出场：自底部滑入 / 滑出 ===== */
.np-enter-active,
.np-leave-active {
  transition: transform 460ms var(--n-ease-out), opacity 460ms var(--n-ease-out);
}

.np-enter-from,
.np-leave-to {
  transform: translateY(100%);
  opacity: 0.55;
}

/* 手指按住拖动期间必须即时跟手，不能有过渡 */
.np--dragging {
  transition: none;
}

/* ===== 顶栏 ===== */
.np__top {
  position: relative;
  z-index: 2;
  flex: none;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--n-space-4);
  /* 刘海屏：顶栏内容下沉到状态栏下面 */
  padding: calc(var(--n-space-4) + var(--n-safe-top)) clamp(20px, 4vw, 56px) var(--n-space-2);
}

.np__top-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  text-align: center;
}

.np__top-name {
  font-size: var(--n-text-base);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.np__top-artist {
  font-size: var(--n-text-xs);
  color: var(--n-text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.np__top-actions {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
}

/* 宽屏：标题相对【整条顶栏】居中。
   两侧内容天然不等宽（左：返回键 1 个；右：评论/收藏/下载/视频等多个），
   原来的 auto/1fr/auto 会把中列挤向较窄的一侧，标题看起来偏左。
   给两侧等宽的 1fr，中列按内容宽度居中即可。 */
@media (min-width: 561px) {
  .np__top {
    grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  }

  .np__top-title {
    justify-self: center;
    /* 避免长标题把两侧操作区挤掉 */
    max-width: min(46vw, 560px);
  }

  .np__top-actions {
    justify-self: end;
  }
}

/* ===== 图标按钮（圆角矩形，非圆形） ===== */
.np-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
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

.np-icon--lg {
  width: 48px;
  height: 48px;
}

.np-icon--sm {
  width: 30px;
  height: 30px;
}

@media (hover: hover) {
  .np-icon:hover:not(:disabled) {
    background: var(--n-surface-hover);
    color: var(--n-text);
    border-color: var(--n-line);
  }
}

.np-icon:active:not(:disabled) {
  background: var(--n-surface-active);
}

.np-icon:disabled {
  opacity: 0.36;
  cursor: default;
}

.np-icon.is-on {
  color: var(--n-accent);
}

.np-icon.np-icon--favorite {
  width: auto;
  min-width: 40px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.np-icon__count {
  font-size: 11px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.np-icon:focus-visible,
.np-play:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--n-accent-soft);
}

/* ===== 移动端下载提示（卡片式，见 MobileAppBanner） ===== */
.np__app-banner {
  position: relative;
  z-index: 2;
  flex: none;
  margin-top: var(--n-space-2);
}

/* ===== 主体 ===== */
.np__body {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 32%) minmax(0, 1fr);
  gap: clamp(20px, 3.4vw, 56px);
  padding: 0 clamp(20px, 4vw, 56px);
}

/* ===== 左：封面 / 信息 / 操作 ===== */
.np__aside {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-4);
  min-width: 0;
  padding: var(--n-space-4) 0;
  overflow-y: auto;
}

.np__loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.np__loading p {
  margin: 0;
}

.np__cover {
  position: relative;
  width: min(100%, 38vh);
  aspect-ratio: 1 / 1;
  border-radius: var(--n-radius-xl);
  overflow: hidden;
  box-shadow: 0 28px 70px rgba(0, 0, 0, 0.55);
  background: var(--n-surface-sunken);
}

.np__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 封面顶部高光，避免在深色里显得死板 */
.np__cover-sheen {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.14), transparent 46%);
}

.np__meta {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  min-width: 0;
  text-align: center;
}

.np__title {
  margin: 0;
  font-size: clamp(1.15rem, 1.9vw, 1.6rem);
  font-weight: var(--n-weight-semibold);
  line-height: var(--n-leading-tight);
  color: var(--n-text);
}

.np__artist {
  margin: 0;
  font-size: var(--n-text-base);
  color: var(--n-text-muted);
}

.np__album {
  margin: 0;
  font-size: var(--n-text-sm);
  color: var(--n-text-faint);
}

.np__dur {
  margin: 3px 0 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--n-text-xs);
  color: var(--n-text-faint);
  font-variant-numeric: tabular-nums;
}

.np__spectrum {
  width: min(100%, 38vh);
}

/* 生成分享视频的会员/免费权益提示（沿用重构前文案），贴在顶栏视频入口旁 */
.np__video-hint {
  margin: 0 0 0 var(--n-space-2);
  align-self: center;
  max-width: 34ch;
  font-size: var(--n-text-xs);
  line-height: 1.4;
  color: var(--n-text-faint);
  text-align: right;
}

.np__video-hint a {
  color: var(--n-accent);
}

.np__notice {
  display: flex;
  align-items: flex-start;
  gap: var(--n-space-2);
  width: 100%;
  padding: var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  font-size: var(--n-text-xs);
  color: var(--n-text-muted);
}

.np__notice p {
  margin: 0;
}

.np__notice--ready {
  border-color: var(--n-success);
  background: var(--n-success-soft);
}

.np__notice-body {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-2);
  align-items: flex-start;
}

.np__notice-meta {
  color: var(--n-text-faint);
}

/* ===== 右：歌词墙 ===== */
.np__lyrics {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: var(--n-space-4) 0;
}

/* LyricsWall 根元素默认给的是固定高度（服务普通页面）；
   全屏页里改为填满容器 */
.np__lyrics :deep(.wall) {
  height: 100%;
  min-height: 0;
}

.np__lyrics-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-2);
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

.np__lyrics-empty p {
  margin: 0;
}

/* ===== 底栏：进度 + 控制 ===== */
.np__bottom {
  position: relative;
  z-index: 2;
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-2);
  /* 手势条：底栏内容上抬到安全区之上 */
  padding: var(--n-space-3) clamp(20px, 4vw, 56px)
    calc(clamp(14px, 2.4vh, 26px) + var(--n-safe-bottom));
}

.np__seek {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  width: min(100%, 720px);
}

.np__time {
  flex: none;
  min-width: 42px;
  text-align: center;
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
  color: var(--n-text-muted);
}

.np__seek-track {
  position: relative;
  flex: 1;
  height: 18px;
  display: flex;
  align-items: center;
}

.np__seek-rail {
  position: absolute;
  left: 0;
  right: 0;
  height: 4px;
  border-radius: var(--n-radius-pill);
  background: rgba(120, 205, 218, 0.2);
  overflow: hidden;
}

.np__seek-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--n-accent-deep), var(--n-accent));
}

.np__seek-input {
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
  .np__seek-track:hover .np__seek-rail {
    height: 6px;
  }
}

.np__seek-track:focus-within .np__seek-rail {
  box-shadow: 0 0 0 3px var(--n-accent-soft);
}

.np__controls {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
}

/* 主播放键：圆角矩形实底 */
.np-play {
  flex: none;
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin: 0 var(--n-space-2);
  padding: 0;
  border: 1px solid var(--n-accent-line);
  border-radius: var(--n-radius-lg);
  background: linear-gradient(160deg, var(--n-accent), var(--n-accent-deep));
  color: var(--n-text-inverse);
  cursor: pointer;
  box-shadow: 0 10px 28px rgba(47, 159, 178, 0.34);
  transition: transform var(--n-duration-fast) var(--n-ease),
    box-shadow var(--n-duration-fast) var(--n-ease), filter var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .np-play:hover:not(:disabled) {
    transform: translateY(-1px);
    filter: brightness(1.08);
    box-shadow: 0 14px 34px rgba(47, 159, 178, 0.44);
  }
}

.np-play:active:not(:disabled) {
  transform: translateY(0) scale(0.97);
}

.np-play:disabled {
  background: var(--n-surface-active);
  border-color: var(--n-line);
  color: var(--n-text-faint);
  box-shadow: none;
  cursor: default;
}

.np__controls-spacer {
  width: 48px;
}

/* ===== 响应式 =====
   断点约定见 design/tokens.css：560 手机竖屏 / 900 平板 / 1200 桌面 */
@media (max-width: 900px) {
  .np__body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    gap: var(--n-space-4);
    overflow-y: auto;
    overscroll-behavior: contain;
  }

  .np__aside {
    padding: 0;
    overflow: visible;
  }

  .np__cover {
    width: min(48%, 200px);
  }

  .np__spectrum {
    display: none;
  }

  .np__lyrics {
    padding: 0 0 var(--n-space-4);
    min-height: 240px;
  }
}

/* 手机横屏（高度很扁）：改回左右分栏，把有限的高度让给歌词 */
@media (max-width: 900px) and (orientation: landscape) {
  .np__body {
    grid-template-columns: minmax(0, 38%) minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr);
    overflow: hidden;
  }

  .np__cover {
    width: min(100%, 32vh);
  }

  .np__album,
  .np__dur {
    display: none;
  }

  .np__lyrics {
    min-height: 0;
  }
}

/* 手机竖屏 */
@media (max-width: 560px) {
  .np__top {
    gap: var(--n-space-2);
    padding-left: var(--n-space-4);
    padding-right: var(--n-space-4);
  }

  .np__top-title {
    text-align: left;
  }

  .np__bottom {
    padding-left: var(--n-space-4);
    padding-right: var(--n-space-4);
    padding-top: var(--n-space-2);
  }

  .np__body {
    /* 手机上整页不再滚动：封面滑走、只剩歌词的体验很别扭。
       改为封面按【可用高度】收敛，歌词吃掉剩余空间。 */
    overflow: hidden;
    overscroll-behavior: none;
    gap: var(--n-space-3);
    padding: 0 var(--n-space-4);
  }

  .np__aside {
    gap: var(--n-space-2);
    padding: var(--n-space-1) 0;
  }

  /* 同时受宽度与高度约束：短屏（如 568px 高的 iPhone SE）才不会被挤爆 */
  .np__cover {
    width: min(48%, 22vh, 168px);
  }

  .np__title {
    font-size: 1.05rem;
  }

  .np__artist {
    font-size: var(--n-text-sm);
  }

  /* 专辑名与时长在手机上省掉，把高度让给歌词 */
  .np__album,
  .np__dur {
    display: none;
  }

  /* 窄屏顶栏空间有限，权益提示省略（入口在顶栏，title 仍可见） */
  .np__video-hint {
    display: none;
  }

  .np__lyrics {
    min-height: 0;
    padding: 0 0 var(--n-space-2);
  }

  .np__seek {
    width: 100%;
    gap: var(--n-space-2);
  }

  .np__time {
    min-width: 36px;
  }

  .np-icon--lg {
    width: var(--n-tap-min);
    height: var(--n-tap-min);
  }

  .np-icon {
    width: var(--n-tap-min);
    height: var(--n-tap-min);
  }

  .np-icon.np-icon--favorite {
    min-width: var(--n-tap-min);
    padding: 0 6px;
  }

  .np-play {
    width: 54px;
    height: 54px;
    margin: 0;
  }

  /* 保留右侧占位，并与循环模式键等宽（同为 --n-tap-min）。
     否则左侧「模式+上一首」、右侧只有「下一首」，播放键会整体偏右，
     不再落在屏幕中线。 */
  .np__controls-spacer {
    display: block;
    width: var(--n-tap-min);
  }
}

@media (prefers-reduced-motion: reduce) {
  .np-enter-active,
  .np-leave-active {
    transition: none;
  }
}

/* ============================================================================
   分享视频弹窗（NModal 内容）
   ========================================================================== */
.clip-song {
  margin: 0 0 var(--n-space-4);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.clip-option {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  padding: var(--n-space-3) var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  cursor: pointer;
  font-size: var(--n-text-base);
}

.clip-option--locked {
  opacity: 0.6;
  cursor: not-allowed;
}

.clip-range {
  margin-top: var(--n-space-5);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius);
  background: var(--n-surface-soft);
}

.clip-range__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-3);
  font-size: var(--n-text-sm);
  color: var(--n-text-muted);
}

.clip-range__value {
  color: var(--n-accent-strong);
  font-variant-numeric: tabular-nums;
  font-weight: var(--n-weight-semibold);
}

.clip-range__slider {
  width: 100%;
  accent-color: var(--n-accent);
  cursor: pointer;
}

.clip-sub {
  margin: var(--n-space-3) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

/* ===== 评论抽屉 ===== */
.np__top-actions .np-icon {
  position: relative;
}

.np__comment-badge {
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

.np__comments-layer {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  justify-content: flex-end;
}

.np__comments-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.42);
  opacity: 1;
}

.np__comments {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--n-space-3);
  width: min(420px, 100%);
  height: 100%;
  padding: var(--n-space-4);
  background: var(--n-bg-elevated);
  border-left: 1px solid var(--n-line);
  box-shadow: -12px 0 32px rgba(0, 0, 0, 0.35);
}

.np__comments-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
}

.np__comments-title {
  font-size: 15px;
  font-weight: 600;
}

.np__comments-body {
  flex: 1 1 auto;
  min-height: 0;
}

/* 抽屉动效：遮罩淡入，面板自右滑入；关闭比打开更快，手感更跟手 */
.np-comments-enter-active .np__comments-mask,
.np-comments-leave-active .np__comments-mask {
  transition: opacity var(--n-duration-fast, 160ms) var(--n-ease);
}

.np-comments-enter-from .np__comments-mask,
.np-comments-leave-to .np__comments-mask {
  opacity: 0;
}

.np-comments-enter-active .np__comments {
  transition: transform var(--n-duration, 240ms) var(--n-ease-out);
  will-change: transform;
}

.np-comments-leave-active .np__comments {
  transition: transform var(--n-duration-fast, 160ms) var(--n-ease-in-out);
  will-change: transform;
}

.np-comments-enter-from .np__comments,
.np-comments-leave-to .np__comments {
  transform: translateX(100%);
}

/* 抽屉内容错开一拍入场，避免整块一起弹出的生硬感 */
.np-comments-enter-active .np__comments-head,
.np-comments-enter-active .np__comments-body {
  transition:
    opacity var(--n-duration, 240ms) var(--n-ease-out),
    transform var(--n-duration, 240ms) var(--n-ease-out);
}

.np-comments-enter-from .np__comments-head,
.np-comments-enter-from .np__comments-body {
  opacity: 0;
  transform: translateX(16px);
}

.np-comments-enter-from .np__comments-body {
  transition-delay: 40ms;
}

/* 关闭时内容只跟随面板滑出，不再单独做位移 */
.np-comments-leave-active .np__comments-head,
.np-comments-leave-active .np__comments-body {
  transition: none;
}

@media (prefers-reduced-motion: reduce) {
  .np-comments-enter-active .np__comments,
  .np-comments-leave-active .np__comments,
  .np-comments-enter-active .np__comments-head,
  .np-comments-enter-active .np__comments-body,
  .np-comments-enter-active .np__comments-mask,
  .np-comments-leave-active .np__comments-mask {
    transition: opacity var(--n-duration-instant, 90ms) linear;
  }

  .np-comments-enter-from .np__comments-head,
  .np-comments-enter-from .np__comments-body,
  .np-comments-enter-from .np__comments,
  .np-comments-leave-to .np__comments {
    transform: none;
  }

  .np-comments-enter-from .np__comments-body {
    transition-delay: 0ms;
  }
}
</style>

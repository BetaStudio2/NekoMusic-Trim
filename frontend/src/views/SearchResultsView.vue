<script setup>
/**
 * SearchResultsView —— 搜索结果
 * ------------------------------------------------------------
 * 排布参考主流音乐 App：查询标题 + 标签页（单曲 / 歌单 / 艺人），
 * 取代原先的三栏并排。（原三栏在窄屏与结果不均衡时体验很差）
 *
 * 数据契约（保持不变）：
 *  - POST /api/music/search    -> results[]
 *  - POST /api/playlists/search -> results[]
 *  - POST /api/artists/search  -> artist{name,musicCount,musicList}
 *  - 收藏：GET/POST/DELETE /api/user/favorites（Authorization: 裸 userToken）
 *  - 播放：经 usePlaybackBridge 起播；点单曲以搜索结果整份列表为播放队列
 */
import { ref, watch, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { resolveMediaUrl } from '@/utils/mediaUrl.js'
import NIcon from '@/icons/NIcon.vue'
import QualityBadge from '@/components/QualityBadge.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { useToast } from '@/composables/useToast'
import { playTrack, playTracks, isCurrentMusic } from '@/composables/usePlaybackBridge'
import NowPlayingTag from '@/components/NowPlayingTag.vue'
import { t } from '@/i18n'

const toast = useToast()
const route = useRoute()
const router = useRouter()

const searchQuery = ref('')
const searchLoading = ref(false)
const musicResults = ref([])
const playlistResults = ref([])
const artistPayload = ref({ name: '', musicCount: 0, musicList: [] })
const favoriteMusicIds = ref(new Set())
const tab = ref('tracks')

/**
 * 查询词来源：优先 ?q= 查询串，兼容历史 /search/{query} 路径。
 *  - 走查询串的原因：路径形式下 encodeURIComponent 会把查询词里的 / 编成 %2F，
 *    Jetty 视为「歧义路径分隔符」直接返回 400，请求根本到不了前端。
 *  - vue-router 已对参数解码，这里不能再 decodeURIComponent（查询词含 % 会抛错）。
 */
const routeQuery = computed(() => {
  const raw = route.query.q ?? route.params.query
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value.trim() : ''
})

searchQuery.value = routeQuery.value

const hasAnyResults = computed(
  () =>
    musicResults.value.length > 0 ||
    playlistResults.value.length > 0 ||
    !!(artistPayload.value.name && String(artistPayload.value.name).trim())
)

const artistTracks = computed(() => {
  const raw = artistPayload.value.musicList
  return Array.isArray(raw) ? raw : []
})

const artistTrackCount = computed(() => {
  const n = artistPayload.value.musicCount
  if (typeof n === 'number' && n >= 0) return n
  return artistTracks.value.length
})

const tabs = computed(() => [
  { key: 'tracks', label: t('search.tabTracks'), icon: 'music', count: musicResults.value.length },
  { key: 'playlists', label: t('search.tabPlaylists'), icon: 'list-music', count: playlistResults.value.length },
  { key: 'artist', label: t('search.tabArtist'), icon: 'mic-2', count: artistPayload.value.name ? artistTrackCount.value : 0 },
])

/** 有结果但当前标签为空时的引导：切到有内容的标签 */
watch(
  () => [searchLoading.value, hasAnyResults.value, tabs.value.map((item) => item.count).join(',')],
  () => {
    if (searchLoading.value || !hasAnyResults.value) return
    const current = tabs.value.find((t) => t.key === tab.value)
    if (current && current.count === 0) {
      const firstNonEmpty = tabs.value.find((t) => t.count > 0)
      if (firstNonEmpty) tab.value = firstNonEmpty.key
    }
  },
  { immediate: true }
)

const jsonHeaders = { 'Content-Type': 'application/json' }
const authHeaders = () => {
  const token = localStorage.getItem('userToken')
  return token ? { Authorization: token } : {}
}

async function fetchMusicResults(query) {
  const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
    method: 'POST',
    headers: { ...jsonHeaders, ...authHeaders() },
    body: JSON.stringify({ query }),
  })
  const data = await response.json()
  if (response.ok && data.success && Array.isArray(data.results)) return data.results
  if (response.ok && Array.isArray(data.results)) return data.results
  return []
}

async function fetchPlaylistResults(query) {
  const response = await fetch(`${API_CONFIG.BASE_URL}/api/playlists/search`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({ query }),
  })
  const data = await response.json()
  if (response.ok && data.success && Array.isArray(data.results)) return data.results
  return []
}

async function fetchArtistPayload(query) {
  const response = await fetch(`${API_CONFIG.BASE_URL}/api/artists/search`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify({ query }),
  })
  const data = await response.json()
  if (!response.ok || !data.success || !data.artist) {
    return { name: '', musicCount: 0, musicList: [] }
  }
  const a = data.artist
  const name = typeof a.name === 'string' ? a.name.trim() : ''
  const musicList = Array.isArray(a.musicList) ? a.musicList : []
  const musicCount = typeof a.musicCount === 'number' ? a.musicCount : musicList.length
  return { name, musicCount, musicList }
}

async function runSearch(query) {
  const q = (query || '').trim()
  if (!q) return
  searchLoading.value = true
  try {
    const [tracks, playlists, artist] = await Promise.all([
      fetchMusicResults(q),
      fetchPlaylistResults(q),
      fetchArtistPayload(q),
    ])
    musicResults.value = tracks
    playlistResults.value = playlists
    artistPayload.value = artist
  } catch (e) {
    console.error('搜索失败:', e)
    toast.error(t('search.failed'))
    musicResults.value = []
    playlistResults.value = []
    artistPayload.value = { name: '', musicCount: 0, musicList: [] }
  } finally {
    searchLoading.value = false
  }
}

const goPlaylist = (pl) => router.push(`/playlist/${pl.id}`)

/** 艺人搜索返回的曲目字段与单曲接口略有差异，统一成播放器/收藏可用的形状 */
function normalizeTrack(m) {
  const fileFormat = m.fileFormat ?? m.file_format ?? 'mp3'
  return {
    id: m.id,
    title: m.title,
    artist: m.artist,
    album: m.album ?? '',
    duration: m.duration ?? 0,
    fileFormat,
    filename: m.filename ?? `${m.title}.${fileFormat}`,
  }
}

const playlistCoverUrl = (pl) => {
  const u = pl.firstMusicCover
  if (!u) return `${API_CONFIG.BASE_URL}/api/music/cover/0`
  if (typeof u === 'string' && u.startsWith('http')) return u
  const path = typeof u === 'string' && u.startsWith('/') ? u : `/${u}`
  return `${API_CONFIG.BASE_URL}${path}`
}

const trackCoverUrl = (m) => {
  const u = m.coverPath
  if (u && typeof u === 'string') {
    if (u.startsWith('http')) return u
    const path = u.startsWith('/') ? u : `/${u}`
    return `${API_CONFIG.BASE_URL}${path}`
  }
  return getCoverUrl(m.id)
}

/** 播放器只认识这些字段，顺便把搜索结果/艺人结果的差异抹平 */
const toTrack = (m) => ({
  id: m.id,
  title: m.title,
  artist: m.artist,
  album: m.album ?? '',
  duration: m.duration ?? 0,
})

/**
 * 从「当前这份列表」起播。
 * ------------------------------------------------------------
 * 关键：把整份列表作为播放队列，并从被点的位置开始。这样：
 *  - 点歌曲就是「播放」而不是跳转（不再自动打开播放页）；
 *  - 上一首 / 下一首 能在搜索结果里正常前后切换。
 * 之前只把单曲塞进队列并跳播放页，导致页面看着在播、实际没起播，自然「无法下一首」。
 */
function playFromResult(result, list) {
  const tracks = Array.isArray(list) ? list : []
  if (!tracks.length) {
    playTrack(toTrack(result))
    return
  }
  const index = tracks.findIndex((item) => String(item?.id) === String(result?.id))
  playTracks(tracks.map(toTrack), index >= 0 ? index : 0)
}

function mapContentTypeToExtension(contentType) {
  const type = contentType.toLowerCase()
  if (type.includes('flac')) return 'flac'
  if (type.includes('wav')) return 'wav'
  if (type.includes('ogg')) return 'ogg'
  if (type.includes('aac')) return 'aac'
  if (type.includes('m4a') || type.includes('mp4')) return 'm4a'
  if (type.includes('wma')) return 'wma'
  if (type.includes('ape')) return 'ape'
  if (type.includes('mpeg') || type.includes('mp3')) return 'mp3'
  return 'mp3'
}

async function downloadMusic(result) {
  try {
    const mediaUrl = await resolveMediaUrl(result.id)
    const response = await fetch(mediaUrl)
    const blob = await response.blob()
    const contentType = response.headers.get('Content-Type') || 'audio/mpeg'
    const extension = mapContentTypeToExtension(contentType)

    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename || `${result.title}.${extension}`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
  } catch (error) {
    console.error('下载音乐失败:', error)
    toast.error(t('track.downloadFailed'))
  }
}

const getToken = () => localStorage.getItem('userToken')
const isLoggedIn = () => !!getToken()
const isFavorite = (musicId) => favoriteMusicIds.value.has(musicId)

async function toggleFavorite(result) {
  if (!isLoggedIn()) {
    toast.error(t('common.needLogin'))
    return
  }
  const token = getToken()

  if (isFavorite(result.id)) {
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites/${result.id}`, {
        method: 'DELETE',
        headers: { Authorization: token },
      })
      const data = await response.json()
      if (data.success) {
        favoriteMusicIds.value.delete(result.id)
        toast.success(t('player.unfavoriteSuccess'))
      } else {
        toast.error(t('player.unfavoriteFailed') + ': ' + data.message)
      }
    } catch (error) {
      console.error('取消收藏失败:', error)
      toast.error(t('player.unfavoriteFailed'))
    }
  } else {
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
        method: 'POST',
        headers: { Authorization: token, 'Content-Type': 'application/json' },
        body: JSON.stringify({ musicId: result.id }),
      })
      const data = await response.json()
      if (data.success) {
        favoriteMusicIds.value.add(result.id)
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

async function fetchFavorites() {
  if (!isLoggedIn()) return
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
      method: 'GET',
      headers: { Authorization: getToken() },
    })
    const data = await response.json()
    if (data.success) {
      favoriteMusicIds.value = new Set(data.favorites.map((m) => m.id))
    }
  } catch (error) {
    console.error('获取收藏列表失败:', error)
  }
}

const getCoverUrl = (musicId) => `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`

function handleImageError(event) {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/0`
}

watch(routeQuery, (query) => {
  if (query) {
    searchQuery.value = query
    runSearch(query)
  } else {
    searchQuery.value = ''
    searchLoading.value = false
    musicResults.value = []
    playlistResults.value = []
    artistPayload.value = { name: '', musicCount: 0, musicList: [] }
  }
})

onMounted(async () => {
  if (searchQuery.value && searchQuery.value !== 'undefined') {
    await runSearch(searchQuery.value)
  }
  await fetchFavorites()
})
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="default">
    <!-- 页头 -->
    <header class="head">
      <h1 class="head__title">{{ searchQuery || $t('search.title') }}</h1>
      <p class="head__sub">
        <template v-if="searchQuery">{{ $t('search.keywordLabel', { q: searchQuery }) }}</template>
        <template v-else>{{ $t('search.hint') }}</template>
      </p>
    </header>

    <!-- 加载 -->
    <div v-if="searchQuery && searchLoading" class="state">
      <NSpinner :size="28" />
      <p>{{ $t('search.searching') }}</p>
    </div>

    <!-- 未输入关键词 -->
    <NCard v-else-if="!searchQuery" pad="lg" class="empty">
      <span class="empty__icon"><NIcon name="search" :size="28" /></span>
      <h2 class="empty__title">{{ $t('search.emptyTitle') }}</h2>
      <p class="empty__text">{{ $t('search.emptyText') }}</p>
    </NCard>

    <!-- 无结果 -->
    <NCard v-else-if="!hasAnyResults" pad="lg" class="empty">
      <span class="empty__icon"><NIcon name="search-x" :size="28" /></span>
      <h2 class="empty__title">{{ $t('search.notFoundTitle') }}</h2>
      <p class="empty__text">{{ $t('search.notFoundText', { q: searchQuery }) }}</p>
      <p class="empty__hint">
        {{ $t('search.contactLead') }}
        <a href="mailto:support@nekocore.cn">support@nekocore.cn</a>
      </p>
    </NCard>

    <!-- 结果 -->
    <template v-else>
      <div class="tabs" role="tablist" :aria-label="$t('search.tabsAria')">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          role="tab"
          class="tabs__btn"
          :class="{ 'tabs__btn--active': tab === t.key }"
          :aria-selected="tab === t.key"
          @click="tab = t.key"
        >
          <NIcon :name="t.icon" :size="16" />
          <span>{{ t.label }}</span>
          <span class="tabs__count">{{ t.count }}</span>
        </button>
      </div>

      <!-- 单曲 -->
      <div v-if="tab === 'tracks'" class="list">
        <p v-if="!musicResults.length" class="list__empty">{{ $t('search.noTracks') }}</p>
        <article v-for="result in musicResults" v-else :key="'m-' + result.id" class="row">
          <img
            :src="getCoverUrl(result.id)"
            :alt="result.title"
            class="row__cover"
            loading="lazy"
            decoding="async"
            @error="handleImageError"
          />
          <button
            type="button"
            class="row__info"
            :aria-label="$t('home.playAria', { title: result.title })"
            @click="playFromResult(result, musicResults)"
          >
            <span class="row__title">
              <span class="row__title-text">{{ result.title }}</span>
              <NowPlayingTag v-if="isCurrentMusic(result.id)" />
              <QualityBadge :quality="result.maxQuality" />
              <NIcon v-if="result.lrc" name="file-text" :size="13" class="row__lyric" />
            </span>
            <span class="row__meta">{{ result.artist }}</span>
            <span class="row__sub">{{ result.album || $t('common.unknownAlbum') }}</span>
          </button>
          <div class="row__actions">
            <NButton
              size="sm"
              :variant="isCurrentMusic(result.id) ? 'primary' : 'secondary'"
              :icon="isCurrentMusic(result.id) ? 'volume-2' : 'play'"
              :title="isCurrentMusic(result.id) ? $t('nowPlaying.label') : $t('track.play')"
              @click="playFromResult(result, musicResults)"
            />
            <NButton
              size="sm"
              :variant="isFavorite(result.id) ? 'primary' : 'secondary'"
              icon="heart"
              :title="isFavorite(result.id) ? $t('track.unfavorite') : $t('track.favorite')"
              @click="toggleFavorite(result)"
            />
            <NButton size="sm" variant="secondary" icon="download" :title="$t('track.download')" @click="downloadMusic(result)" />
          </div>
        </article>
      </div>

      <!-- 歌单 -->
      <div v-else-if="tab === 'playlists'" class="list">
        <p v-if="!playlistResults.length" class="list__empty">{{ $t('search.noPlaylists') }}</p>
        <article v-for="pl in playlistResults" v-else :key="'p-' + pl.id" class="row">
          <img
            :src="playlistCoverUrl(pl)"
            :alt="pl.name"
            class="row__cover"
            loading="lazy"
            decoding="async"
            @error="handleImageError"
          />
          <button type="button" class="row__info" @click="goPlaylist(pl)">
            <span class="row__title">
              <span class="row__title-text">{{ pl.name }}</span>
            </span>
            <span class="row__meta">{{ $t('track.songCount', { count: pl.musicCount ?? 0 }) }}</span>
            <span class="row__sub">{{ pl.description || $t('track.noDescription') }}</span>
          </button>
          <div class="row__actions">
            <NButton size="sm" variant="secondary" icon="arrow-right" @click="goPlaylist(pl)">{{ $t('track.open') }}</NButton>
          </div>
        </article>
      </div>

      <!-- 艺人 -->
      <div v-else class="artist">
        <template v-if="artistPayload.name">
          <header class="artist__head">
            <span class="artist__avatar"><NIcon name="mic-2" :size="22" /></span>
            <div>
              <h2 class="artist__name">{{ artistPayload.name }}</h2>
              <p class="artist__meta">{{ $t('track.artistWorks', { count: artistTrackCount }) }}</p>
            </div>
          </header>

          <div class="list">
            <p v-if="!artistTracks.length" class="list__empty">{{ $t('search.noArtistTracks') }}</p>
            <article v-for="result in artistTracks" v-else :key="'a-' + result.id" class="row">
              <img
                :src="trackCoverUrl(result)"
                :alt="result.title"
                class="row__cover"
                loading="lazy"
                decoding="async"
                @error="handleImageError"
              />
              <button
                type="button"
                class="row__info"
                :aria-label="$t('home.playAria', { title: result.title })"
                @click="playFromResult(result, artistTracks)"
              >
                <span class="row__title">
                  <span class="row__title-text">{{ result.title }}</span>
                  <QualityBadge :quality="result.maxQuality" />
                </span>
                <span class="row__meta">{{ result.artist }}</span>
                <span class="row__sub">{{ result.album || $t('common.unknownAlbum') }}</span>
              </button>
              <div class="row__actions">
                <NButton
                  size="sm"
                  variant="secondary"
                  icon="play"
                  :title="$t('track.play')"
                  @click="playFromResult(result, artistTracks)"
                />
                <NButton
                  size="sm"
                  :variant="isFavorite(result.id) ? 'primary' : 'secondary'"
                  icon="heart"
                  :title="isFavorite(result.id) ? $t('track.unfavorite') : $t('track.favorite')"
                  @click="toggleFavorite(normalizeTrack(result))"
                />
                <NButton size="sm" variant="secondary" icon="download" :title="$t('track.download')" @click="downloadMusic(normalizeTrack(result))" />
              </div>
            </article>
          </div>
        </template>

        <p v-else class="list__empty">{{ $t('search.noArtists') }}</p>
      </div>
    </template>
  </PageShell>
</template>

<style scoped>
.head {
  margin-bottom: var(--n-space-6);
}

.head__title {
  margin: 0 0 var(--n-space-1);
  font-size: clamp(1.5rem, 3vw, 1.9rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
  overflow-wrap: anywhere;
}

.head__sub {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ===== 状态 ===== */
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-4);
  padding: var(--n-space-16) 0;
  color: var(--n-text-muted);
}

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
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.empty__hint {
  margin: 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

/* ===== 标签页 ===== */
.tabs {
  display: flex;
  gap: var(--n-space-1);
  padding: 4px;
  margin-bottom: var(--n-space-5);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  overflow-x: auto;
}

/* 手机竖屏：改为均分收缩，避免出现「不易发现的横向滚动」 */
@media (max-width: 560px) {
  .tabs {
    overflow-x: visible;
  }

  .tabs__btn {
    flex: 1 1 0;
    min-width: 0;
    justify-content: center;
    padding-inline: var(--n-space-2);
  }

  .tabs__count {
    display: none;
  }
}

.tabs__btn {
  display: inline-flex;
  align-items: center;
  gap: var(--n-space-2);
  flex: 1 1 auto;
  justify-content: center;
  padding: 9px var(--n-space-4);
  border-radius: var(--n-radius-xs);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  white-space: nowrap;
  transition: background var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .tabs__btn:hover {
    color: var(--n-text);
  }
}

.tabs__btn--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.tabs__count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--n-radius-xs);
  background: rgba(120, 220, 232, 0.12);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.tabs__btn--active .tabs__count {
  background: rgba(95, 208, 224, 0.22);
}

/* ===== 列表 ===== */
.list {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-2);
}

.list__empty {
  margin: 0;
  padding: var(--n-space-10) 0;
  text-align: center;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  padding: var(--n-space-3) var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface);
  transition: border-color var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .row:hover {
    border-color: var(--n-line-strong);
    background: var(--n-surface-hover);
  }
}

.row__cover {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: var(--n-radius-sm);
  border: 1px solid var(--n-line-subtle);
  flex: none;
}

.row__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
  text-align: left;
}

.row__title {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
  min-width: 0;
  color: var(--n-text);
  font-size: var(--n-text-base);
  font-weight: var(--n-weight-semibold);
}

.row__title-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row__lyric {
  flex: none;
  color: var(--n-accent);
}

.row__meta {
  color: var(--n-accent-strong);
  font-size: var(--n-text-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row__sub {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row__actions {
  display: flex;
  gap: var(--n-space-2);
  flex: none;
}

/* ===== 艺人 ===== */
.artist__head {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
}

.artist__avatar {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: var(--n-radius);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  flex: none;
}

.artist__name {
  margin: 0;
  font-size: var(--n-text-xl);
  font-weight: var(--n-weight-semibold);
  letter-spacing: -0.02em;
}

.artist__meta {
  margin: 2px 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

@media (max-width: 560px) {
  .row {
    flex-wrap: wrap;
  }

  .row__actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>

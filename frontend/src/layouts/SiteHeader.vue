<script setup>
/**
 * SiteHeader —— 全站顶栏（由旧 SearchHeader 改造）
 * ------------------------------------------------------------
 * 组成：Logo + 搜索（防抖下拉）+ 用户区。
 * 设计：黑偏青 + 圆角矩形；不使用高亮条。
 * 行为契约（保持与旧实现一致，勿随意改动）：
 *  - localStorage：userToken（用户资料只在内存中，不落盘）
 *  - window 事件：storage、USER_VIP_SYNC_EVENT
 *  - 选中搜索结果：直接播放（经 usePlaybackBridge），不再自动跳播放页
 *  - 回车：跳 /search?q=...（查询词走查询串，避免 / 被编码成 %2F 被 Jetty 400）
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import NIcon from '@/icons/NIcon.vue'
import { NInput, NButton } from '@/ui'
import PwaInstallButton from '@/components/PwaInstallButton.vue'
import LocaleSwitcher from '@/components/LocaleSwitcher.vue'
import { playTrack, playTracks, isCurrentMusic } from '@/composables/usePlaybackBridge'
import { logoutUserGlobally } from '@/api/session.js'
import { readAuthSessionHint, rememberSilentAttempt } from '@/utils/nekoAuthSilent.js'
import NowPlayingTag from '@/components/NowPlayingTag.vue'
import { syncUserVipFromPlaylistsApi, USER_VIP_SYNC_EVENT } from '@/utils/userVip.js'
import { avatarUrl, useAvatarVersion } from '@/utils/userAvatar.js'
import { getUser, clearUser, loadUserInfo } from '@/utils/userStore.js'
import {
  NOTIFICATION_SYNC_EVENT,
  reconnectNotificationStream,
  subscribeNotifications,
  syncNotificationStream,
} from '@/api/notifications.js'

const router = useRouter()

const searchQuery = ref('')
const searchResults = ref(null)
const showResults = ref(false)
const searchRoot = ref(null)
let debounceTimer = null
/**
 * 请求序号：搜索请求是并发的，先发的可能后到。只采纳“最后一次发起”
 * 的结果，避免旧响应把用户刚输入的新结果覆盖掉。
 */
let searchSeq = 0

const isLoggedIn = ref(false)
const user = ref(null)
const nickname = ref('')

function initializeUserState() {
  const token = localStorage.getItem('userToken')
  isLoggedIn.value = token !== null && token !== undefined

  // 用户资料只放在内存里（不落盘），由 main.js 启动时用 /api/user/info 拉取
  const cached = getUser()
  user.value = cached
  nickname.value = cached?.nickname || ''
}

function handleStorageChange(event) {
  if (event.key === 'userToken') {
    // 登录 / 登出 / 换账号：资料只在内存里，Token 变了就重新拉一次
    initializeUserState()
    loadUserInfo({ force: true })
    // 连接跟着身份走：登出即断开，换账号重连（未登录时下面的回调不会触发）
    syncNotificationStream()
    if (!isLoggedIn.value) unreadCount.value = 0
    return
  }
  if (event.key === 'user') initializeUserState()
}

// ── 站内消息红点 ──────────────────────────────────────────────
// 未读数全部来自 SSE：连上的 `ready` 帧给一次准确值，之后每来一条新消息自增；
// 消息中心标记已读时广播事件，这里直接采用。没有任何轮询请求。
const unreadCount = ref(0)
let notificationSubscription = null

function applyUnread(count) {
  const value = Number(count)
  if (Number.isFinite(value) && value >= 0) unreadCount.value = value
}

function handleNotificationReady(data) {
  applyUnread(data.unread)
}

function handleNotificationMessage(item) {
  if (!item.read) unreadCount.value += 1
}

function handleNotificationSync(event) {
  applyUnread(event?.detail?.unread)
}

function handleVisibilityChange() {
  // 切回前台时重建连接，顺便用新的 `ready` 帧校准红点，避免长时间挂后台后计数漂移
  if (!document.hidden) {
    reconnectNotificationStream()
    // 用户可能在 NekoAuth 页面改了昵称；回到站内时从 Auth 拉取最新展示资料。
    loadUserInfo({ force: true })
  }
}

const avatarVersion = useAvatarVersion()

const userAvatar = computed(() => {
  const userId = user.value ? user.value.id : 'default'
  return avatarUrl(userId, avatarVersion.value)
})

const getCoverUrl = (musicId) => `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`

function handleAvatarError(event) {
  event.target.src =
    'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40"><rect width="40" height="40" fill="%235fd0e0"/><text x="20" y="25" font-family="Arial" font-size="16" fill="%2304191d" text-anchor="middle">N</text></svg>'
}

function handleImageError(event) {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`
}

async function fetchSearchResults(query) {
  const seq = ++searchSeq
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query }),
    })
    const data = await response.json()
    // 期间又发起了新的搜索：丢弃本次结果
    if (seq !== searchSeq) return
    if (response.ok && data.success && data.results) {
      searchResults.value = data.results
      showResults.value = true
    } else {
      searchResults.value = null
      showResults.value = false
    }
  } catch (error) {
    if (seq !== searchSeq) return
    console.error('搜索请求失败:', error)
    searchResults.value = null
    showResults.value = false
  }
}

function onSearchInput() {
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!searchQuery.value.trim()) {
    // 清空输入：让在途请求的结果失效，并收起下拉
    searchSeq++
    searchResults.value = null
    showResults.value = false
    return
  }
  debounceTimer = setTimeout(() => fetchSearchResults(searchQuery.value), 500)
}

async function performSearch() {
  if (!searchQuery.value.trim()) return
  const query = searchQuery.value
  if (debounceTimer) clearTimeout(debounceTimer)
  // 别再让在途的下拉搜索把面板重新弹出来
  searchSeq++
  searchResults.value = null
  showResults.value = false
  // 查询串形式：查询词里的 / 编码成 %2F 在 path 里会被 Jetty 拒绝
  router.push({ name: 'search', query: { q: query } })
}

function selectResult(result) {
  // 只播放，不自动打开播放页：选中搜索结果的预期是「听这首」，
  // 而不是被强制切走当前页面。需要看详情时点播放条封面即可。
  // 把下拉里的整份结果作为播放队列，从被点的位置开始，
  // 这样「下一首 / 上一首」能在这批搜索结果里正常切换。
  const list = Array.isArray(searchResults.value) ? searchResults.value : []
  const index = list.findIndex((item) => String(item?.id) === String(result?.id))
  if (list.length) playTracks(list, index >= 0 ? index : 0)
  else playTrack(result)
  searchSeq++
  searchResults.value = null
  showResults.value = false
}

function goHome() {
  router.push('/')
  searchSeq++
  searchQuery.value = ''
  searchResults.value = null
  showResults.value = false
}

function goToLogin() {
  openAuthDialog('login')
}

async function logout() {
  const previousToken = localStorage.getItem('userToken')
  // 显式退出后不要被同一次 Auth 会话的静默登录立刻拉回；重新登录 Auth 会产生新的提示值。
  rememberSilentAttempt(readAuthSessionHint())
  localStorage.removeItem('userToken')
  clearUser()
  if (previousToken) {
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'userToken', oldValue: previousToken, newValue: null })
    )
  }
  // 全局退出会跳转 NekoAuth 注销主会话；配置不可用时退回仅吊销当前 access token。
  const redirected = await logoutUserGlobally(previousToken)
  if (!redirected) router.push('/')
}

function handleClickOutside(event) {
  if (searchRoot.value && !searchRoot.value.contains(event.target)) {
    showResults.value = false
  }
}

onMounted(async () => {
  initializeUserState()
  window.addEventListener('storage', handleStorageChange)
  window.addEventListener(USER_VIP_SYNC_EVENT, initializeUserState)
  document.addEventListener('click', handleClickOutside)
  await syncUserVipFromPlaylistsApi()
  initializeUserState()
  notificationSubscription = subscribeNotifications({
    onReady: handleNotificationReady,
    onMessage: handleNotificationMessage,
  })
  window.addEventListener(NOTIFICATION_SYNC_EVENT, handleNotificationSync)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
  window.removeEventListener('storage', handleStorageChange)
  window.removeEventListener(USER_VIP_SYNC_EVENT, initializeUserState)
  window.removeEventListener(NOTIFICATION_SYNC_EVENT, handleNotificationSync)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  document.removeEventListener('click', handleClickOutside)
  if (notificationSubscription) notificationSubscription.close()
})
</script>

<template>
  <header class="site-header">
    <div class="site-header__inner">
      <!-- Logo -->
      <a class="site-header__logo" href="/" @click.prevent="goHome">{{ $t('common.appName') }}</a>

      <!-- 搜索 -->
      <div ref="searchRoot" class="site-header__search">
        <NInput
          v-model="searchQuery"
          icon="search"
          :placeholder="$t('nav.searchPlaceholder')"
          clearable
          @update:model-value="onSearchInput"
          @enter="performSearch"
        />

        <div v-if="showResults && searchResults && searchResults.length" class="search-panel">
          <button
            v-for="result in searchResults"
            :key="result.id"
            type="button"
            class="search-panel__item"
            @click="selectResult(result)"
          >
            <img
              :src="getCoverUrl(result.id)"
              :alt="result.title"
              class="search-panel__cover"
              loading="lazy"
              @error="handleImageError"
            />
            <span class="search-panel__info">
              <span class="search-panel__title">
                <span class="search-panel__title-text">{{ result.title }}</span>
                <NowPlayingTag v-if="isCurrentMusic(result.id)" />
                <NIcon v-if="result.lrc" name="file-text" :size="13" class="search-panel__lyric" />
              </span>
              <span class="search-panel__artist">{{ $t('nav.searchComposer', { artist: result.artist }) }}</span>
              <span class="search-panel__album">{{ $t('nav.searchAlbum', { album: result.album || $t('common.unknownAlbum') }) }}</span>
            </span>
          </button>
        </div>
      </div>

      <!-- 用户区 -->
      <div class="site-header__auth">
        <LocaleSwitcher />
        <NButton
          class="site-header__download"
          variant="secondary"
          size="sm"
          icon="download"
          to="/download"
          :title="$t('nav.downloadClient')"
        >
          {{ $t('nav.downloadClient') }}
        </NButton>
        <!-- 可安装时出现；已安装/不支持时组件自身不渲染 -->
        <PwaInstallButton />
        <template v-if="isLoggedIn">
          <RouterLink to="/account" class="site-header__user" :title="$t('nav.account')">
            <img :src="userAvatar" :alt="$t('nav.avatarAlt')" class="site-header__avatar" @error="handleAvatarError" />
            <span class="site-header__nickname">{{ nickname }}</span>
          </RouterLink>

          <RouterLink
            to="/vip"
            class="site-header__vip"
            :class="{ 'site-header__vip--active': user?.isVip }"
            :title="user?.isVip ? $t('nav.vipActive') : $t('nav.vipInactive')"
          >
            VIP
          </RouterLink>

          <RouterLink
            to="/notifications"
            class="site-header__notice"
            :title="unreadCount > 0 ? $t('nav.messagesUnread', { count: unreadCount }) : $t('nav.messages')"
          >
            <NIcon name="bell" :size="18" />
            <span v-if="unreadCount > 0" class="site-header__notice-badge">
              {{ unreadCount > 99 ? '99+' : unreadCount }}
            </span>
          </RouterLink>

          <NButton size="sm" variant="ghost" icon="list-music" to="/playlists" :title="$t('nav.myPlaylists')" />
          <NButton size="sm" variant="ghost" icon="heart" to="/favorites" :title="$t('nav.myFavorites')" />
          <NButton size="sm" variant="danger" icon="logout" @click="logout">{{ $t('nav.logout') }}</NButton>
        </template>

        <NButton v-else variant="primary" icon="login" @click="goToLogin">{{ $t('nav.login') }}</NButton>
      </div>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  position: sticky;
  top: 0;
  z-index: var(--n-z-header);
  /* 刘海屏：内容不下沉到状态栏下面 */
  padding-top: var(--n-safe-top);
  border-bottom: 1px solid var(--n-line);
  background: rgba(4, 9, 11, 0.82);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
}

.site-header__inner {
  width: min(1400px, calc(100% - 2 * var(--n-content-gutter)));
  margin: 0 auto;
  min-height: var(--n-header-height);
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--n-space-5);
  padding: var(--n-space-2) 0;
}

/* ===== Logo ===== */
.site-header__logo {
  font-size: clamp(1.02rem, 1.7vw, 1.25rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
  color: var(--n-text);
  white-space: nowrap;
}
@media (hover: hover) {
  .site-header__logo:hover {
    color: var(--n-accent-strong);
  }
}

/* ===== 站内消息红点 ===== */
.site-header__notice {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--n-radius-sm);
  color: var(--n-text-muted);
}
@media (hover: hover) {
  .site-header__notice:hover {
    color: var(--n-text);
    background: var(--n-surface-hover);
  }
}
.site-header__notice-badge {
  position: absolute;
  top: 2px;
  right: 0;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--n-radius-pill);
  background: var(--n-danger);
  color: var(--n-text-inverse);
  font-size: 0.6875rem;
  font-weight: var(--n-weight-bold);
  line-height: 16px;
  text-align: center;
}

/* ===== 搜索 ===== */
.site-header__search {
  position: relative;
  min-width: 0;
}

.search-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: var(--n-z-overlay);
  max-height: 340px;
  overflow-y: auto;
  padding: var(--n-space-1);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius);
  background: var(--n-surface-strong);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
  box-shadow: var(--n-shadow-lg);
}

.search-panel__item {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  width: 100%;
  padding: var(--n-space-2);
  border-radius: var(--n-radius-sm);
  text-align: left;
  transition: background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .search-panel__item:hover {
    background: var(--n-surface-hover);
  }
}

.search-panel__cover {
  width: 42px;
  height: 42px;
  border-radius: var(--n-radius-xs);
  object-fit: cover;
  flex: none;
  border: 1px solid var(--n-line-subtle);
}

.search-panel__info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.search-panel__title {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
  min-width: 0;
  color: var(--n-text);
  font-size: var(--n-text-base);
  font-weight: var(--n-weight-semibold);
}

.search-panel__title-text,
.search-panel__artist,
.search-panel__album {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-panel__lyric {
  flex: none;
  color: var(--n-accent);
}

.search-panel__artist {
  color: var(--n-accent-strong);
  font-size: var(--n-text-sm);
}

.search-panel__album {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

/* ===== 用户区 ===== */
.site-header__auth {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  justify-content: flex-end;
}

.site-header__user {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  min-width: 0;
  padding: 4px 10px 4px 4px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  transition: border-color var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}
@media (hover: hover) {
  .site-header__user:hover {
    border-color: var(--n-line-strong);
    background: var(--n-surface-hover);
  }
}

.site-header__avatar {
  width: 28px;
  height: 28px;
  border-radius: var(--n-radius-xs);
  object-fit: cover;
  flex: none;
}

.site-header__nickname {
  max-width: 110px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

.site-header__vip {
  flex: none;
  padding: 5px 10px;
  border-radius: var(--n-radius-xs);
  font-size: 0.66rem;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.1em;
  color: var(--n-text-muted);
  background: var(--n-surface-soft);
  border: 1px solid var(--n-line);
}

.site-header__vip--active {
  color: var(--n-text-inverse);
  background: linear-gradient(135deg, var(--n-accent-strong), var(--n-accent));
  border-color: transparent;
}

/* ===== 响应式 =====
   断点约定见 design/tokens.css：560 手机竖屏 / 900 平板 / 1200 桌面 */
@media (max-width: 900px) {
  .site-header__inner {
    /* 两行：上行「Logo + 用户区」，下行整宽搜索框。
       三行堆叠会吃掉手机上宝贵的首屏高度。 */
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'logo auth'
      'search search';
    gap: var(--n-space-2) var(--n-space-3);
    padding: var(--n-space-2) 0 var(--n-space-3);
  }

  .site-header__logo {
    grid-area: logo;
  }

  .site-header__search {
    grid-area: search;
  }

  .site-header__auth {
    grid-area: auth;
    gap: var(--n-space-1);
  }
}

@media (max-width: 560px) {
  .site-header__logo {
    font-size: 1rem;
  }

  /* 下载入口在窄屏只留图标，给用户区腾地方 */
  .site-header__download :deep(.n-btn__label) {
    display: none;
  }

  /* 用户名与 VIP 入口收进个人中心：一行放不下 5 个元素，
     且这两项在 /account 里都能看到 */
  .site-header__nickname,
  .site-header__vip {
    display: none;
  }

  /* 触摸目标抬到 32px（视觉仍是 28px 的圆角方块观感） */
  .site-header__user {
    padding: 2px;
  }
}
</style>

<script setup>
/**
 * App —— 应用外壳
 * ------------------------------------------------------------
 * 编排：顶栏（顶） + 路由内容 + 底栏（底） + 全局播放条（底，停靠式）。
 *
 * 播放条策略（决策见 docs/UI重构开发文档.md §5.4）：
 *  - 停靠底部，不做悬浮；
 *  - 无当前曲目时视觉隐藏（下移 + 淡出），不占底部空间；
 *  - 有曲目时滑入；因 GlobalPlayer 挂着 hash / 事件 / MediaSession 监听，
 *    只能视觉隐藏，不能 v-if 卸载。
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import SiteHeader from './layouts/SiteHeader.vue'
import SiteFooter from './layouts/SiteFooter.vue'
import GlobalPlayer from './components/GlobalPlayer.vue'
import MobileAppBanner from './components/MobileAppBanner.vue'
import AuthDialog from './components/AuthDialog.vue'
import PwaUpdateBanner from './components/PwaUpdateBanner.vue'
import { isMobileDevice } from './utils/mobile.js'
import { usePlaybackBridge } from './composables/usePlaybackBridge'

const route = useRoute()

/**
 * 全局挂载播放状态桥：让任意页面都能读到统一的「正在播放」快照
 * （列表高亮、播放条等共用同一数据源），而不再只在播放页才接线。
 */
const { sendPlayerCommand } = usePlaybackBridge()

/** 下载页独立布局：不显示顶栏、底栏与全局播放器 */
const isDownloadPage = computed(() => route.name === 'download')

/**
 * 全屏播放页（/detail/:id）自带整屏 UI 与播放控制，
 * 此时底部停靠播放条必须让位——两者层级相同，否则会把全屏页压在下面。
 */
const isPlayerPage = computed(() => route.name === 'detail')
const isLyricsToolPage = computed(() =>
  route.name === 'lyrics-report' || route.name === 'tools-lyr'
)

/** 内容区全幅（去掉 main 内边距，由页面自行控制） */
const isFlushMain = computed(() => !String(route.name || '').startsWith('admin'))

/** 管理后台 */
const isAdminPage = computed(() => String(route.name || '').startsWith('admin'))

/**
 * 手机端「下载 APP」软引导横幅。
 * 原先手机访问首页/搜索/收藏等会被路由守卫【硬重定向】到 /download，
 * 整站手机端不可用；改为可关闭的横幅后导流仍在但不阻断浏览。
 *
 * UA 在应用生命周期内不会变，因此在 setup 里【取一次】再进 computed ——
 * 把非响应式的 isMobileDevice() 直接写进 computed 会让人误以为它会跟着变。
 */
const isMobileUA = isMobileDevice()
const showAppBanner = computed(
  () => isMobileUA && !isDownloadPage.value && !isAdminPage.value
)

/** 深色 chrome：管理后台除外 */
const isChromeDarkShell = computed(() => !String(route.name || '').startsWith('admin'))

/**
 * 顶栏是否收起。
 *
 * 下载页与后台都自带布局：下载页是独立落地页，后台有侧栏 + 自己的页头，再把全站顶栏（搜索 /
 * 用户区）压在上面既占高度又和后台导航打架。这里复用下载页的「视觉收起」而不是 v-if 卸载——
 * SiteHeader 挂着搜索防抖、通知流与顶栏高度观测（--app-header-h），卸载会连带把观测目标弄丢。
 */
const isChromeHeaderHidden = computed(() => isDownloadPage.value || isAdminPage.value)

/** 是否存在当前曲目（决定播放条显隐） */
const hasTrack = ref(false)

/** 顶栏高度（用于「收起」时精确收拢布局，避免硬编码） */
const headerRef = ref(null)
const headerHeight = ref(0)
let headerResizeObserver = null

function measureHeader() {
  const el = headerRef.value?.$el
  if (el) headerHeight.value = el.offsetHeight
}

function refreshTrackPresence() {
  const raw = localStorage.getItem('currentPlayingMusic')
  hasTrack.value = !!raw && raw !== 'null' && raw !== 'undefined'
}

/** GlobalPlayer 的状态广播里带 currentMusic，一旦出现即视为有曲目 */
function onPlayerState(event) {
  const music = event?.detail?.currentMusic
  if (music) {
    hasTrack.value = true
    return
  }
  // 广播里明确带了 currentMusic 却为空 → 曲目已被清空（如「清空播放列表」），
  // 重新读一次存储，让播放条收回
  if (event?.detail && 'currentMusic' in event.detail) refreshTrackPresence()
}

onMounted(() => {
  refreshTrackPresence()
  window.addEventListener('storage', refreshTrackPresence)
  window.addEventListener('forcePlay', refreshTrackPresence)
  window.addEventListener('playerStateChange', onPlayerState)

  // 顶栏高度跟随渲染/尺寸变化，保证收起动效收拢准确
  measureHeader()
  const el = headerRef.value?.$el
  if (el && typeof ResizeObserver !== 'undefined') {
    headerResizeObserver = new ResizeObserver(measureHeader)
    headerResizeObserver.observe(el)
  }
  window.addEventListener('resize', measureHeader)
})

watch(
  () => route.name,
  (name) => {
    if (name === 'lyrics-report' || name === 'tools-lyr') sendPlayerCommand('pause')
  },
  { immediate: true },
)

onUnmounted(() => {
  window.removeEventListener('storage', refreshTrackPresence)
  window.removeEventListener('forcePlay', refreshTrackPresence)
  window.removeEventListener('playerStateChange', onPlayerState)
  headerResizeObserver?.disconnect()
  headerResizeObserver = null
  window.removeEventListener('resize', measureHeader)
})
</script>

<template>
  <div
    id="app"
    :class="{ 'app--home': isChromeDarkShell, 'app--player-visible': hasTrack }"
    :style="{ '--app-header-h': headerHeight + 'px' }"
  >
    <AuthDialog />

    <!-- 新版本 Service Worker 就绪时的刷新提示 -->
    <PwaUpdateBanner />

    <SiteHeader
      ref="headerRef"
      class="app-chrome-header"
      :class="{ 'app-chrome-header--hidden': isChromeHeaderHidden }"
      :inert="isChromeHeaderHidden || null"
      :aria-hidden="isChromeHeaderHidden || undefined"
    />

    <MobileAppBanner v-if="showAppBanner" />

    <main :class="{ 'main--flush': isFlushMain }">
      <RouterView />
    </main>

    <div
      class="app-footer"
      :class="{ 'app-footer--raised': hasTrack && !isDownloadPage && !isLyricsToolPage }"
    >
      <SiteFooter v-if="!isDownloadPage" />
    </div>

    <div
      class="app-player"
      :class="{ 'app-player--hidden': !hasTrack || isDownloadPage || isPlayerPage || isLyricsToolPage }"
    >
      <!-- 不能 v-if 卸载：<audio> 是唯一播放源，卸载即打断播放（含下载页） -->
      <GlobalPlayer :chrome-dark="isChromeDarkShell" />
    </div>
  </div>
</template>

<style scoped>
#app {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
}

/* 深色内容页：三层背景（黑偏青，与玻璃页共用） */
#app.app--home {
  background:
    radial-gradient(1200px 700px at 12% -10%, rgba(95, 208, 224, 0.15), transparent 55%),
    radial-gradient(900px 600px at 92% 8%, rgba(96, 140, 150, 0.1), transparent 50%),
    linear-gradient(180deg, #04090b, #071115 42%, #010506 100%);
}

main {
  flex: 1;
  padding: 20px;
  position: relative;
}

/* 页面自行控制内边距（非管理端） */
main.main--flush {
  padding: 0;
}

/* ===== 顶栏：下载页 / 管理后台收起，并同步收拢布局占位 =====
   这两种页面都没有全站顶栏的用武之地；此处保留挂载只做视觉隐藏，以便过渡与高度观测。
   用 --app-header-h（JS 实测高度）精确收拢，避免硬编码与移动端换行偏差。 */
.app-chrome-header {
  transition:
    margin-bottom var(--n-duration) var(--n-ease),
    transform var(--n-duration) var(--n-ease),
    opacity var(--n-duration) var(--n-ease);
}

.app-chrome-header--hidden {
  margin-bottom: calc(-1 * var(--app-header-h, 0px));
  transform: translateY(-100%);
  opacity: 0;
  pointer-events: none;
}

/* ===== 页脚：为播放条预留空间，随播放条显隐收放 ===== */
.app-footer {
  transition: padding-bottom var(--n-duration) var(--n-ease);
}

.app-footer--raised {
  /* 播放条高度 + 手机底部安全区（手势条），避免最后一行被遮住 */
  padding-bottom: calc(var(--n-player-height) + var(--n-safe-bottom));
}

/* ===== 播放条：停靠底部，空闲时下移淡出 ===== */
.app-player :deep(.global-player) {
  transition:
    transform var(--n-duration) var(--n-ease),
    opacity var(--n-duration) var(--n-ease);
}

.app-player--hidden :deep(.global-player) {
  transform: translateY(100%);
  opacity: 0;
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .app-footer,
  .app-chrome-header,
  .app-player :deep(.global-player) {
    transition: none;
  }
}
</style>

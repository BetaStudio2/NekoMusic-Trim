<template>
  <section class="access">
    <header class="access__head">
      <h2 class="access__title">访问日志（安全分析）</h2>
      <p class="access__desc">
        每个请求一条：访问量、来源 IP 与网段、User-Agent、已登录用户、状态码、耗时与本次的
        威胁判定，并自动标记「正常 / 疑似攻击 / 攻击」（综合高危载荷、短时失败行为与跨网段扫描簇）。
        数据落在独立日志库，不参与业务库备份。
      </p>
    </header>

    <p v-if="error" class="err">
      <NIcon name="triangle-alert" :size="16" />
      {{ error }}
    </p>

    <div class="stats">
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.todayRequests ?? 0 }}</span>
        <span class="stat__label">今日访问</span>
      </NCard>
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.todayUniqueIps ?? 0 }}</span>
        <span class="stat__label">今日独立 IP</span>
      </NCard>
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.todayLoggedUsers ?? 0 }}</span>
        <span class="stat__label">今日登录用户</span>
      </NCard>
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.todaySeo ?? 0 }}</span>
        <span class="stat__label">今日 SEO 渲染</span>
      </NCard>
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.todayErrors ?? 0 }}</span>
        <span class="stat__label">今日异常（≥400）</span>
      </NCard>
      <NCard pad="lg" class="stat stat--danger">
        <span class="stat__number">{{ stats.todayAttacks ?? 0 }}</span>
        <span class="stat__label">今日判定攻击</span>
      </NCard>
      <NCard pad="lg" class="stat stat--warning">
        <span class="stat__number">{{ stats.todaySuspected ?? 0 }}</span>
        <span class="stat__label">今日疑似攻击</span>
      </NCard>
      <NCard pad="lg" class="stat">
        <span class="stat__number">{{ stats.totalRequests ?? 0 }}</span>
        <span class="stat__label">累计访问</span>
      </NCard>
    </div>

    <NCard pad="lg" class="card">
      <div class="card__head">
        <h2 class="card__title">访问趋势</h2>
        <select v-model.number="statsDays" class="select" aria-label="统计天数" @change="loadStats">
          <option :value="7">最近 7 天</option>
          <option :value="14">最近 14 天</option>
          <option :value="30">最近 30 天</option>
        </select>
      </div>
      <div class="chart__canvas">
        <canvas ref="trendCanvas"></canvas>
      </div>
    </NCard>

    <div class="grid-two">
      <NCard pad="lg" class="card">
        <h2 class="card__title">Top 来源 IP</h2>
        <ul class="ranking">
          <li v-for="item in stats.topIps || []" :key="item.key">
            <span class="ranking__key" :title="item.key">{{ item.key || '未知' }}</span>
            <span class="ranking__count">{{ item.count }}</span>
          </li>
        </ul>
      </NCard>
      <NCard pad="lg" class="card">
        <h2 class="card__title">Top 登录用户</h2>
        <ul class="ranking">
          <li v-for="item in stats.topUsers || []" :key="item.key">
            <span class="ranking__key" :title="item.key">{{ item.key || '未知' }}</span>
            <span class="ranking__count">{{ item.count }}</span>
          </li>
        </ul>
      </NCard>
      <NCard pad="lg" class="card">
        <h2 class="card__title">Top 路径</h2>
        <ul class="ranking">
          <li v-for="item in stats.topPaths || []" :key="item.key">
            <span class="ranking__key" :title="item.key">{{ item.key || '未知' }}</span>
            <span class="ranking__count">{{ item.count }}</span>
          </li>
        </ul>
      </NCard>
      <NCard pad="lg" class="card">
        <h2 class="card__title">威胁判定分布</h2>
        <ul class="ranking">
          <li v-for="(count, level) in stats.threatLevels || {}" :key="level">
            <span class="ranking__key">{{ threatLabel(level) }}</span>
            <span class="ranking__count">{{ count }}</span>
          </li>
        </ul>
      </NCard>
      <NCard pad="lg" class="card">
        <h2 class="card__title">Top User-Agent</h2>
        <ul class="ranking">
          <li v-for="item in stats.topUserAgents || []" :key="item.key">
            <span class="ranking__key" :title="item.key">{{ item.key || '（空）' }}</span>
            <span class="ranking__count">{{ item.count }}</span>
          </li>
        </ul>
      </NCard>
    </div>

    <div class="toolbar">
      <select v-model="accessQuery.threatLevel" class="select" aria-label="威胁判定">
        <option value="">全部威胁判定</option>
        <option v-for="item in THREAT_LEVELS" :key="item.value" :value="item.value">{{ item.label }}</option>
      </select>
      <NInput v-model="accessQuery.ip" placeholder="来源 IP 含…" clearable class="toolbar__search" />
      <NInput v-model="accessQuery.path" placeholder="路径含…" clearable class="toolbar__search" />
      <NInput
        v-model="accessQuery.keyword"
        icon="search"
        placeholder="路径 / UA / IP / 用户关键字…"
        clearable
        class="toolbar__search"
        @keyup.enter="loadAccess(1)"
      />
      <NButton variant="primary" icon="search" :disabled="loading" @click="loadAccess(1)">查询</NButton>
      <NButton
        :variant="live ? 'primary' : 'secondary'"
        icon="activity"
        :title="liveHint"
        @click="toggleLive"
      >{{ liveLabel }}</NButton>
    </div>

    <p v-if="live && livePaused" class="live-note">
      <NIcon name="activity" :size="14" />
      实时更新已开启，但当前在第 {{ access.page }} 页 / 带筛选条件，新记录不插入表格；回到第 1 页并清空筛选即可看到实时追加。
    </p>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>时间</th>
            <th>请求</th>
            <th>状态</th>
            <th>判定</th>
            <th>耗时</th>
            <th>分类</th>
            <th>来源 IP</th>
            <th>登录用户</th>
            <th>User-Agent</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in access.items" :key="row.id ?? row.__liveKey" :class="threatRowClass(row)">
            <td class="cell-dim">{{ formatTime(row.occurred_at) }}</td>
            <td class="cell-path" :title="fullPath(row)">
              <span class="cell-method">{{ row.method }}</span> {{ row.path }}
            </td>
            <td>
              <NTag :variant="statusVariant(row.status)" size="sm">{{ row.status }}</NTag>
            </td>
            <td :title="threatTitle(row)">
              <NTag :variant="threatVariant(row.threat_level)" size="sm">
                {{ threatLabel(row.threat_level) }}
              </NTag>
            </td>
            <td class="cell-dim">{{ formatLatency(row.duration_us) }}</td>
            <td class="cell-dim" :title="renderBranchTitle(row)">
              <span v-if="row.seo_render" class="cell-seo">SEO</span>
              {{ categoryLabel(row.category) }}
            </td>
            <td class="cell-dim">{{ row.client_ip }}</td>
            <td :class="loginUserText(row) === '—' ? 'cell-dim' : 'cell-user'" :title="loginUserTitle(row)">
              {{ loginUserText(row) }}
            </td>
            <td class="cell-ua" :title="row.user_agent">{{ row.user_agent || '（空）' }}</td>
            <td>
              <NButton size="sm" variant="ghost" icon="eye" @click="openDetail(row)">详情</NButton>
            </td>
          </tr>
          <tr v-if="!access.items.length">
            <td colspan="10" class="cell-empty">暂无记录</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <NButton size="sm" variant="secondary" icon="chevron-left" :disabled="access.page <= 1 || loading" @click="loadAccess(access.page - 1)">
        上一页
      </NButton>
      <span class="pager__info">共 {{ access.total }} 条 · 第 {{ access.page }} / {{ totalPages(access) }} 页</span>
      <NButton
        size="sm"
        variant="secondary"
        icon-after="chevron-right"
        :disabled="access.page >= totalPages(access) || loading"
        @click="loadAccess(access.page + 1)"
      >
        下一页
      </NButton>
    </div>

    <!-- 列表只放得下几列；完整 UA / Referer / 网段 / 线程等安全分析字段在这里全给出来 -->
    <LogDetailModal
      v-model="detailOpen"
      :title="detailTitle"
      :fields="detailFields"
    />
  </section>
</template>

<script setup>
/**
 * 访问日志面板（安全分析）。
 *
 * 放在左侧「安全」页里，而不是混进「操作日志」：访问量统计与安全分析是运维每天要看的第一手数据，
 * 混在「操作日志」里会让人以为日志页只关心后台动作。
 *
 * 实时更新走 SSE（`/api/admin/logs/access/stream`）：连上后台则新日志直接追加到表格顶部；
 * 翻页或带筛选条件时暂停追加（否则会把不属于当前视图的行混进来），只提示；断线期间缺的行用列表接口补。
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NTag } from '@/ui'
import LogDetailModal from '@/views/admin/components/LogDetailModal.vue'
import { fetchAccessLogs, fetchAccessLogStats, openAccessLogStream } from '@/api/adminLogs.js'
import { formatLatency } from '@/utils/format.js'
import {
  Chart as ChartJS,
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Title,
  Tooltip,
  Legend,
  Filler
} from 'chart.js'

ChartJS.register(
  LineController,
  LineElement,
  PointElement,
  CategoryScale,
  LinearScale,
  Title,
  Tooltip,
  Legend,
  Filler
)

const PAGE_SIZE = 50

const CATEGORY_LABELS = {
  api: '动态接口',
  admin: '管理后台',
  page: '页面',
  media: '媒体',
  static: '静态资源',
  other: '其它'
}

/** 与后端 ThreatAssessment 的取值保持一致。 */
const THREAT_LEVELS = [
  { value: 'normal', label: '正常' },
  { value: 'suspected', label: '疑似攻击' },
  { value: 'attack', label: '攻击' }
]
const THREAT_LABELS = Object.fromEntries(THREAT_LEVELS.map((item) => [item.value, item.label]))

/** 实时流最多在表格顶部保留多少行；再多就丢掉最旧的实时行，避免页面越开越卡。 */
const LIVE_MAX_ROWS = 200

const error = ref('')
const loading = ref(false)
const statsDays = ref(7)
const stats = ref({})
const access = reactive({ total: 0, page: 1, size: PAGE_SIZE, items: [] })
const accessQuery = reactive({
  ip: '',
  path: '',
  keyword: '',
  threatLevel: ''
})

const detailOpen = ref(false)
const detailTitle = ref('访问日志详情')
const detailFields = ref([])

const trendCanvas = ref(null)
let trendChart = null

const live = ref(true)
const liveState = ref('connecting')
const liveReceived = ref(0)
let liveStop = null
let liveSeq = 0

const livePaused = computed(() =>
  access.page !== 1 ||
  Boolean(
    accessQuery.ip ||
    accessQuery.path ||
    accessQuery.keyword ||
    accessQuery.threatLevel
  )
)
const liveLabel = computed(() => {
  if (!live.value) return '实时已关闭'
  if (liveState.value === 'open') return '实时更新中'
  if (liveState.value === 'connecting') return '实时连接中…'
  return '实时已断开'
})
const liveHint = computed(() => {
  if (!live.value) return '点击开启访问日志实时更新（SSE）'
  if (liveState.value === 'error') return `实时流已断开（累计收到 ${liveReceived.value} 条），点击重连`
  return `已实时收到 ${liveReceived.value} 条新日志，点击关闭`
})

onMounted(() => {
  loadAccess(1)
  loadStats()
  syncLiveStream()
})

onUnmounted(() => {
  closeLiveStream()
  destroyChart()
})

// ── 实时流（SSE） ─────────────────────────────────────────────

function toggleLive() {
  live.value = !live.value
  syncLiveStream()
}

function syncLiveStream() {
  if (live.value) {
    openLiveStream()
  } else {
    closeLiveStream()
  }
}

function openLiveStream() {
  if (liveStop || !liveTransportReady()) return
  liveState.value = 'connecting'
  liveStop = openAccessLogStream({
    onRecord: applyLiveRecord,
    onState: (state) => {
      liveState.value = state
      // 鉴权失败（401/403）时浏览器会自动无限重连，这里主动收手，等用户手动重开
      if (state === 'error') closeLiveStream()
    }
  })
}

function closeLiveStream() {
  if (!liveStop) return
  liveStop()
  liveStop = null
}

function liveTransportReady() {
  return typeof EventSource !== 'undefined' && Boolean(localStorage.getItem('adminToken'))
}

/** 新日志插入表格顶部：翻页或带筛选时不动表格（那会把不属于当前视图的行混进来）。 */
function applyLiveRecord(row) {
  liveReceived.value += 1
  if (livePaused.value) return
  row.__liveKey = `live-${(liveSeq += 1)}`
  access.items = [row, ...access.items].slice(0, LIVE_MAX_ROWS)
  access.total += 1
}

async function loadAccess(page) {
  loading.value = true
  error.value = ''
  try {
    const data = await fetchAccessLogs({
      page,
      size: PAGE_SIZE,
      ip: accessQuery.ip,
      path: accessQuery.path,
      threatLevel: accessQuery.threatLevel,
      keyword: accessQuery.keyword
    })
    access.total = data.total || 0
    access.page = data.page || page
    access.items = data.items || []
  } catch (e) {
    error.value = e.message
    access.items = []
    access.total = 0
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await fetchAccessLogStats(statsDays.value)
    await nextTick()
    renderChart()
  } catch (e) {
    error.value = e.message
  }
}

function renderChart() {
  if (!trendCanvas.value) return
  const trend = stats.value.dailyTrend || []
  destroyChart()
  trendChart = new ChartJS(trendCanvas.value, {
    type: 'line',
    data: {
      labels: trend.map((item) => item.date),
      datasets: [
        {
          label: '访问量',
          data: trend.map((item) => item.count),
          borderColor: '#5fd0e0',
          backgroundColor: 'rgba(95, 208, 224, 0.16)',
          fill: true,
          tension: 0.32,
          pointRadius: 2
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { ticks: { color: 'rgba(255,255,255,0.55)' }, grid: { display: false } },
        y: {
          beginAtZero: true,
          ticks: { color: 'rgba(255,255,255,0.55)', precision: 0 },
          grid: { color: 'rgba(255,255,255,0.06)' }
        }
      }
    }
  })
}

function destroyChart() {
  if (trendChart) {
    trendChart.destroy()
    trendChart = null
  }
}

function totalPages(page) {
  return Math.max(1, Math.ceil((page.total || 0) / (page.size || PAGE_SIZE)))
}

function formatTime(millis) {
  if (!millis) return '—'
  return new Date(Number(millis)).toLocaleString('zh-CN', { hour12: false })
}

function fullPath(row) {
  return row.query ? `${row.path}?${row.query}` : row.path
}

function categoryLabel(value) {
  return CATEGORY_LABELS[value] || value || '—'
}

/**
 * 已登录用户：日志里存的是**解析当时**的快照（昵称改了不回溯旧记录）。
 *
 * 未登录、令牌失效或用户已被删除时都是「—」——注意它与 `authenticated` 不是一回事：后者只说明
 * 请求带了 Bearer 头，令牌能不能换来用户 ID 才是这里的判据。
 */
function loginUserText(row) {
  const id = row.user_id == null ? null : Number(row.user_id)
  const name = row.user_name || ''
  if (name && id) return `#${id} ${name}`
  if (name) return name
  if (id) return `#${id}`
  return '—'
}

function loginUserTitle(row) {
  if (loginUserText(row) === '—') {
    return Number(row.authenticated) ? '带了令牌但未解析出用户（令牌失效 / 已注销 / 管理员令牌）' : '未登录'
  }
  return `用户 ID ${row.user_id}${row.user_name ? ` · ${row.user_name}` : ''}`
}

/**
 * 响应分支：同一 URL 对爬虫与浏览器有两张表示（SEO 服务端 HTML / SPA 壳）。
 *
 * 「分类」是按路径算的，`/detail/148` 永远是 page；只有这一个字段能说明这次到底是「被收录」
 * 还是「用户在浏览」。与 SEO 无关的路径记为「不适用」，不要把它显示成「非 SEO」。
 */
function renderBranchText(row) {
  if (row.seo_render === true || row.seo_render === 1) return 'SEO 服务端渲染（爬虫 / 链接预览）'
  if (row.seo_render === false || row.seo_render === 0) return 'SPA 入口（浏览器表示）'
  return '未记录（历史数据，或该路径没有 SEO 表示）'
}

function renderBranchTitle(row) {
  if (row.seo_render === true || row.seo_render === 1) {
    return '本次返回服务端 SEO 渲染的 HTML（爬虫 / 链接预览拿到的表示）'
  }
  if (row.seo_render === false || row.seo_render === 0) {
    return '本次走 SPA：浏览器拿到的站点应用入口'
  }
  return '本次未记录渲染分支：旧数据，或该路径没有 SEO 表示'
}

function statusVariant(status) {
  if (status >= 500) return 'danger'
  if (status >= 400) return 'warning'
  return 'success'
}

function threatLabel(value) {
  return THREAT_LABELS[value] || '正常'
}

function threatVariant(value) {
  if (value === 'attack') return 'danger'
  if (value === 'suspected') return 'warning'
  return 'success'
}

function threatTitle(row) {
  const label = threatLabel(row.threat_level)
  const score = row.threat_score == null ? 0 : Number(row.threat_score)
  const reasons = row.threat_reasons ? `：${row.threat_reasons}` : ''
  return `${label} · 风险分 ${score}${reasons}`
}

function threatRowClass(row) {
  if (row.threat_level === 'attack') return 'row--attack'
  if (row.threat_level === 'suspected') return 'row--suspected'
  return ''
}

function formatBytes(value) {
  const bytes = Number(value) || 0
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/**
 * 详情弹窗：把这一条访问日志的全部字段摊开。
 *
 * 表格里只放得下「谁、什么时候、做了什么」；排查爬虫 / 脚本 / 被刷要看的是完整 UA、Referer、
 * Origin、网段、Host 与线程，这些一个都不能省。
 */
function openDetail(row) {
  detailTitle.value = row.id ? `访问日志详情 · #${row.id}` : '访问日志详情 · 实时记录'
  detailFields.value = [
    { label: '记录 ID', value: row.id ?? '实时行（尚未落库）', mono: true },
    { label: '时间', value: formatTime(row.occurred_at) },
    { label: '时间戳', value: row.occurred_at, mono: true },
    { label: '请求方法', value: row.method, mono: true },
    { label: '路径', value: row.path, mono: true },
    { label: '查询串', value: row.query || '（无）', mono: true, block: true },
    { label: '完整请求', value: fullPath(row), mono: true, block: true },
    { label: '请求分类', value: categoryLabel(row.category) },
    { label: '响应分支', value: renderBranchText(row) },
    { label: '状态码', value: row.status },
    { label: '威胁判定', value: threatLabel(row.threat_level) },
    { label: '风险分', value: row.threat_score == null ? '0' : `${row.threat_score} / 100` },
    { label: '命中原因', value: row.threat_reasons || '未命中风险规则', block: true },
    { label: '判定引擎版本', value: row.threat_engine_version },
    { label: '耗时', value: formatLatency(row.duration_us) },
    { label: '响应体大小', value: formatBytes(row.response_bytes) },
    { label: '来源 IP', value: row.client_ip, mono: true },
    { label: '所属网段', value: row.ip_subnet, mono: true },
    { label: 'User-Agent', value: row.user_agent, mono: true, block: true },
    { label: 'Referer', value: row.referer, block: true },
    { label: 'Origin', value: row.origin, block: true },
    { label: 'Accept-Language', value: row.accept_language },
    { label: '已认证', value: Number(row.authenticated) ? '是（携带令牌）' : '否' },
    { label: '登录用户 ID', value: row.user_id, mono: true },
    { label: '登录用户昵称', value: row.user_name || '（未解析出用户）' },
    { label: '管理员 ID', value: row.admin_id },
    { label: '管理员', value: row.admin_username },
    { label: 'Host', value: row.host, mono: true },
    { label: '请求方案', value: row.scheme, mono: true },
    { label: 'HTTP 协议', value: row.protocol, mono: true },
    { label: '处理线程', value: row.thread, mono: true }
  ]
  detailOpen.value = true
}
</script>

<style scoped>
.access {
  margin-top: var(--n-space-6);
}

.access__head {
  margin-bottom: var(--n-space-4);
}

.access__title {
  margin: 0 0 var(--n-space-1);
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
}

.access__desc {
  margin: 0;
  max-width: 72ch;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.err {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  margin-bottom: var(--n-space-4);
  color: var(--n-color-danger, #ff6b6b);
  font-size: var(--n-text-sm);
}

/* 已登录用户：一眼分出「谁在登着账号访问」，未登录的行保持暗色 */
.cell-user {
  color: var(--n-accent-strong);
  white-space: nowrap;
}

/* 「本次返回的是 SEO 服务端渲染」——分类列里的加注，别让爬虫收录看起来像普通页面浏览 */
.cell-seo {
  margin-right: 2px;
  padding: 0 4px;
  border-radius: var(--n-radius-xs);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

/* ==================== 统计卡 ==================== */
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-1);
}

.stat__number {
  color: var(--n-text);
  font-size: var(--n-text-2xl);
  font-weight: var(--n-weight-bold);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
}

.stat__label {
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.stat--danger .stat__number {
  color: var(--n-color-danger, #ff6b6b);
}

.stat--warning .stat__number {
  color: var(--n-color-warning, #f5b74e);
}

/* ==================== 卡片 ==================== */
.card {
  margin-bottom: var(--n-space-5);
}

.card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
}

.card__title {
  margin: 0 0 var(--n-space-4);
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
}

.card__head .card__title {
  margin-bottom: 0;
}

.chart__canvas {
  position: relative;
  width: 100%;
  min-height: 260px;
  margin-top: var(--n-space-4);
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
}

.ranking {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.ranking li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  font-size: var(--n-text-sm);
}

.ranking__key {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--n-text-muted);
}

.ranking__count {
  flex: none;
  color: var(--n-text);
  font-variant-numeric: tabular-nums;
}

/* ==================== 工具条 ==================== */
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-4);
}

.select {
  height: 40px;
  padding: 0 var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-sm);
  background: var(--n-surface-2);
  color: var(--n-text);
  font-size: var(--n-text-sm);
}

.toolbar__search {
  flex: 1 1 180px;
  max-width: 260px;
}

.live-note {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  margin: 0 0 var(--n-space-3);
  padding: var(--n-space-2) var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-md);
  background: var(--n-surface-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== 表格 ==================== */
.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-md);
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--n-text-sm);
}

.table th,
.table td {
  padding: var(--n-space-3);
  text-align: left;
  border-bottom: 1px solid var(--n-line-subtle);
  vertical-align: top;
}

.table th {
  color: var(--n-text-faint);
  font-weight: var(--n-weight-medium);
  white-space: nowrap;
}

.table tbody tr:last-child td {
  border-bottom: none;
}

.table tbody tr.row--attack > td:first-child {
  box-shadow: inset 3px 0 0 var(--n-color-danger, #ff6b6b);
}

.table tbody tr.row--suspected > td:first-child {
  box-shadow: inset 3px 0 0 var(--n-color-warning, #f5b74e);
}

.cell-dim {
  color: var(--n-text-muted);
  white-space: nowrap;
}

.cell-path {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--n-text-muted);
}

.cell-method {
  color: var(--n-accent-strong);
  font-weight: var(--n-weight-semibold);
}

.cell-ua {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--n-text-faint);
}

.cell-empty {
  text-align: center;
  color: var(--n-text-faint);
}

/* ==================== 分页 ==================== */
.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-4);
  margin-top: var(--n-space-4);
}

.pager__info {
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}
</style>

<template>
  <div class="subpage">
    <header class="subpage__head">
      <div>
        <h1 class="subpage__title">操作日志</h1>
        <p class="subpage__desc">
          记录后台写操作：系统设置修改、审核通过 / 驳回、音乐上传 / 修改 / 删除、歌词修改等，并保留操作人、时间、
          结果与来源；系统设置类修改会写明改了哪一项、原值是什么、改成了什么。访问量与安全分析见左侧「安全」。
        </p>
      </div>
    </header>

    <p v-if="error" class="err">
      <NIcon name="triangle-alert" :size="16" />
      {{ error }}
    </p>

    <div class="toolbar">
      <select v-model="operationQuery.module" class="select" aria-label="模块">
        <option value="">全部模块</option>
        <option v-for="name in MODULES" :key="name" :value="name">{{ name }}</option>
      </select>
      <NInput
        v-model="operationQuery.keyword"
        icon="search"
        placeholder="路径 / 查询串 / 管理员 / 说明…"
        clearable
        class="toolbar__search"
        @keyup.enter="loadOperations(1)"
      />
      <NButton variant="primary" icon="search" :disabled="loading" @click="loadOperations(1)">查询</NButton>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>时间</th>
            <th>管理员</th>
            <th>角色</th>
            <th>模块</th>
            <th>动作</th>
            <th>请求</th>
            <th>状态</th>
            <th>耗时</th>
            <th>来源 IP</th>
            <th>说明</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in operations.items" :key="row.id">
            <td class="cell-dim">{{ formatTime(row.occurred_at) }}</td>
            <td class="cell-strong">{{ row.admin_username || '—' }}</td>
            <td>{{ roleLabel(row.admin_role) }}</td>
            <td>{{ row.module || '—' }}</td>
            <td>{{ row.action || '—' }}</td>
            <td class="cell-path" :title="fullPath(row)">
              <span class="cell-method">{{ row.method }}</span> {{ row.path }}
            </td>
            <td>
              <NTag :variant="row.success ? 'success' : 'danger'" size="sm">{{ row.status }}</NTag>
            </td>
            <td class="cell-dim">{{ formatLatency(row.duration_us) }}</td>
            <td class="cell-dim">{{ row.client_ip }}</td>
            <td class="cell-detail" :title="row.detail || ''">{{ detailSummary(row) }}</td>
            <td>
              <NButton size="sm" variant="ghost" icon="eye" @click="openDetail(row)">详情</NButton>
            </td>
          </tr>
          <tr v-if="!operations.items.length">
            <td colspan="11" class="cell-empty">暂无记录</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <NButton size="sm" variant="secondary" icon="chevron-left" :disabled="operations.page <= 1 || loading" @click="loadOperations(operations.page - 1)">
        上一页
      </NButton>
      <span class="pager__info">共 {{ operations.total }} 条 · 第 {{ operations.page }} / {{ totalPages(operations) }} 页</span>
      <NButton
        size="sm"
        variant="secondary"
        icon-after="chevron-right"
        :disabled="operations.page >= totalPages(operations) || loading"
        @click="loadOperations(operations.page + 1)"
      >
        下一页
      </NButton>
    </div>

    <!-- 列表只放得下概览；改了哪个字段、从什么变成什么，以及完整 UA / 平台等在详情里全给出来 -->
    <LogDetailModal
      v-model="detailOpen"
      :title="detailTitle"
      :fields="detailFields"
      :changes="detailChanges"
      changes-title="变更内容（原值 → 新值）"
    />
  </div>
</template>

<script setup>
/**
 * 操作日志：后台写操作的人话留痕（谁、什么时候、对哪个模块做了什么、结果如何）。
 *
 * 这里只放操作日志——访问量统计与安全分析（含实时流）在左侧「安全」页里
 * （见 AdminSecurityView.vue / components/AccessLogPanel.vue）。
 */
import { onMounted, reactive, ref } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NInput, NTag } from '@/ui'
import LogDetailModal from '@/views/admin/components/LogDetailModal.vue'
import { fetchOperationLogs } from '@/api/adminLogs.js'
import { parseOperationDetail } from '@/utils/operationDetail.js'
import { formatLatency } from '@/utils/format.js'

const PAGE_SIZE = 50

/** 与后端 RequestAuditFilter.ADMIN_MODULES 的模块名保持一致 */
const MODULES = [
  '用户管理',
  '音乐管理',
  '歌词管理',
  '审核管理',
  '客户端更新',
  '系统设置',
  '外站补全',
  'VIP 定价',
  '管理员管理',
  '管理员登录',
  '数据统计'
]

const error = ref('')
const loading = ref(false)
const operations = reactive({ total: 0, page: 1, size: PAGE_SIZE, items: [] })
const operationQuery = reactive({ module: '', keyword: '' })

const detailOpen = ref(false)
const detailTitle = ref('操作日志详情')
const detailFields = ref([])
const detailChanges = ref([])

onMounted(() => {
  loadOperations(1)
})

async function loadOperations(page) {
  loading.value = true
  error.value = ''
  try {
    const data = await fetchOperationLogs({
      page,
      size: PAGE_SIZE,
      module: operationQuery.module,
      keyword: operationQuery.keyword
    })
    operations.total = data.total || 0
    operations.page = data.page || page
    operations.items = data.items || []
  } catch (e) {
    error.value = e.message
    operations.items = []
    operations.total = 0
  } finally {
    loading.value = false
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

function roleLabel(value) {
  return { super_admin: '超级管理员', admin: '管理员', auditor: '审核员' }[value] || value || '—'
}

/**
 * 列表里的「说明」：写明这条操作改了什么。
 *
 * detail 是多行「字段：原值 → 新值」，列表格宽度有限，只显示第一条并标出总条数——
 * 只写「修改系统设置」而不写改了哪一项，等于没记（完整内容在详情弹窗里）。
 */
function detailSummary(row) {
  const raw = typeof row.detail === 'string' ? row.detail.trim() : ''
  if (!raw) return '—'
  const lines = raw.split('\n').filter((line) => line.trim())
  if (lines.length <= 1) return lines[0] || raw
  return `${lines[0]} 等 ${lines.length} 项`
}

/** 详情弹窗：操作日志的全部字段，并把 detail 解析成「原值 → 新值」三列。 */
function openDetail(row) {
  detailTitle.value = row.id ? `操作日志详情 · #${row.id}` : '操作日志详情'
  const changes = parseOperationDetail(row.detail)
  detailChanges.value = changes
  detailFields.value = [
    { label: '记录 ID', value: row.id, mono: true },
    { label: '时间', value: formatTime(row.occurred_at) },
    { label: '时间戳', value: row.occurred_at, mono: true },
    { label: '管理员 ID', value: row.admin_id },
    { label: '管理员', value: row.admin_username },
    { label: '角色', value: roleLabel(row.admin_role) },
    { label: '模块', value: row.module },
    { label: '动作', value: row.action },
    { label: '请求方法', value: row.method, mono: true },
    { label: '路径', value: row.path, mono: true },
    { label: '查询串', value: row.query || '（无）', mono: true, block: true },
    { label: '完整请求', value: fullPath(row), mono: true, block: true },
    { label: '状态码', value: row.status },
    { label: '结果', value: Number(row.success) ? '成功' : '失败' },
    { label: '耗时', value: formatLatency(row.duration_us) },
    { label: '来源 IP', value: row.client_ip, mono: true },
    { label: 'User-Agent', value: row.user_agent, mono: true, block: true }
  ]
  // 解析不出「原值 → 新值」时才把原文照抄一份（老记录写的是路径编号这类纯文本）
  if (!changes.length && row.detail) {
    detailFields.value.push({ label: '说明', value: row.detail, mono: true, block: true })
  }
  detailOpen.value = true
}
</script>

<style scoped>
.subpage {
  width: 100%;
}

.subpage__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
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
  max-width: 72ch;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
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
  flex: 1 1 220px;
  max-width: 320px;
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

.cell-dim {
  color: var(--n-text-muted);
  white-space: nowrap;
}

.cell-strong {
  color: var(--n-text);
  font-weight: var(--n-weight-semibold);
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

.cell-detail {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--n-text-muted);
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

.err {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  margin-bottom: var(--n-space-4);
  color: var(--n-color-danger, #ff6b6b);
  font-size: var(--n-text-sm);
}
</style>

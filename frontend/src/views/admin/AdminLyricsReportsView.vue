<template>
  <div class="subpage">
    <header class="subpage__head">
      <h1 class="subpage__title">歌词报错审核</h1>
      <p class="subpage__desc">
        审核用户提交的歌词修改、翻译补充与逐字时间轴。通过后会直接更新正式歌词。
      </p>
    </header>

    <div v-if="isLoading" class="state">
      <NSpinner :size="28" />
      <p>正在加载待审核歌词报错…</p>
    </div>

    <NCard v-else-if="reports.length === 0" pad="lg" class="state state--empty">
      <NIcon name="file-text" :size="26" />
      <p>暂无待审核的歌词报错</p>
    </NCard>

    <div v-else class="list">
      <NCard v-for="report in reports" :key="report.id" pad="none" class="report-card">
        <header class="report-card__head">
          <div class="report-card__title">
            <span class="report-card__id">#{{ report.id }}</span>
            <h2>{{ report.title }}</h2>
            <p>{{ report.artist }}</p>
          </div>
          <div class="report-card__meta">
            <span>{{ report.userNickname || `用户 ${report.userId}` }}</span>
            <span>{{ report.createdAt }}</span>
          </div>
        </header>

        <p v-if="report.note" class="report-card__note">
          <NIcon name="message" :size="14" />
          {{ report.note }}
        </p>

        <div class="report-card__body">
          <div class="report-card__stats">
            <NTag variant="accent" size="sm">{{ report.stats.lines }} 行</NTag>
            <NTag size="sm">{{ report.stats.translated }} 行翻译</NTag>
            <NTag size="sm">{{ report.stats.words }} 个逐字片段</NTag>
          </div>
          <NButton
            size="sm"
            variant="ghost"
            :icon="previewId === report.id ? 'eye-off' : 'eye'"
            :loading="detailLoading[report.id]"
            @click="togglePreview(report.id)"
          >
            {{ previewId === report.id ? '隐藏歌词' : '查看歌词' }}
          </NButton>
        </div>

        <div v-if="previewId === report.id" class="preview">
          <div v-if="detailLoading[report.id]" class="preview__state">加载中…</div>
          <div v-else-if="details[report.id]" class="preview__compare">
            <section class="preview__panel">
              <h3>提交版本</h3>
              <div class="preview__lines">
                <article
                  v-for="(line, index) in details[report.id].lines"
                  :key="index"
                  class="preview-line"
                >
                  <span class="preview-line__time">{{ formatTime(line.startMs) }}</span>
                  <div class="preview-line__main">
                    <p>{{ line.text || '（空行）' }}</p>
                    <p v-if="line.translation" class="preview-line__translation">
                      {{ line.translation }}
                    </p>
                    <p v-if="line.romanization" class="preview-line__romanization">
                      {{ line.romanization }}
                    </p>
                    <div v-if="line.words?.length" class="preview-line__words">
                      <span v-for="(word, wordIndex) in line.words" :key="wordIndex">
                        {{ word.text }}
                      </span>
                    </div>
                  </div>
                </article>
              </div>
            </section>
            <section class="preview__panel">
              <h3>当前正式歌词</h3>
              <div v-if="details[report.id].originalLines.length" class="preview__lines">
                <article
                  v-for="(line, index) in details[report.id].originalLines"
                  :key="index"
                  class="preview-line"
                >
                  <span class="preview-line__time">{{ formatTime(line.startMs) }}</span>
                  <div class="preview-line__main">
                    <p>{{ line.text || '（空行）' }}</p>
                    <p v-if="line.translation" class="preview-line__translation">
                      {{ line.translation }}
                    </p>
                    <p v-if="line.romanization" class="preview-line__romanization">
                      {{ line.romanization }}
                    </p>
                  </div>
                </article>
              </div>
              <div v-else class="preview__state">当前没有可对比的正式歌词</div>
            </section>
                </div>
          <div v-else class="preview__state">歌词详情加载失败</div>
        </div>

        <footer class="report-card__actions">
          <NButton
            variant="danger"
            icon="close"
            :disabled="Boolean(actionLoading[report.id])"
            @click="openReject(report)"
          >
            拒绝
          </NButton>
          <NButton
            variant="primary"
            icon="check"
            :loading="actionLoading[report.id] === 'approve'"
            :disabled="Boolean(actionLoading[report.id])"
            @click="approve(report)"
          >
            通过并更新歌词
          </NButton>
        </footer>
      </NCard>
    </div>

    <NModal v-model="rejectOpen" title="拒绝歌词报错" size="sm">
      <label class="field">
        <span>审核备注（可选）</span>
        <NInput v-model="rejectReason" textarea :rows="4" placeholder="说明未通过原因" />
      </label>
      <template #footer>
        <NButton variant="ghost" :disabled="rejecting" @click="rejectOpen = false">取消</NButton>
        <NButton variant="danger" :loading="rejecting" :disabled="rejecting" @click="confirmReject">
          确认拒绝
        </NButton>
      </template>
    </NModal>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from '@/composables/useToast'
import { parseLyricsPayload } from '@/utils/lyrics.js'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NModal, NSpinner, NTag } from '@/ui'

const toast = useToast()
const isLoading = ref(true)
const reports = ref([])
const previewId = ref(null)
const details = reactive({})
const detailLoading = reactive({})
const actionLoading = reactive({})
const rejectOpen = ref(false)
const rejectTarget = ref(null)
const rejectReason = ref('')
const rejecting = ref(false)

onMounted(loadPending)

function adminHeaders(json = false) {
  return {
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    Authorization: `Bearer ${localStorage.getItem('adminToken') || ''}`,
  }
}

async function loadPending() {
  isLoading.value = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/lyrics-reports/pending`, {
      headers: adminHeaders(),
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || !payload.success) {
      throw new Error(payload.message || '获取歌词报错失败')
    }
    reports.value = (payload.data?.reports || []).map((report) => ({
      ...report,
      stats: report.stats || { lines: 0, translated: 0, words: 0 },
    }))
  } catch (error) {
    toast.error(error.message || '获取歌词报错失败')
  } finally {
    isLoading.value = false
  }
}

async function loadDetail(id) {
  if (details[id]) return details[id]
  detailLoading[id] = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/lyrics-reports/${id}`, {
      headers: adminHeaders(),
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || !payload.success) {
      throw new Error(payload.message || '获取歌词详情失败')
    }
    const parsed = parseReportContent(payload.data?.content)
    const originalLines = parseOriginalContent(
      payload.data?.originalContent,
      payload.data?.originalFormat,
    )
    details[id] = { ...payload.data, lines: parsed, originalLines }
    return details[id]
  } finally {
    detailLoading[id] = false
  }
}

async function togglePreview(id) {
  if (previewId.value === id) {
    previewId.value = null
    return
  }
  previewId.value = id
  try {
    await loadDetail(id)
  } catch (error) {
    toast.error(error.message || '获取歌词详情失败')
  }
}

async function approve(report) {
  if (actionLoading[report.id]) return
  actionLoading[report.id] = 'approve'
  try {
    const response = await fetch(
      `${API_CONFIG.BASE_URL}/api/admin/lyrics-reports/${report.id}/approve`,
      {
        method: 'POST',
        headers: adminHeaders(true),
        body: JSON.stringify({ reviewNote: '' }),
      },
    )
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || !payload.success) {
      throw new Error(payload.message || '审核失败')
    }
    reports.value = reports.value.filter((item) => item.id !== report.id)
    toast.success('歌词报错已通过，正式歌词已更新')
  } catch (error) {
    toast.error(error.message || '审核失败')
  } finally {
    actionLoading[report.id] = ''
  }
}

function openReject(report) {
  rejectTarget.value = report
  rejectReason.value = ''
  rejectOpen.value = true
}

async function confirmReject() {
  if (!rejectTarget.value || rejecting.value) return
  rejecting.value = true
  const report = rejectTarget.value
  try {
    const response = await fetch(
      `${API_CONFIG.BASE_URL}/api/admin/lyrics-reports/${report.id}/reject`,
      {
        method: 'POST',
        headers: adminHeaders(true),
        body: JSON.stringify({ reviewNote: rejectReason.value }),
      },
    )
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || !payload.success) {
      throw new Error(payload.message || '拒绝失败')
    }
    reports.value = reports.value.filter((item) => item.id !== report.id)
    rejectOpen.value = false
    toast.success('歌词报错已拒绝')
  } catch (error) {
    toast.error(error.message || '拒绝失败')
  } finally {
    rejecting.value = false
  }
}

function parseReportContent(content) {
  try {
    const document = JSON.parse(content || '{}')
    return Array.isArray(document.lines) ? document.lines : []
  } catch {
    return []
  }
}

function parseOriginalContent(content, format) {
  if (!content) return []
  try {
    if (String(format || '').toLowerCase() === 'neko-v1'
      || String(content).trimStart().startsWith('{')) {
      const document = JSON.parse(content)
      return Array.isArray(document.lines) ? document.lines : []
    }
    return parseLyricsPayload({ lyrics: content }).map((line) => ({
      startMs: Math.round(Number(line.time || 0) * 1000),
      text: line.text || '',
      translation: line.translation || '',
    }))
  } catch {
    return []
  }
}

function formatTime(ms) {
  const value = Math.max(0, Number(ms) || 0)
  const minutes = Math.floor(value / 60000)
  const seconds = Math.floor((value % 60000) / 1000)
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}
</script>

<style scoped>
.subpage {
  width: 100%;
  max-width: 1500px;
  margin: 0 auto;
}

.subpage__head {
  margin-bottom: var(--n-space-5);
}

.subpage__title {
  margin: 0 0 var(--n-space-2);
}

.subpage__desc {
  margin: 0;
  color: var(--n-text-muted);
}

.state {
  display: grid;
  place-items: center;
  gap: var(--n-space-3);
  padding: var(--n-space-8);
  color: var(--n-text-muted);
}

.list {
  display: grid;
  gap: var(--n-space-4);
}

@media (min-width: 1400px) {
  .list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

.report-card {
  padding: var(--n-space-5);
}

.report-card__head,
.report-card__body,
.report-card__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-4);
}

.report-card__title {
  min-width: 0;
}

.report-card__title h2,
.report-card__title p {
  margin: 0;
}

.report-card__title p,
.report-card__meta {
  color: var(--n-text-muted);
}

.report-card__id {
  color: var(--n-accent);
  font-size: var(--n-text-xs);
}

.report-card__meta {
  display: grid;
  gap: 4px;
  text-align: right;
  font-size: var(--n-text-sm);
}

.report-card__note {
  display: flex;
  gap: 8px;
  margin: var(--n-space-4) 0;
  padding: 10px 12px;
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
}

.report-card__body {
  margin-top: var(--n-space-4);
}

.report-card__stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview {
  margin-top: var(--n-space-4);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line-subtle);
  border-radius: var(--n-radius-md);
  background: var(--n-surface-soft);
}

.preview__state {
  color: var(--n-text-muted);
}

.preview__lines {
  display: grid;
  gap: 10px;
  max-height: 460px;
  overflow: auto;
}

.preview__compare {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--n-space-4);
}

.preview__panel {
  min-width: 0;
}

.preview__panel h3 {
  margin: 0 0 var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.preview-line {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: var(--n-space-3);
}

.preview-line__time {
  color: var(--n-text-faint);
  font-variant-numeric: tabular-nums;
}

.preview-line__main p {
  margin: 0;
}

.preview-line__translation {
  margin-top: 4px !important;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.preview-line__romanization {
  margin-top: 3px !important;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: 1.45;
}

.preview-line__words {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.preview-line__words span {
  padding: 2px 6px;
  border-radius: 5px;
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
}

.report-card__actions {
  justify-content: flex-end;
  margin-top: var(--n-space-4);
}

.field {
  display: grid;
  gap: 8px;
  color: var(--n-text-muted);
}

@media (max-width: 720px) {
  .report-card__head,
  .report-card__body {
    align-items: flex-start;
    flex-direction: column;
  }

  .report-card__meta {
    text-align: left;
  }

  .report-card__actions {
    align-items: stretch;
    flex-direction: column-reverse;
  }

  .preview__compare {
    grid-template-columns: 1fr;
  }
}
</style>

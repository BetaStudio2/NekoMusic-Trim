<template>
  <AmbientBackdrop />

  <PageShell width="xwide">
    <div class="lyrics-tool">
      <header class="lyrics-tool__hero">
        <div class="lyrics-tool__hero-copy">
          <span class="lyrics-tool__eyebrow">NEKO LYRICS STUDIO</span>
          <h1>逐字歌词制作工具</h1>
          <p>
            选择本地音乐，粘贴或输入歌词，听着音乐逐字打轴。完成后直接下载
            <code>.neko</code>，可上传到音乐投稿的歌词入口。
          </p>
          <div class="lyrics-tool__privacy">
            <span><NIcon name="shield-check" :size="15" /> 全程本地处理</span>
            <span><NIcon name="cloud-upload" :size="15" /> 不上传音频或歌词</span>
            <span><NIcon name="file-text" :size="15" /> 下载 .neko 歌词</span>
          </div>
        </div>
        <div class="lyrics-tool__format-card" aria-hidden="true">
          <span>OUTPUT</span>
          <strong>.neko</strong>
          <small>version 1 · words[]</small>
        </div>
      </header>

      <section class="lyrics-tool__setup">
        <NCard pad="md" class="lyrics-tool__card">
          <div class="lyrics-tool__step">
            <span class="lyrics-tool__step-no">01</span>
            <div>
              <h2>选择本地音乐</h2>
              <p>支持浏览器可播放的 MP3、FLAC、WAV、M4A、OGG 等格式。</p>
            </div>
          </div>

          <input
            ref="audioInput"
            class="lyrics-tool__hidden-input"
            type="file"
            accept="audio/*,.mp3,.flac,.wav,.m4a,.aac,.ogg,.opus"
            @change="handleAudioChange"
          />
          <button
            type="button"
            class="lyrics-tool__audio-drop"
            :class="{ 'lyrics-tool__audio-drop--over': audioDragging, 'lyrics-tool__audio-drop--ready': audioFile }"
            @click="audioInput?.click()"
            @dragenter.prevent="audioDragging = true"
            @dragover.prevent="audioDragging = true"
            @dragleave.prevent="audioDragging = false"
            @drop.prevent="handleAudioDrop"
          >
            <span class="lyrics-tool__audio-icon">
              <NIcon :name="audioFile ? 'circle-check' : 'file-music'" :size="24" />
            </span>
            <span class="lyrics-tool__audio-text">
              <strong>{{ audioFile ? audioFile.name : '点击选择音乐文件' }}</strong>
              <small>{{ audioFile ? '已载入本地音频，点击可更换' : '也可以直接把音乐拖到这里' }}</small>
            </span>
            <NIcon name="chevron-right" :size="17" class="lyrics-tool__audio-arrow" />
          </button>
        </NCard>

        <NCard pad="md" class="lyrics-tool__card">
          <div class="lyrics-tool__step">
            <span class="lyrics-tool__step-no">02</span>
            <div>
              <h2>粘贴或输入歌词</h2>
              <p>支持纯文本、LRC 或完整 neko-v1 JSON，也可以直接导入 .lrc / .neko 文件。</p>
            </div>
          </div>

          <input
            ref="lyricsImportInput"
            class="lyrics-tool__hidden-input"
            type="file"
            accept=".lrc,.neko,text/plain,application/json"
            @change="handleLyricsFileImport"
          />
          <NInput
            v-model="lyricsText"
            textarea
            :rows="10"
            class="lyrics-tool__textarea"
            placeholder="第一句歌词&#10;第二句歌词&#10;第三句歌词"
          />
          <div class="lyrics-tool__lyrics-actions">
            <span>{{ lyricsInputSummary }}</span>
            <div class="lyrics-tool__lyrics-buttons">
              <NButton variant="secondary" icon="upload" @click="lyricsImportInput?.click()">
                导入歌词文件
              </NButton>
              <NButton variant="primary" icon="sparkles" @click="applyLyrics">
                生成逐字编辑
              </NButton>
            </div>
          </div>
        </NCard>
      </section>

      <NCard v-if="lines.length && audioUrl" pad="md" class="lyrics-tool__studio">
        <header class="lyrics-tool__studio-head">
          <div>
            <span class="lyrics-tool__eyebrow">STEP 03 · TIMING</span>
            <h2>逐字打轴</h2>
            <p>开始播放后，在目标字/词出现时点击节拍按钮；空格、标点和符号不会成为打轴目标。</p>
          </div>
          <div class="lyrics-tool__stats" aria-live="polite">
            <span>{{ timingStats.timedLines }} / {{ timingStats.totalLines }} 行完成</span>
            <span>{{ timingStats.timedWords }} / {{ timingStats.totalWords }} 个片段</span>
          </div>
        </header>

        <div class="lyrics-tool__progress" aria-hidden="true">
          <span :style="{ width: `${timingStats.progress * 100}%` }" />
        </div>

        <div class="lyrics-tool__modes" role="tablist" aria-label="逐字歌词工作区">
          <button
            type="button"
            role="tab"
            :aria-selected="activeStudioTab === 'timing'"
            :class="{ 'lyrics-tool__mode--active': activeStudioTab === 'timing' }"
            @click="activeStudioTab = 'timing'"
          >
            <NIcon name="music-2" :size="15" />
            逐字打轴
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeStudioTab === 'preview'"
            :class="{ 'lyrics-tool__mode--active': activeStudioTab === 'preview' }"
            @click="activeStudioTab = 'preview'"
          >
            <NIcon name="eye" :size="15" />
            效果预览
          </button>
        </div>

        <KaraokeTimingEditor
          v-if="activeStudioTab === 'timing'"
          :lines="lines"
          :audio-url="audioUrl"
          :audio-loading="false"
          @change="timingRevision += 1"
        />

        <LyricsPreviewPanel
          v-else
          :lines="lines"
          :audio-url="audioUrl"
          :audio-loading="false"
          hint="这是本地歌词效果预览。播放音乐后可检查逐字高亮、滚动和行间衔接。"
        />

        <footer class="lyrics-tool__export">
          <div class="lyrics-tool__export-state">
            <NIcon :name="timingStats.complete ? 'circle-check' : 'timer'" :size="19" />
            <div>
              <strong>{{ timingStats.complete ? '时间轴已完成，可以下载歌词' : '还没有完成全部歌词打轴' }}</strong>
              <p v-if="!timingStats.complete">
                还有 {{ timingStats.totalLines - timingStats.timedLines }} 行需要完成逐字和时间轴。
              </p>
              <p v-else>下载文件只包含歌词和时间轴，不包含你选择的音乐文件。</p>
            </div>
          </div>
          <div class="lyrics-tool__export-actions">
            <NButton variant="ghost" icon="trash" @click="resetWorkspace">清空</NButton>
            <NButton variant="primary" icon="download" @click="downloadNeko">下载歌词</NButton>
          </div>
        </footer>
      </NCard>

      <NCard v-else-if="lines.length" pad="lg" class="lyrics-tool__empty">
        <span class="lyrics-tool__empty-icon"><NIcon name="file-music" :size="28" /></span>
        <h2>歌词已经就绪，还差一首音乐</h2>
        <p>请在上方选择本地音乐文件，然后开始逐字打轴。</p>
      </NCard>

      <NCard v-else pad="lg" class="lyrics-tool__empty">
        <span class="lyrics-tool__empty-icon"><NIcon name="music-2" :size="28" /></span>
        <h2>准备好音乐和歌词后开始</h2>
        <p>选择上方音乐并生成歌词行，逐字编辑器会显示在这里。</p>
      </NCard>
    </div>
  </PageShell>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput } from '@/ui'
import { useToast } from '@/composables/useToast'
import { buildNekoDocument, toReportLines } from '@/utils/lyricsReport'
import { isTimingText } from '@/utils/timingUnits'
import { AmbientBackdrop, PageShell } from '@/layouts'
import KaraokeTimingEditor from '@/components/KaraokeTimingEditor.vue'
import LyricsPreviewPanel from '@/components/LyricsPreviewPanel.vue'

const MAX_NEKO_BYTES = 1024 * 1024
const MAX_LRC_BYTES = 200 * 1024
const MAX_LINES = 5000
const AUDIO_FILE_PATTERN = /\.(mp3|flac|wav|m4a|aac|ogg|opus)$/i
const LRC_TIME = /^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,5}))?\]\s*(.*)$/u
const LRC_METADATA = /^\[[a-z]+:[^\]]*\]\s*$/i
const LRC_TRANSLATION = /^\{\s*["'](.*)["']\s*\}\s*$/u

const toast = useToast()
const audioInput = ref(null)
const lyricsImportInput = ref(null)
const audioFile = ref(null)
const audioUrl = ref('')
const audioDragging = ref(false)
const lyricsText = ref('')
const lines = ref([])
const timingRevision = ref(0)
const activeStudioTab = ref('timing')

let lineSequence = 0

const rawLineCount = computed(() => lyricsText.value
  .replace(/\r\n?/g, '\n')
  .split('\n')
  .filter((line) => line.trim())
  .length)

const nekoInputLineCount = computed(() => {
  const text = lyricsText.value.trim()
  if (!text.startsWith('{')) return 0
  try {
    const document = JSON.parse(text)
    return Array.isArray(document?.lines) ? document.lines.length : 0
  } catch {
    return 0
  }
})

const lyricsInputSummary = computed(() => nekoInputLineCount.value
  ? `${nekoInputLineCount.value} 行 neko-v1 歌词`
  : `${rawLineCount.value} 行非空内容`)

const timingStats = computed(() => {
  timingRevision.value
  let totalLines = 0
  let timedLines = 0
  let totalWords = 0
  let timedWords = 0

  for (const line of lines.value) {
    if (!String(line.text || '').trim()) continue
    totalLines += 1
    const words = (Array.isArray(line.words) ? line.words : []).filter((word) => isTimingText(word.text))
    totalWords += words.length
    const wordsTimed = words.length > 0 && words.every((word) => {
      const start = Number(word.startSec)
      const end = Number(word.endSec)
      return Number.isFinite(start) && Number.isFinite(end) && end >= start
    })
    timedWords += words.filter((word) => {
      const start = Number(word.startSec)
      const end = Number(word.endSec)
      return Number.isFinite(start) && Number.isFinite(end) && end >= start
    }).length
    if (wordsTimed && Number(line.endSec) > Number(line.startSec)) {
      timedLines += 1
    }
  }

  return {
    totalLines,
    timedLines,
    totalWords,
    timedWords,
    complete: totalLines > 0 && timedLines === totalLines,
    progress: totalLines > 0 ? timedLines / totalLines : 0,
  }
})

function isAudioFile(file) {
  return Boolean(file) && (String(file.type || '').startsWith('audio/') || AUDIO_FILE_PATTERN.test(file.name || ''))
}

function setAudioFile(file) {
  if (!isAudioFile(file)) {
    toast.error('请选择浏览器支持的音频文件')
    return
  }
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioFile.value = file
  audioUrl.value = URL.createObjectURL(file)
}

function handleAudioChange(event) {
  const file = event.target.files?.[0]
  if (file) setAudioFile(file)
  event.target.value = ''
}

function handleAudioDrop(event) {
  audioDragging.value = false
  const file = event.dataTransfer.files?.[0]
  if (file) setAudioFile(file)
}

async function handleLyricsFileImport(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!/\.(lrc|neko)$/i.test(file.name || '')) {
    toast.error('请选择 .lrc 或 .neko 歌词文件')
    return
  }
  if (/\.neko$/i.test(file.name) && file.size > MAX_NEKO_BYTES) {
    toast.error('NEKO 歌词文件不能超过 1MiB')
    return
  }
  if (/\.lrc$/i.test(file.name) && file.size > MAX_LRC_BYTES) {
    toast.error('LRC 歌词文件不能超过 200KiB')
    return
  }
  try {
    lyricsText.value = await file.text()
    applyLyrics()
  } catch {
    toast.error('读取歌词文件失败')
  }
}

function applyLyrics() {
  const raw = lyricsText.value.trim()
  if (!raw) {
    toast.error('请先粘贴或输入歌词')
    return
  }
  if (lines.value.length && !window.confirm('重新生成会清除当前逐字时间轴，确定继续吗？')) {
    return
  }

  const parsed = parseLyrics(raw)
  if (parsed == null) return
  if (!parsed.length) {
    toast.error('没有识别到有效歌词')
    return
  }
  if (parsed.length > MAX_LINES) {
    toast.error(`歌词不能超过 ${MAX_LINES} 行`)
    return
  }
  lines.value = parsed
  timingRevision.value += 1
  activeStudioTab.value = 'timing'
  toast.success(`已生成 ${parsed.length} 行歌词，开始播放并逐字打轴吧`)
}

function parseLyrics(raw) {
  const text = String(raw || '').trim()
  if (text.startsWith('{')) {
    const rich = parseNekoInput(text)
    if (!rich) return null
    try {
      return toReportLines({ lyricsRich: rich }).map(withUid)
    } catch {
      toast.error('无法读取 neko-v1 歌词内容')
      return null
    }
  }
  if (new Blob([text]).size > MAX_LRC_BYTES) {
    toast.error('LRC 或纯文本歌词不能超过 200KiB')
    return null
  }

  const result = []
  let lastTimedIndex = -1
  const rows = text.replace(/\r\n?/g, '\n').split('\n')

  for (const rawRow of rows) {
    const row = rawRow.trim()
    if (!row || LRC_METADATA.test(row)) continue

    const timed = row.match(LRC_TIME)
    if (timed) {
      const text = timed[4].trim()
      if (!isTimingText(text)) continue
      const startSec = lrcTimestampSeconds(timed)
      const previous = lastTimedIndex >= 0 ? result[lastTimedIndex] : null
      if (previous && previous.startSec === startSec && !previous.translation) {
        previous.translation = text
        continue
      }
      result.push(withUid({
        startSec,
        endSec: 0,
        text,
        translation: '',
        romanization: '',
        words: [],
        timingVersion: 2,
      }))
      lastTimedIndex = result.length - 1
      continue
    }

    const translation = row.match(LRC_TRANSLATION)
    if (translation && lastTimedIndex >= 0) {
      result[lastTimedIndex].translation = translation[1].trim()
      continue
    }

    if (!isTimingText(row)) continue
    result.push(withUid({
      startSec: 0,
      endSec: 0,
      text: row,
      translation: '',
      romanization: '',
      words: [],
      timingVersion: 2,
    }))
    lastTimedIndex = result.length - 1
  }

  result.forEach((line, index) => {
    const next = result[index + 1]
    if (!(Number(line.endSec) > Number(line.startSec))) {
      line.endSec = next && Number(next.startSec) > Number(line.startSec)
        ? Number(next.startSec)
        : Number(line.startSec) + 4
    }
  })
  return result
}

function parseNekoInput(text) {
  if (new Blob([text]).size > MAX_NEKO_BYTES) {
    toast.error('NEKO 歌词不能超过 1MiB')
    return null
  }
  try {
    const document = JSON.parse(text)
    if (!document || document.version !== 1 || !Array.isArray(document.lines)) {
      toast.error('.neko 内容必须是 version 为 1 的 neko-v1 JSON')
      return null
    }
    return document
  } catch {
    toast.error('无法解析 .neko JSON')
    return null
  }
}

function lrcTimestampSeconds(match) {
  const minutes = Number(match[1]) || 0
  const seconds = Number(match[2]) || 0
  const fraction = match[3] || ''
  const millis = fraction ? Number(fraction.padEnd(3, '0').slice(0, 3)) : 0
  return minutes * 60 + seconds + millis / 1000
}

function withUid(line) {
  return {
    ...line,
    uid: ++lineSequence,
    words: Array.isArray(line.words) ? line.words.map((word) => ({ ...word })) : [],
  }
}

function createExport() {
  if (!lines.value.length) {
    toast.error('请先生成歌词行')
    return null
  }
  const stats = timingStats.value
  if (!stats.complete) {
    toast.error(`还有 ${stats.totalLines - stats.timedLines} 行未完成逐字打轴`)
    return null
  }
  const content = JSON.stringify(buildNekoDocument(lines.value), null, 2)
  if (new Blob([content]).size > MAX_NEKO_BYTES) {
    toast.error('歌词内容超过 1MiB，请精简歌词或减少逐字片段')
    return null
  }
  return {
    content,
    fileName: `${safeBaseName(audioFile.value?.name) || 'lyrics'}.neko`,
  }
}

function safeBaseName(fileName) {
  return String(fileName || '')
    .replace(/\.[^.]+$/, '')
    .replace(/[\\/:*?"<>|]+/g, '_')
    .trim()
}

function downloadNeko() {
  const output = createExport()
  if (!output) return
  const blob = new Blob([output.content], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = output.fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  toast.success(`歌词已下载为 ${output.fileName}`)
}

function resetWorkspace() {
  if ((lines.value.length || lyricsText.value) && !window.confirm('确定清空当前歌词和时间轴吗？')) {
    return
  }
  lines.value = []
  lyricsText.value = ''
  timingRevision.value += 1
  activeStudioTab.value = 'timing'
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = ''
  audioFile.value = null
}

onBeforeUnmount(() => {
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
})
</script>

<style scoped>
.lyrics-tool {
  width: min(1560px, 100%);
  margin: 0 auto;
}

.lyrics-tool__hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--n-space-6);
  align-items: center;
  margin-bottom: var(--n-space-6);
  padding: clamp(22px, 4vw, 42px);
  overflow: hidden;
  border: 1px solid rgba(95, 208, 224, 0.24);
  border-radius: var(--n-radius-xl);
  background:
    radial-gradient(circle at 92% 12%, rgba(95, 208, 224, 0.2), transparent 36%),
    linear-gradient(135deg, rgba(9, 25, 29, 0.94), rgba(5, 12, 15, 0.9));
  box-shadow: var(--n-shadow-lg);
}

.lyrics-tool__eyebrow {
  display: inline-block;
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.16em;
}

.lyrics-tool__hero h1 {
  margin: var(--n-space-2) 0 0;
  font-size: clamp(2rem, 5vw, 4rem);
  line-height: 1.05;
  background: var(--n-gradient-text);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.lyrics-tool__hero-copy > p {
  max-width: 780px;
  margin: var(--n-space-4) 0 0;
  color: var(--n-text-muted);
  font-size: clamp(0.95rem, 1.4vw, 1.08rem);
  line-height: 1.75;
}

.lyrics-tool__hero code {
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(95, 208, 224, 0.12);
  color: var(--n-accent-strong);
}

.lyrics-tool__privacy {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-2) var(--n-space-4);
  margin-top: var(--n-space-5);
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

.lyrics-tool__privacy span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.lyrics-tool__format-card {
  display: grid;
  place-items: center;
  min-width: 190px;
  aspect-ratio: 1;
  padding: var(--n-space-5);
  border: 1px solid rgba(95, 208, 224, 0.22);
  border-radius: 28px;
  background: rgba(2, 9, 12, 0.54);
  box-shadow: inset 0 0 48px rgba(95, 208, 224, 0.08);
  text-align: center;
}

.lyrics-tool__format-card span,
.lyrics-tool__format-card small {
  color: var(--n-text-faint);
  font-size: 11px;
  letter-spacing: 0.12em;
}

.lyrics-tool__format-card strong {
  color: var(--n-accent-strong);
  font-size: 2rem;
  letter-spacing: -0.04em;
}

.lyrics-tool__setup {
  display: grid;
  grid-template-columns: minmax(280px, 0.82fr) minmax(0, 1.18fr);
  gap: var(--n-space-4);
}

.lyrics-tool__card,
.lyrics-tool__studio {
  border-color: var(--n-line);
  background: rgba(8, 18, 22, 0.72);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
}

.lyrics-tool__step {
  display: flex;
  gap: var(--n-space-3);
  align-items: flex-start;
  margin-bottom: var(--n-space-4);
}

.lyrics-tool__step-no {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border: 1px solid rgba(95, 208, 224, 0.28);
  border-radius: 11px;
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-bold);
}

.lyrics-tool__step h2,
.lyrics-tool__studio-head h2,
.lyrics-tool__empty h2 {
  margin: 0;
  color: var(--n-text);
  font-size: var(--n-text-lg);
}

.lyrics-tool__step p,
.lyrics-tool__studio-head p,
.lyrics-tool__empty p {
  margin: 5px 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
  line-height: 1.65;
}

.lyrics-tool__hidden-input {
  display: none;
}

.lyrics-tool__audio-drop {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  width: 100%;
  min-height: 126px;
  padding: var(--n-space-4);
  border: 1px dashed rgba(95, 208, 224, 0.3);
  border-radius: var(--n-radius-lg);
  background: rgba(95, 208, 224, 0.035);
  color: var(--n-text);
  text-align: left;
  transition:
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease),
    transform var(--n-duration-fast) var(--n-ease);
}

.lyrics-tool__audio-drop--over,
.lyrics-tool__audio-drop--ready {
  border-color: var(--n-accent);
  background: rgba(95, 208, 224, 0.09);
}

.lyrics-tool__audio-drop--over {
  transform: scale(1.01);
}

.lyrics-tool__audio-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  flex: none;
  border-radius: 15px;
  color: var(--n-accent-strong);
  background: rgba(95, 208, 224, 0.11);
}

.lyrics-tool__audio-text {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 5px;
}

.lyrics-tool__audio-text strong,
.lyrics-tool__audio-text small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lyrics-tool__audio-text strong {
  font-size: var(--n-text-sm);
}

.lyrics-tool__audio-text small {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lyrics-tool__audio-arrow {
  color: var(--n-text-faint);
}

.lyrics-tool__textarea {
  width: 100%;
}

.lyrics-tool__lyrics-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  margin-top: var(--n-space-3);
}

.lyrics-tool__lyrics-actions > span {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lyrics-tool__lyrics-buttons {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--n-space-2);
}

.lyrics-tool__studio {
  margin-top: var(--n-space-4);
}

.lyrics-tool__studio-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-3);
}

.lyrics-tool__stats {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--n-space-2);
}

.lyrics-tool__stats span {
  padding: 6px 10px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-pill);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
}

.lyrics-tool__progress {
  height: 4px;
  margin-bottom: var(--n-space-4);
  overflow: hidden;
  border-radius: var(--n-radius-pill);
  background: rgba(255, 255, 255, 0.06);
}

.lyrics-tool__progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--n-accent), var(--n-accent-strong));
  transition: width var(--n-duration) var(--n-ease);
}

.lyrics-tool__modes {
  display: inline-flex;
  gap: 4px;
  margin-bottom: var(--n-space-4);
  padding: 4px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
}

.lyrics-tool__modes button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: calc(var(--n-radius-control) - 3px);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

.lyrics-tool__modes button:hover {
  color: var(--n-text);
}

.lyrics-tool__modes .lyrics-tool__mode--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.lyrics-tool__export {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-5);
  margin-top: var(--n-space-5);
  padding-top: var(--n-space-4);
  border-top: 1px solid var(--n-line-subtle);
}

.lyrics-tool__export-state {
  display: flex;
  align-items: flex-start;
  gap: var(--n-space-3);
  min-width: 0;
  color: var(--n-accent-strong);
}

.lyrics-tool__export-state strong {
  display: block;
  color: var(--n-text);
  font-size: var(--n-text-sm);
}

.lyrics-tool__export-state p {
  margin: 4px 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lyrics-tool__export-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--n-space-2);
}

.lyrics-tool__empty {
  display: grid;
  place-items: center;
  gap: var(--n-space-2);
  margin-top: var(--n-space-4);
  text-align: center;
}

.lyrics-tool__empty-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: var(--n-space-2);
  border-radius: 18px;
  color: var(--n-accent-strong);
  background: rgba(95, 208, 224, 0.1);
}

@media (hover: hover) {
  .lyrics-tool__audio-drop:hover {
    border-color: var(--n-accent);
    background: rgba(95, 208, 224, 0.075);
  }
}

@media (max-width: 900px) {
  .lyrics-tool__hero {
    grid-template-columns: 1fr;
  }

  .lyrics-tool__format-card {
    display: none;
  }

  .lyrics-tool__setup {
    grid-template-columns: 1fr;
  }

  .lyrics-tool__studio-head,
  .lyrics-tool__export {
    align-items: stretch;
    flex-direction: column;
  }

  .lyrics-tool__stats,
  .lyrics-tool__export-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 560px) {
  .lyrics-tool__hero {
    padding: var(--n-space-5);
    border-radius: var(--n-radius-lg);
  }

  .lyrics-tool__privacy {
    display: grid;
  }

  .lyrics-tool__lyrics-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .lyrics-tool__lyrics-buttons {
    justify-content: flex-start;
  }

  .lyrics-tool__export-actions :deep(.n-btn) {
    flex: 1;
  }
}
</style>

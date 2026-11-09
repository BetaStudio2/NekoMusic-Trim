<template>
  <div class="karaoke">
    <div class="karaoke__player">
      <button
        type="button"
        class="karaoke__play"
        :aria-label="isPlaying ? '暂停' : '播放'"
        :disabled="audioLoading || !audioUrl || audioError"
        @click="togglePlay"
      >
        <NIcon :name="isPlaying ? 'pause' : 'play'" :size="20" />
      </button>
      <div class="karaoke__timeline">
        <template v-if="audioLoading">
          <p class="karaoke__status">正在加载音乐…</p>
        </template>
        <template v-else-if="audioError || !audioUrl">
          <p class="karaoke__status karaoke__status--error">音乐加载失败，请刷新页面重试</p>
        </template>
        <template v-else>
          <input
            :value="currentTime"
            type="range"
            min="0"
            :max="duration || 0"
            step="0.01"
            :disabled="!duration"
            aria-label="播放进度"
            @input="seek"
          />
        </template>
        <div class="karaoke__time">
          <span>{{ formatTime(currentTime) }}</span>
          <span>{{ formatTime(duration) }}</span>
        </div>
      </div>
      <label class="karaoke__speed">
        <span>倍速</span>
        <select v-model.number="playbackRate" @change="applyPlaybackRate">
          <option :value="0.5">0.5×</option>
          <option :value="0.75">0.75×</option>
          <option :value="1">1×</option>
          <option :value="1.25">1.25×</option>
          <option :value="1.5">1.5×</option>
          <option :value="2">2×</option>
        </select>
      </label>
    </div>

    <audio
      ref="audioRef"
      :src="audioUrl"
      preload="metadata"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @play="isPlaying = true"
      @pause="isPlaying = false"
      @ended="isPlaying = false"
      @error="audioError = true"
    />

    <div class="karaoke__guide">
      <div>
        <strong>听着歌，看到目标字出现时点按钮。</strong>
        <p>每次点击记录当前字/词的时间；最后一个字点完后，再点一次结束本行并自动进入下一行。</p>
      </div>
      <span class="karaoke__counter">
        {{ activeLineIndex + 1 }} / {{ lines.length }}
      </span>
    </div>

    <div ref="scrollRef" class="karaoke__lyrics" aria-live="polite">
      <button
        v-for="(line, lineIndex) in lines"
        :key="line.uid || lineIndex"
        type="button"
        class="karaoke-line"
        :class="{
          'karaoke-line--active': lineIndex === activeLineIndex,
          'karaoke-line--past': lineIndex < activeLineIndex,
        }"
        @click="selectLine(lineIndex)"
      >
        <span class="karaoke-line__time">{{ formatTime(line.startSec) }}</span>
        <span class="karaoke-line__text">
          <span
            v-for="(word, wordIndex) in line.words"
            :key="wordIndex"
            class="karaoke-segment"
            :class="segmentClass(line, lineIndex, word, wordIndex)"
          >{{ word.text }}</span>
        </span>
      </button>
    </div>

    <div class="karaoke__tap-panel">
      <div class="karaoke__target">
        <span>下一个目标</span>
        <strong>{{ targetLabel }}</strong>
      </div>
      <button
        type="button"
        class="karaoke__tap"
        :class="{
          'karaoke__tap--finish': cursor >= currentWords.length,
          'karaoke__tap--playing': isPlaying,
          'karaoke__tap--hit': tapPulse,
        }"
        :disabled="!isPlaying || finished || !currentLine"
        @click="tap"
      >
        <NIcon name="music-2" :size="24" />
        <span>{{ tapLabel }}</span>
      </button>
      <div class="karaoke__actions">
        <NButton size="sm" variant="ghost" icon="chevron-left" @click="previousLine">
          上一行
        </NButton>
        <NButton size="sm" variant="ghost" icon="refresh" @click="resplitLine">
          重新拆分本行
        </NButton>
        <NButton size="sm" variant="ghost" iconAfter="chevron-right" @click="nextLine">
          下一行
        </NButton>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton } from '@/ui'

const props = defineProps({
  lines: { type: Array, required: true },
  audioUrl: { type: String, required: true },
  audioLoading: { type: Boolean, default: false },
})

const emit = defineEmits(['change'])

const audioRef = ref(null)
const scrollRef = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const playbackRate = ref(1)
const audioError = ref(false)
const activeLineIndex = ref(0)
const cursor = ref(0)
const finished = ref(false)
const tapPulse = ref(false)

const currentLine = computed(() => props.lines[activeLineIndex.value] || null)
const currentWords = computed(() => currentLine.value?.words || [])
const targetLabel = computed(() => {
  if (finished.value) return '全部完成'
  if (!currentWords.value.length) return '请先重新拆分本行'
  if (cursor.value >= currentWords.value.length) return '结束本行'
  return `「${currentWords.value[cursor.value]?.text || ''}」`
})
const tapLabel = computed(() => {
  if (finished.value) return '已完成'
  if (!isPlaying.value) return '先播放音乐'
  if (cursor.value >= currentWords.value.length) return '结束本行'
  return '点我记录'
})

onMounted(() => {
  props.lines.forEach(ensureSegments)
  const firstIncomplete = props.lines.findIndex((line) =>
    !line.words.length || line.words.some((word) => !Number.isFinite(Number(word.startSec))))
  selectLine(firstIncomplete >= 0 ? firstIncomplete : 0)
})

watch(() => props.lines.length, () => {
  if (activeLineIndex.value >= props.lines.length) {
    activeLineIndex.value = Math.max(0, props.lines.length - 1)
  }
  const line = props.lines[activeLineIndex.value]
  ensureSegments(line)
  cursor.value = 0
  finished.value = false
})

watch(() => props.audioUrl, () => {
  audioError.value = false
  currentTime.value = 0
  duration.value = 0
})

function ensureSegments(line) {
  if (!line) return
  if (!Array.isArray(line.words) || line.words.length === 0) {
    line.words = splitGraphemes(line.text || '').map((text) => ({
      text,
      startSec: null,
      endSec: null,
    }))
  }
}

function splitGraphemes(text) {
  const value = String(text || '')
  if (!value) return []
  try {
    if (typeof Intl !== 'undefined' && Intl.Segmenter) {
      return [...new Intl.Segmenter('ja', { granularity: 'grapheme' }).segment(value)]
        .map((item) => item.segment)
        .filter((item) => item.trim())
    }
  } catch {
    // fall through
  }
  return Array.from(value).filter((item) => item.trim())
}

function togglePlay() {
  const audio = audioRef.value
  if (!audio || !props.audioUrl || props.audioLoading || audioError.value) return
  if (audio.paused) {
    audio.play().catch(() => {
      isPlaying.value = false
    })
  } else {
    audio.pause()
  }
}

function onTimeUpdate() {
  currentTime.value = Number(audioRef.value?.currentTime) || 0
}

function onLoadedMetadata() {
  duration.value = Number(audioRef.value?.duration) || 0
  applyPlaybackRate()
}

function applyPlaybackRate() {
  if (audioRef.value) {
    audioRef.value.playbackRate = playbackRate.value
  }
}

function seek(event) {
  const value = Number(event.target.value)
  if (!Number.isFinite(value) || !audioRef.value) return
  audioRef.value.currentTime = Math.max(0, value)
  currentTime.value = audioRef.value.currentTime
}

function selectLine(index) {
  if (!props.lines.length) return
  const next = Math.max(0, Math.min(props.lines.length - 1, index))
  activeLineIndex.value = next
  const line = props.lines[next]
  ensureSegments(line)
  cursor.value = 0
  finished.value = false
  nextTick(() => {
    const active = scrollRef.value?.querySelector('.karaoke-line--active')
    active?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

function tap() {
  const line = currentLine.value
  if (!line || !isPlaying.value || finished.value) return
  tapPulse.value = true
  window.setTimeout(() => {
    tapPulse.value = false
  }, 140)
  const now = Number(audioRef.value?.currentTime) || 0
  const words = line.words
  if (!words.length) return
  if (cursor.value < words.length) {
    if (cursor.value === 0) {
      line.startSec = now
    } else {
      words[cursor.value - 1].endSec = now
    }
    words[cursor.value].startSec = now
    cursor.value++
  } else {
    const last = words[words.length - 1]
    last.endSec = now
    line.endSec = now
    if (activeLineIndex.value + 1 < props.lines.length) {
      selectLine(activeLineIndex.value + 1)
    } else {
      finished.value = true
    }
  }
  emit('change')
}

function previousLine() {
  selectLine(activeLineIndex.value - 1)
}

function nextLine() {
  selectLine(activeLineIndex.value + 1)
}

function resplitLine() {
  const line = currentLine.value
  if (!line) return
  line.words = splitGraphemes(line.text || '').map((text) => ({
    text,
    startSec: null,
    endSec: null,
  }))
  cursor.value = 0
  finished.value = false
  emit('change')
}

function segmentClass(line, lineIndex, word, wordIndex) {
  if (lineIndex !== activeLineIndex.value) {
    return { 'karaoke-segment--timed': Number.isFinite(Number(word.startSec)) }
  }
  return {
    'karaoke-segment--target': wordIndex === cursor.value,
    'karaoke-segment--played': Number.isFinite(Number(word.startSec))
      && currentTime.value >= Number(word.startSec),
    'karaoke-segment--timed': Number.isFinite(Number(word.startSec)),
  }
}

function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}
</script>

<style scoped>
.karaoke {
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr) minmax(220px, 280px);
  grid-template-areas:
    "player lyrics tap"
    "guide lyrics tap";
  align-items: start;
  gap: var(--n-space-4);
}

.karaoke__player {
  grid-area: player;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: var(--n-space-3);
  padding: var(--n-space-3) var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface);
}

.karaoke__play {
  display: grid;
  place-items: center;
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--n-gradient-accent);
  color: var(--n-text);
}

.karaoke__timeline {
  flex: 1;
  min-width: 0;
}

.karaoke__status {
  margin: 0 0 4px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.karaoke__status--error {
  color: var(--n-danger);
}

.karaoke__timeline input {
  width: 100%;
  accent-color: var(--n-accent);
}

.karaoke__time {
  display: flex;
  justify-content: space-between;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.karaoke__speed {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.karaoke__speed select {
  height: 32px;
  min-width: 84px;
  padding: 0 10px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
}

.karaoke__guide {
  grid-area: guide;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--n-space-4);
  padding: 12px 14px;
  border-radius: var(--n-radius-control);
  background: var(--n-accent-soft);
}

.karaoke__guide p {
  margin: 4px 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.karaoke__counter {
  flex: none;
  color: var(--n-accent-strong);
  font-size: var(--n-text-sm);
}

.karaoke__lyrics {
  grid-area: lyrics;
  display: grid;
  gap: 8px;
  max-height: min(62vh, 650px);
  overflow-y: auto;
  padding: var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: rgba(0, 0, 0, 0.18);
  scroll-behavior: smooth;
}

.karaoke-line {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  gap: var(--n-space-3);
  width: 100%;
  padding: 10px 12px;
  border-radius: var(--n-radius-control);
  color: var(--n-text-faint);
  text-align: left;
  transition: color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

.karaoke-line--active {
  background: var(--n-accent-soft);
  color: var(--n-text);
}

.karaoke-line--past {
  opacity: 0.55;
}

.karaoke-line__time {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.karaoke-line__text {
  font-size: clamp(18px, 2.2vw, 26px);
  line-height: 1.55;
  word-break: break-word;
}

.karaoke-segment {
  transition: color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

.karaoke-segment--timed {
  color: var(--n-text-muted);
}

.karaoke-segment--played {
  color: var(--n-accent-strong);
}

.karaoke-segment--target {
  border-radius: 5px;
  background: var(--n-accent);
  color: var(--n-text-inverse);
}

.karaoke__tap-panel {
  grid-area: tap;
  display: grid;
  justify-items: center;
  gap: var(--n-space-3);
  padding: var(--n-space-5);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface);
}

.karaoke__target {
  display: grid;
  justify-items: center;
  gap: 4px;
}

.karaoke__target span {
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.karaoke__target strong {
  color: var(--n-accent-strong);
  font-size: clamp(28px, 5vw, 44px);
}

.karaoke__tap {
  display: grid;
  place-items: center;
  gap: 6px;
  width: 128px;
  height: 128px;
  border-radius: 50%;
  border: 1px solid var(--n-accent-line);
  background: var(--n-gradient-accent);
  color: var(--n-text);
  box-shadow: 0 10px 30px rgba(95, 208, 224, 0.28);
  font-weight: var(--n-weight-semibold);
  touch-action: manipulation;
}

.karaoke__tap:active {
  transform: scale(0.96);
}

.karaoke__tap:disabled {
  opacity: 0.45;
  box-shadow: none;
}

.karaoke__tap--finish {
  background: var(--n-surface-strong);
}

.karaoke__tap--playing {
  animation: karaoke-pulse 1.8s ease-in-out infinite;
}

.karaoke__tap--hit {
  animation: karaoke-hit 140ms ease-out;
}

@keyframes karaoke-pulse {
  0%, 100% { box-shadow: 0 10px 30px rgba(95, 208, 224, 0.28); }
  50% { box-shadow: 0 10px 46px rgba(95, 208, 224, 0.48); }
}

@keyframes karaoke-hit {
  from { transform: scale(0.9); }
  to { transform: scale(1); }
}

.karaoke__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--n-space-2);
}

@media (max-width: 1000px) {
  .karaoke {
    grid-template-columns: minmax(0, 1fr) minmax(200px, 260px);
    grid-template-areas:
      "lyrics tap"
      "player tap"
      "guide tap";
  }
}

@media (max-width: 720px) {
  .karaoke {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      "player"
      "guide"
      "lyrics"
      "tap";
  }

  .karaoke__guide {
    flex-direction: column;
  }

  .karaoke-line {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .karaoke__tap {
    width: 112px;
    height: 112px;
  }
}
</style>

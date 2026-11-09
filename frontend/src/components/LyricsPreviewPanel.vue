<template>
  <div class="preview">
    <aside class="preview__side">
      <div class="preview__player">
        <button
          type="button"
          class="preview__play"
          :aria-label="isPlaying ? '暂停' : '播放'"
          :disabled="audioLoading || !audioUrl || audioError"
          @click="togglePlay"
        >
          <NIcon :name="isPlaying ? 'pause' : 'play'" :size="22" />
        </button>
        <div class="preview__timeline">
          <p v-if="audioLoading" class="preview__status">正在加载音乐…</p>
          <p v-else-if="audioError || !audioUrl" class="preview__status preview__status--error">
            音乐加载失败，请刷新页面重试
          </p>
          <input
            v-else
            :value="currentTime"
            type="range"
            min="0"
            :max="duration || 0"
            step="0.01"
            aria-label="播放进度"
            @input="seek"
          />
          <div class="preview__time">
            <span>{{ formatTime(currentTime) }}</span>
            <span>{{ formatTime(duration) }}</span>
          </div>
        </div>
        <label class="preview__speed">
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

      <div class="preview__hint">
        <NIcon name="eye" :size="17" />
        <p>这是提交后的歌词效果预览。播放音乐时当前行会自动跟随，点击任意歌词行可跳转。</p>
      </div>
    </aside>

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

    <section ref="scrollRef" class="preview__stage" aria-live="polite">
      <article
        v-for="(line, index) in lines"
        :key="line.uid || index"
        class="preview-line"
        :class="{
          'preview-line--active': index === activeLineIndex,
          'preview-line--past': index < activeLineIndex,
        }"
        @click="seekToLine(line)"
      >
        <span class="preview-line__time">{{ formatTime(line.startSec) }}</span>
        <div class="preview-line__body">
          <p class="preview-line__text">
            <span
              v-for="(segment, segmentIndex) in segmentsFor(line)"
              :key="segmentIndex"
              :class="{ 'preview-line__segment--active': segmentActive(segment, index) }"
            >{{ segment.text }}</span>
          </p>
          <p v-if="line.translation" class="preview-line__translation">
            {{ line.translation }}
          </p>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'

const props = defineProps({
  lines: { type: Array, required: true },
  audioUrl: { type: String, required: true },
  audioLoading: { type: Boolean, default: false },
})

const audioRef = ref(null)
const scrollRef = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const playbackRate = ref(1)
const audioError = ref(false)

const activeLineIndex = computed(() => {
  if (!props.lines.length) return -1
  let active = 0
  for (let index = 0; index < props.lines.length; index++) {
    const start = Number(props.lines[index].startSec)
    if (!Number.isFinite(start)) continue
    if (currentTime.value + 0.02 >= start) {
      active = index
    } else {
      break
    }
  }
  return active
})

watch(activeLineIndex, () => {
  nextTick(() => {
    const line = scrollRef.value?.querySelector('.preview-line--active')
    line?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
})

watch(() => props.audioUrl, () => {
  audioError.value = false
  currentTime.value = 0
  duration.value = 0
})

function segmentsFor(line) {
  const words = Array.isArray(line.words) ? line.words : []
  if (words.length && words.map((word) => word.text || '').join('') === String(line.text || '')) {
    return words
  }
  return line.text ? [{ text: line.text, startSec: line.startSec }] : []
}

function segmentActive(segment, lineIndex) {
  if (lineIndex !== activeLineIndex.value) return false
  const start = Number(segment.startSec)
  return Number.isFinite(start) && currentTime.value + 0.02 >= start
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

function seekToLine(line) {
  if (!audioRef.value) return
  const start = Math.max(0, Number(line.startSec) || 0)
  audioRef.value.currentTime = start
  currentTime.value = start
}

function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}
</script>

<style scoped>
.preview {
  display: grid;
  grid-template-columns: minmax(230px, 300px) minmax(0, 1fr);
  gap: var(--n-space-4);
  align-items: start;
}

.preview__side {
  display: grid;
  gap: var(--n-space-3);
}

.preview__player {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--n-space-3);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface);
}

.preview__play {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--n-gradient-accent);
  color: var(--n-text);
}

.preview__play:disabled {
  opacity: 0.45;
}

.preview__timeline {
  min-width: 0;
}

.preview__timeline input {
  width: 100%;
  accent-color: var(--n-accent);
}

.preview__time {
  display: flex;
  justify-content: space-between;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.preview__status {
  margin: 0 0 4px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.preview__status--error {
  color: var(--n-danger);
}

.preview__speed {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.preview__speed select {
  height: 32px;
  min-width: 84px;
  padding: 0 10px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
}

.preview__hint {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
  border-radius: var(--n-radius-control);
  background: var(--n-accent-soft);
  color: var(--n-text-muted);
}

.preview__hint p {
  margin: 0;
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.preview__stage {
  display: grid;
  gap: 4px;
  max-height: min(72vh, 760px);
  overflow-y: auto;
  padding: var(--n-space-5);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: rgba(0, 0, 0, 0.18);
  scroll-behavior: smooth;
}

.preview-line {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  gap: var(--n-space-4);
  padding: 12px 14px;
  border-radius: var(--n-radius-control);
  color: var(--n-text-faint);
  cursor: pointer;
  transition: color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease),
    transform var(--n-duration-fast) var(--n-ease);
}

.preview-line--active {
  background: var(--n-accent-soft);
  color: var(--n-text);
  transform: translateX(4px);
}

.preview-line--past {
  opacity: 0.55;
}

.preview-line__time {
  padding-top: 8px;
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.preview-line__text {
  margin: 0;
  color: inherit;
  font-size: clamp(20px, 2.3vw, 30px);
  line-height: 1.55;
}

.preview-line__segment--active {
  color: var(--n-accent-strong);
  text-shadow: 0 0 18px rgba(95, 208, 224, 0.28);
}

.preview-line__translation {
  margin: 6px 0 0;
  color: var(--n-text-muted);
  font-size: clamp(13px, 1.2vw, 16px);
}

@media (max-width: 900px) {
  .preview {
    grid-template-columns: minmax(0, 1fr);
  }

  .preview__stage {
    max-height: 60vh;
  }
}

@media (max-width: 560px) {
  .preview-line {
    grid-template-columns: 42px minmax(0, 1fr);
    gap: var(--n-space-2);
  }
}
</style>

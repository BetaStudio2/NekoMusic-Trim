<template>
  <div class="review">
    <div class="review__player">
      <button
        type="button"
        class="review__play"
        :aria-label="isPlaying ? '暂停' : '播放'"
        :disabled="!audioUrl || audioError"
        @click="togglePlay"
      >
        <NIcon :name="isPlaying ? 'pause' : 'play'" :size="20" />
      </button>
      <div class="review__timeline">
        <p v-if="!audioUrl || audioError" class="review__status">音乐暂不可播放</p>
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
        <div class="review__time">
          <span>{{ formatTime(currentTime) }}</span>
          <span>{{ formatTime(duration) }}</span>
        </div>
      </div>
      <label class="review__speed">
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

    <div class="review__columns">
      <section class="review__column">
        <h3>提交版本</h3>
        <LyricsWall
          class="review__wall"
          :lines="submittedAmll"
          :current-time="currentTime"
          :playing="isPlaying"
          @seek="seekToSeconds"
        />
      </section>

      <section class="review__column">
        <h3>当前正式歌词</h3>
        <LyricsWall
          v-if="currentLines.length"
          class="review__wall"
          :lines="currentAmll"
          :current-time="currentTime"
          :playing="isPlaying"
          @seek="seekToSeconds"
        />
        <p v-else class="review__empty">当前没有可对比的正式歌词</p>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import LyricsWall from '@/components/LyricsWall.vue'

const props = defineProps({
  submittedLines: { type: Array, required: true },
  currentLines: { type: Array, required: true },
  audioUrl: { type: String, default: '' },
})

const audioRef = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const playbackRate = ref(1)
const audioError = ref(false)

const submittedAmll = computed(() => toAmllLines(props.submittedLines))
const currentAmll = computed(() => toAmllLines(props.currentLines))
watch(() => props.audioUrl, () => {
  audioError.value = false
  currentTime.value = 0
  duration.value = 0
})

function toAmllLines(lines) {
  return (Array.isArray(lines) ? lines : []).map((line, index, source) => {
    const time = lineStartMs(line) / 1000
    const next = source[index + 1]
    const endTime = Number(line?.endMs) > 0
      ? Number(line.endMs) / 1000
      : (next ? lineStartMs(next) / 1000 : time + 8)
    const words = Array.isArray(line?.words) && line.words.length
      ? line.words
          .filter((word) => word && typeof word.text === 'string')
          .map((word) => ({
            text: word.text,
            startTime: Math.max(0, Number(word.startMs) || 0) / 1000,
            endTime: Math.max(0, Number(word.endMs) || 0) / 1000,
          }))
      : []
    return {
      time,
      endTime,
      text: typeof line?.text === 'string' ? line.text : '',
      translation: typeof line?.translation === 'string' ? line.translation : '',
      romanization: typeof line?.romanization === 'string' ? line.romanization : '',
      words,
    }
  })
}

function lineStartMs(line) {
  const raw = Number(line?.startMs)
  if (Number.isFinite(raw)) return Math.max(0, raw)
  const seconds = Number(line?.startSec)
  return Number.isFinite(seconds) ? Math.max(0, seconds * 1000) : 0
}

function togglePlay() {
  const audio = audioRef.value
  if (!audio || !props.audioUrl || audioError.value) return
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

function seekToSeconds(seconds) {
  if (!audioRef.value) return
  const start = Math.max(0, Number(seconds) || 0)
  audioRef.value.currentTime = Math.max(0, start)
  currentTime.value = audioRef.value.currentTime
}

function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0))
  const minutes = Math.floor(total / 60)
  const rest = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
}
</script>

<style scoped>
.review {
  display: grid;
  gap: var(--n-space-3);
}

.review__player {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--n-space-3);
  padding: 10px 12px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: rgba(0, 0, 0, 0.14);
}

.review__play {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: var(--n-gradient-accent);
  color: var(--n-text);
}

.review__play:disabled {
  opacity: 0.45;
}

.review__timeline {
  min-width: 0;
}

.review__timeline input {
  width: 100%;
  accent-color: var(--n-accent);
}

.review__time {
  display: flex;
  justify-content: space-between;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.review__status {
  margin: 0 0 4px;
  color: var(--n-danger);
  font-size: var(--n-text-sm);
}

.review__speed {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
}

.review__speed select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
}

.review__columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--n-space-4);
}

.review__column {
  min-width: 0;
}

.review__column h3 {
  margin: 0 0 8px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.review__wall {
  height: 430px;
  min-height: 320px;
}

.review__lyrics {
  display: grid;
  gap: 4px;
  max-height: 430px;
  overflow-y: auto;
  padding: 10px;
  border: 1px solid var(--n-line-subtle);
  border-radius: var(--n-radius-control);
  background: rgba(0, 0, 0, 0.14);
  scroll-behavior: smooth;
}

.review-line {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr);
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  color: var(--n-text-faint);
  cursor: pointer;
  transition: color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

.review-line--active {
  background: var(--n-accent-soft);
  color: var(--n-text);
}

.review-line--past {
  opacity: 0.55;
}

.review-line__time {
  padding-top: 5px;
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.review-line__text {
  margin: 0;
  font-size: 18px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.review-word--active {
  color: var(--n-accent-strong);
  text-shadow: 0 0 14px rgba(95, 208, 224, 0.24);
}

.review-line__romanization {
  margin: 3px 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.review-line__translation {
  margin: 4px 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.review__empty {
  margin: 0;
  padding: 20px;
  color: var(--n-text-faint);
  text-align: center;
}

@media (max-width: 760px) {
  .review__player {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .review__speed {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .review__columns {
    grid-template-columns: 1fr;
  }
}
</style>

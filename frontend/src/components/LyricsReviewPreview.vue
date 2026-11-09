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
        <div ref="submittedScrollRef" class="review__lyrics">
          <article
            v-for="(line, index) in submittedLines"
            :key="index"
            class="review-line"
            :class="{
              'review-line--active': index === submittedActive,
              'review-line--past': index < submittedActive,
            }"
            @click="seekToLine(line)"
          >
            <span class="review-line__time">{{ formatTime(line.startMs / 1000) }}</span>
            <div class="review-line__main">
              <p class="review-line__text">
                <span
                  v-for="(word, wordIndex) in segmentsFor(line)"
                  :key="wordIndex"
                  :class="{ 'review-word--active': wordActive(word, index === submittedActive) }"
                >{{ wordText(word) }}</span>
              </p>
              <p v-if="line.romanization" class="review-line__romanization">
                {{ line.romanization }}
              </p>
              <p v-if="line.translation" class="review-line__translation">
                {{ line.translation }}
              </p>
            </div>
          </article>
        </div>
      </section>

      <section class="review__column">
        <h3>当前正式歌词</h3>
        <div ref="currentScrollRef" class="review__lyrics">
          <article
            v-for="(line, index) in currentLines"
            :key="index"
            class="review-line"
            :class="{
              'review-line--active': index === currentActive,
              'review-line--past': index < currentActive,
            }"
            @click="seekToLine(line)"
          >
            <span class="review-line__time">{{ formatTime(line.startMs / 1000) }}</span>
            <div class="review-line__main">
              <p class="review-line__text">
                <span
                  v-for="(word, wordIndex) in segmentsFor(line)"
                  :key="wordIndex"
                  :class="{ 'review-word--active': wordActive(word, index === currentActive) }"
                >{{ wordText(word) }}</span>
              </p>
              <p v-if="line.romanization" class="review-line__romanization">
                {{ line.romanization }}
              </p>
              <p v-if="line.translation" class="review-line__translation">
                {{ line.translation }}
              </p>
            </div>
          </article>
          <p v-if="!currentLines.length" class="review__empty">当前没有可对比的正式歌词</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { comparableLyricsText } from '@/utils/timingUnits'

const props = defineProps({
  submittedLines: { type: Array, required: true },
  currentLines: { type: Array, required: true },
  audioUrl: { type: String, default: '' },
})

const audioRef = ref(null)
const submittedScrollRef = ref(null)
const currentScrollRef = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const playbackRate = ref(1)
const audioError = ref(false)

const currentMs = computed(() => currentTime.value * 1000)
const submittedActive = computed(() => findActive(props.submittedLines))
const currentActive = computed(() => findActive(props.currentLines))

watch(submittedActive, () => scrollActive(submittedScrollRef.value))
watch(currentActive, () => scrollActive(currentScrollRef.value))
watch(() => props.audioUrl, () => {
  audioError.value = false
  currentTime.value = 0
  duration.value = 0
})

function findActive(lines) {
  if (!lines?.length) return -1
  let active = 0
  for (let index = 0; index < lines.length; index++) {
    const start = lineStartMs(lines[index])
    if (currentMs.value + 20 >= start) {
      active = index
    } else {
      break
    }
  }
  return active
}

function segmentsFor(line) {
  const words = Array.isArray(line.words) ? line.words : []
  if (words.length
    && words.every((word) => word && typeof word.text === 'string')
    && comparableLyricsText(words.map((word) => word.text).join(''))
      === comparableLyricsText(line.text)) {
    return words
  }
  return line.text ? [{ text: line.text, startMs: lineStartMs(line) }] : []
}

function wordText(word) {
  return word && typeof word.text === 'string' ? word.text : ''
}

function wordActive(word, lineActive) {
  if (!lineActive) return false
  const start = Number(word.startMs)
  return Number.isFinite(start) && currentMs.value + 20 >= start
}

function lineStartMs(line) {
  const raw = Number(line?.startMs)
  if (Number.isFinite(raw)) return Math.max(0, raw)
  const seconds = Number(line?.startSec)
  return Number.isFinite(seconds) ? Math.max(0, seconds * 1000) : 0
}

function scrollActive(container) {
  nextTick(() => {
    const line = container?.querySelector('.review-line--active')
    line?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
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

function seekToLine(line) {
  if (!audioRef.value) return
  const start = lineStartMs(line) / 1000
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

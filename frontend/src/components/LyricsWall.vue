<script setup>
/**
 * LyricsWall —— AMLL 歌词墙
 * ------------------------------------------------------------
 * 使用官方 @applemusic-like-lyrics/vue 的 LyricPlayer（Apple Music 风格
 * 逐行滚动 + 弹簧动效 + 逐字高亮）。
 *
 * 数据衔接：
 *   本项目歌词由后端返回 LRC 文本，PlayerView 已解析为
 *   [{ time(秒), text, translation }]，这里转换成 AMLL 的 LyricLine[]
 *   （毫秒、words[]、translatedLyric）。
 *
 * 说明：不依赖 @applemusic-like-lyrics/lyric 的格式解析，避免猜测我们
 * 自定义的双语 `{"翻译"}` 约定；直接手工构造 LyricLine 更可控。
 */
import { computed } from 'vue'
import { LyricPlayer } from '@applemusic-like-lyrics/vue'
import '@applemusic-like-lyrics/core/style.css'
import NIcon from '@/icons/NIcon.vue'

const props = defineProps({
  /** PlayerView 解析结果：{ time(秒), text, translation } */
  lines: { type: Array, default: () => [] },
  /** 当前播放时间（秒，可含小数） */
  currentTime: { type: Number, default: 0 },
  /** 是否正在播放 */
  playing: { type: Boolean, default: false },
  /** 没有最后一行结束时间时的兜底时长（毫秒） */
  tailMs: { type: Number, default: 8000 },
})

/** 点击某行歌词 → emit('seek', 秒) */
const emit = defineEmits(['seek'])

/**
 * 点击歌词行跳转到该行起点。
 *
 * AMLL 的 LyricPlayer 在 click 时派发 `line-click`（vue 包装层转成
 * `lineClick`），载荷是 LyricLineMouseEvent：{ lineIndex, line, bgLine }。
 * line.startTime 是毫秒。这里对「数字」与「MediaTime 包装」两种形态都兜一下，
 * 最后再按 lineIndex 回原始 lines 取 time —— 保证换了 AMLL 版本也不会静默失效。
 */
function onLineClick(e) {
  let seconds = null

  const raw = e?.line?.startTime
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    seconds = raw / 1000
  } else if (raw && typeof raw.value === 'number' && Number.isFinite(raw.value)) {
    seconds = raw.value / 1000
  }

  if (seconds === null) {
    const idx = e?.lineIndex
    const fallback = Number.isInteger(idx) ? props.lines[idx] : null
    if (fallback) seconds = Number(fallback.time) || 0
  }

  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return
  emit('seek', seconds)
}

/** 秒 → 毫秒（AMLL 要求整数毫秒） */
const toMs = (sec) => Math.max(0, Math.round((Number(sec) || 0) * 1000))

const lyricLines = computed(() =>
  props.lines.map((line, i) => {
    const startTime = toMs(line.time)
    const next = props.lines[i + 1]
    // 下一行起点即本行终点；最后一行给一个兜底时长
    const rawEnd = Number(line.endTime)
    const endTime = Number.isFinite(rawEnd) && rawEnd > line.time
      ? toMs(rawEnd)
      : (next ? Math.max(startTime + 1, toMs(next.time)) : startTime + props.tailMs)
    const sourceWords = Array.isArray(line.words) ? line.words : []
    const words = sourceWords.length
      ? sourceWords.map((word, wi) => {
          const wordStart = toMs(word.startTime)
          const nextWord = sourceWords[wi + 1]
          const wordEnd = Number.isFinite(Number(word.endTime))
            ? toMs(word.endTime)
            : (nextWord ? toMs(nextWord.startTime) : endTime)
          return {
            word: word.text || '',
            startTime: Math.max(startTime, wordStart),
            endTime: Math.max(startTime + 1, wordEnd),
          }
        })
      : [{ word: line.text || '', startTime, endTime }]
    return {
      words,
      translatedLyric: line.translation || '',
      romanLyric: line.romanization || '',
      startTime,
      endTime,
      isBG: !!line.background,
      isDuet: false,
    }
  })
)

const currentMs = computed(() => toMs(props.currentTime))
</script>

<template>
  <div class="wall">
    <LyricPlayer
      v-if="lyricLines.length"
      class="wall__player"
      :lyric-lines="lyricLines"
      :current-time="currentMs"
      :playing="playing"
      @line-click="onLineClick"
    />
    <div v-else class="wall__empty">
      <NIcon name="file-text" :size="24" />
      <p>{{ $t('ui.noLyrics') }}</p>
    </div>
  </div>
</template>

<style scoped>
.wall {
  position: relative;
  width: 100%;
  /* 必须给出【确定高度】：AMLL 的 LyricPlayer 依赖容器尺寸做布局，
     若父级为 auto，子元素 height:100% 无法解析 → 渲染为 0 高度（无歌词显示）。 */
  height: min(62vh, 620px);
  min-height: 320px;
  overflow: hidden;
}

.wall__player {
  width: 100%;
  height: 100%;
  /* 提示「歌词行可点」——点击会跳转到该行 */
  cursor: pointer;
}

.wall__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-2);
  height: 100%;
  color: var(--n-text-faint);
}

.wall__empty p {
  margin: 0;
  font-size: var(--n-text-sm);
}
</style>

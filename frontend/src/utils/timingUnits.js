/**
 * 逐字录入单位：拉丁字母/数字按单词聚合，其它文字按字拆分，
 * 空格与标点附着在前一个单位上，避免英语逐字母点按。
 */
export function splitTimingUnits(text) {
  const value = String(text || '')
  if (!value) return []
  const graphemes = graphemeSegments(value)
  const tokens = []
  let currentWord = ''
  let leading = ''

  const appendToLast = (raw) => {
    if (!raw) return
    if (tokens.length) {
      tokens[tokens.length - 1].text += raw
    } else {
      leading += raw
    }
  }
  const flushWord = () => {
    if (!currentWord) return
    tokens.push({ text: currentWord, startSec: null, endSec: null })
    currentWord = ''
  }

  for (const grapheme of graphemes) {
    if (/^\s+$/u.test(grapheme)) {
      flushWord()
      appendToLast(grapheme)
      continue
    }
    if (/[\p{Script=Latin}\p{Number}]/u.test(grapheme)) {
      currentWord += leading + grapheme
      leading = ''
      continue
    }
    if (currentWord && /^['’\-‐‑–—]$/u.test(grapheme)) {
      currentWord += grapheme
      continue
    }
    flushWord()
    if (/\p{L}/u.test(grapheme)) {
      tokens.push({ text: leading + grapheme, startSec: null, endSec: null })
      leading = ''
    } else {
      appendToLast(leading + grapheme)
      leading = ''
    }
  }
  flushWord()
  if (tokens.length === 0 && leading) {
    tokens.push({ text: leading, startSec: null, endSec: null })
  } else {
    appendToLast(leading)
  }
  return tokens.filter((token) => token.text)
}

export function comparableLyricsText(value) {
  return String(value || '').replace(/\s+/gu, '')
}

/** 只有包含文字或数字的片段才允许成为打轴目标。 */
export function isTimingText(value) {
  return /[\p{L}\p{N}]/u.test(String(value || ''))
}

/** 目标提示去掉首尾空格与符号，保留单词内部符号（如 don't / well-known）。 */
export function timingLabel(value) {
  return String(value || '').replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '')
}

/**
 * 把旧版逐字母时间重新聚合到新的单词/单字拆分上。
 * 字体拆分边界变化时，保留每个新单位覆盖字符的最早开始与最晚结束时间。
 */
export function remapTimingUnits(text, existingWords = []) {
  const charTimings = []
  for (const word of Array.isArray(existingWords) ? existingWords : []) {
    const chars = Array.from(String(word.text || '').replace(/\s+/gu, ''))
    for (const _char of chars) {
      charTimings.push({
        startSec: Number.isFinite(Number(word.startSec)) ? Number(word.startSec) : null,
        endSec: Number.isFinite(Number(word.endSec)) ? Number(word.endSec) : null,
      })
    }
  }
  let position = 0
  return splitTimingUnits(text).map((token) => {
    let startSec = null
    let endSec = null
    const chars = Array.from(token.text.replace(/\s+/gu, ''))
    for (let index = 0; index < chars.length; index++) {
      const timing = charTimings[position++]
      if (timing?.startSec != null && startSec == null) startSec = timing.startSec
      if (timing?.endSec != null) endSec = timing.endSec
    }
    return { ...token, startSec, endSec }
  })
}

function graphemeSegments(value) {
  try {
    if (typeof Intl !== 'undefined' && Intl.Segmenter) {
      return [...new Intl.Segmenter('ja', { granularity: 'grapheme' }).segment(value)]
        .map((item) => item.segment)
    }
  } catch {
    // fall through
  }
  return Array.from(value)
}

/**
 * 逐字录入单位：拉丁字母/数字按单词聚合，日文假名与汉字按字拆分，
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
    if (/[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/u.test(grapheme)) {
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

import { parseLyricsPayload } from '@/utils/lyrics.js'

export function toReportLines(music) {
  const parsed = parseLyricsPayload(music)
  const lines = parsed.map((line, index) => {
    const startSec = finiteNumber(line.time, 0)
    const nextStart = parsed[index + 1]?.time
    const endSec = finiteNumber(
      line.endTime,
      nextStart != null ? Number(nextStart) : startSec + 4,
    )
    const words = Array.isArray(line.words) && line.words.length
      ? line.words.map((word, wordIndex) => ({
          text: word.text || '',
          startSec: finiteNumber(word.startTime, startSec),
          endSec: finiteNumber(
            word.endTime,
            line.words[wordIndex + 1]?.startTime ?? endSec,
          ),
        }))
      : []
    return {
      startSec,
      endSec: Math.max(startSec, endSec),
      text: line.text || '',
      translation: line.translation || '',
      romanization: line.romanization || '',
      words,
    }
  })
  const cleaned = []
  for (const line of lines) {
    if (looksLikeJsonText(line.text)) {
      const translation = translationFromJsonText(line.text)
      if (translation && cleaned.length) {
        cleaned[cleaned.length - 1].translation ||= translation
      }
      continue
    }
    cleaned.push(line)
  }
  return cleaned
}

export function looksLikeJsonText(value) {
  const text = String(value || '').trim()
  return text.startsWith('{') && text.endsWith('}')
    && (text.includes(':') || /^\{\s*["'][\s\S]*["']\s*\}$/u.test(text))
}

function translationFromJsonText(value) {
  const text = String(value || '').trim()
  const wrapper = text.match(/^\{\s*(["'])([\s\S]*)\1\s*\}$/u)
  if (wrapper) {
    return wrapper[2].trim()
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const values = Object.values(parsed)
      if (values.length === 1 && typeof values[0] === 'string') {
        return values[0].trim()
      }
    }
  } catch {
    // 非标准 JSON 交给上层按异常草稿丢弃。
  }
  return ''
}

export function emptyReportLine(startSec = 0) {
  return {
    startSec: Math.max(0, finiteNumber(startSec, 0)),
    endSec: Math.max(0, finiteNumber(startSec, 0) + 4),
    text: '',
    translation: '',
    romanization: '',
    words: [],
  }
}

export function buildNekoDocument(lines) {
  const source = Array.isArray(lines) ? lines : []
  const sorted = source
    .filter((line) => line && typeof line === 'object')
    .map((line) => ({
      ...line,
      startSec: Math.max(0, finiteNumber(line.startSec, 0)),
      endSec: Math.max(0, finiteNumber(line.endSec, 0)),
    }))
    .sort((a, b) => a.startSec - b.startSec)
  const normalized = []
  for (let index = 0; index < sorted.length; index++) {
    const line = sorted[index]
    const nextStart = sorted[index + 1]?.startSec
    const startMs = Math.round(line.startSec * 1000)
    let endSec = line.endSec
    if (endSec <= line.startSec) {
      endSec = nextStart > line.startSec ? nextStart : line.startSec + 4
    }
    const endMs = Math.round(endSec * 1000)
    const words = (Array.isArray(line.words) ? line.words : [])
      .filter((word) => word && typeof word === 'object'
        && typeof word.text === 'string' && word.text.length > 0)
      .map((word) => {
        const wordStart = Math.round(Math.max(line.startSec, finiteNumber(word.startSec, line.startSec)) * 1000)
        const wordEnd = Math.round(Math.max(
          wordStart / 1000,
          finiteNumber(word.endSec, endSec),
        ) * 1000)
        return { startMs: wordStart, endMs: wordEnd, text: word.text }
      })
      .sort((a, b) => a.startMs - b.startMs)
    normalized.push({
      startMs,
      endMs,
      text: typeof line.text === 'string' ? line.text.trim() : '',
      translation: blankToNull(line.translation),
      romanization: blankToNull(line.romanization),
      background: false,
      words: words.filter((word) => typeof word.text === 'string' && word.text.length > 0),
    })
  }
  return { version: 1, lines: normalized }
}

export function reportStats(lines) {
  const list = Array.isArray(lines) ? lines : []
  return {
    lines: list.filter((line) => String(line.text || '').trim()).length,
    translated: list.filter((line) => String(line.translation || '').trim()).length,
    words: list.reduce(
      (sum, line) => sum + (Array.isArray(line.words)
        ? line.words.filter((word) => String(word.text || '').trim()).length
        : 0),
      0,
    ),
  }
}

function finiteNumber(value, fallback) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function blankToNull(value) {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text ? text : null
}

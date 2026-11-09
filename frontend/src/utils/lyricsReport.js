import { parseLyricsPayload } from '@/utils/lyrics.js'

export function toReportLines(music) {
  const parsed = parseLyricsPayload(music)
  return parsed.map((line, index) => {
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
      .filter((word) => String(word.text || '').length > 0)
      .map((word) => {
        const wordStart = Math.round(Math.max(line.startSec, finiteNumber(word.startSec, line.startSec)) * 1000)
        const wordEnd = Math.round(Math.max(
          wordStart / 1000,
          finiteNumber(word.endSec, endSec),
        ) * 1000)
        return { startMs: wordStart, endMs: wordEnd, text: String(word.text || '') }
      })
      .sort((a, b) => a.startMs - b.startMs)
    normalized.push({
      startMs,
      endMs,
      text: String(line.text || '').trim(),
      translation: blankToNull(line.translation),
      romanization: blankToNull(line.romanization),
      background: false,
      words,
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
  const text = String(value ?? '').trim()
  return text ? text : null
}

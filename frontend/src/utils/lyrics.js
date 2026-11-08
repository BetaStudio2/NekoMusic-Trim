/**
 * 歌词消费入口：优先使用后端 neko-v1 富歌词，旧接口/旧数据回退 LRC。
 */

const LRC_TIME = /\[(\d{2}):(\d{2})\.(\d{2,3})\]/

function lrcSeconds(minutes, seconds, fraction) {
  const ms = parseInt(fraction, 10)
  const divisor = fraction.length === 2 ? 100 : 1000
  return parseInt(minutes, 10) * 60 + parseInt(seconds, 10) + ms / divisor
}

export function parseLrcLyrics(lrcText) {
  if (!lrcText) return []
  const lines = String(lrcText).split('\n')
  const parsed = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim()
    if (!line) continue
    const timeMatch = line.match(LRC_TIME)
    if (!timeMatch) continue

    const time = lrcSeconds(timeMatch[1], timeMatch[2], timeMatch[3])
    const text = line.replace(LRC_TIME, '').trim()
    let translation = ''
    if (i + 1 < lines.length) {
      const nextLine = lines[i + 1].trim()
      const jsonMatch = nextLine.match(/^\{["'](.+)["']\}$/)
      if (jsonMatch) translation = jsonMatch[1]
    }
    parsed.push({ time, endTime: null, text, translation, romanization: '', words: [] })
  }

  parsed.sort((a, b) => a.time - b.time)
  return parsed
}

function fromRich(rich) {
  const lines = Array.isArray(rich?.lines) ? rich.lines : []
  return lines
    .filter((line) => line && Number.isFinite(Number(line.startMs)))
    .map((line) => ({
      time: Math.max(0, Number(line.startMs)) / 1000,
      endTime: Number.isFinite(Number(line.endMs)) ? Number(line.endMs) / 1000 : null,
      text: typeof line.text === 'string' ? line.text : '',
      translation: typeof line.translation === 'string' ? line.translation : '',
      romanization: typeof line.romanization === 'string' ? line.romanization : '',
      background: !!line.background,
      words: Array.isArray(line.words)
        ? line.words
            .filter((word) => word && Number.isFinite(Number(word.startMs)))
            .map((word) => ({
              text: typeof word.text === 'string' ? word.text : '',
              startTime: Math.max(0, Number(word.startMs)) / 1000,
              endTime: Number.isFinite(Number(word.endMs)) ? Number(word.endMs) / 1000 : null,
            }))
        : [],
    }))
    .sort((a, b) => a.time - b.time)
}

/** `GET /api/music/info/{id}` 的 data 或完整响应都可传入。 */
export function parseLyricsPayload(payload) {
  const data = payload?.data && typeof payload.data === 'object' ? payload.data : payload
  if (data?.lyricsRich && Array.isArray(data.lyricsRich.lines)) {
    const rich = fromRich(data.lyricsRich)
    if (rich.length) return rich
  }
  return parseLrcLyrics(typeof data?.lyrics === 'string' ? data.lyrics : '')
}

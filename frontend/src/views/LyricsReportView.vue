<template>
  <AmbientBackdrop />

  <PageShell width="xwide">
    <div class="report">
      <header class="report__head">
        <NButton variant="ghost" icon="chevron-left" @click="backToSong">
          {{ $t('lyricsReport.back') }}
        </NButton>
        <div class="report__intro">
          <h1>{{ $t('lyricsReport.title') }}</h1>
          <p>{{ $t('lyricsReport.subtitle') }}</p>
        </div>
      </header>

      <NCard v-if="loading" pad="lg" class="state">
        <NSpinner :size="26" />
        <p>{{ $t('lyricsReport.loading') }}</p>
      </NCard>

      <NCard v-else-if="!music" pad="lg" class="state">
        <NIcon name="circle-alert" :size="26" />
        <p>{{ $t('lyricsReport.musicMissing') }}</p>
      </NCard>

      <template v-else-if="submittedId">
        <NCard pad="lg" class="success">
          <div class="success__icon"><NIcon name="circle-check" :size="28" /></div>
          <h2>{{ $t('lyricsReport.submittedTitle') }}</h2>
          <p>{{ $t('lyricsReport.submittedDesc', { id: submittedId }) }}</p>
          <div class="success__actions">
            <NButton variant="primary" icon="play" @click="backToSong">
              {{ $t('lyricsReport.backToSong') }}
            </NButton>
          </div>
        </NCard>
      </template>

      <template v-else>
        <NCard pad="md" class="song-card">
          <img class="song-card__cover" :src="coverUrl" :alt="music.title" />
          <div class="song-card__meta">
            <h2>{{ music.title }}</h2>
            <p>{{ music.artist }}<span v-if="music.album"> · {{ music.album }}</span></p>
            <p class="song-card__stats">
              {{ $t('lyricsReport.stats', stats) }}
            </p>
          </div>
          <NTag variant="accent" size="sm">{{ music.language || $t('common.unknown') }}</NTag>
        </NCard>

        <NCard v-if="!isLoggedIn" pad="lg" class="login-card">
          <NIcon name="lock" :size="26" />
          <div>
            <h2>{{ $t('lyricsReport.loginTitle') }}</h2>
            <p>{{ $t('lyricsReport.loginDesc') }}</p>
          </div>
          <NButton variant="primary" icon="login" @click="openLogin">
            {{ $t('lyricsReport.login') }}
          </NButton>
        </NCard>

        <template v-else>
          <div class="toolbar">
            <div class="tabs" role="tablist" :aria-label="$t('lyricsReport.tabsAria')">
              <button
                v-for="tab in tabs"
                :key="tab.id"
                type="button"
                role="tab"
                :aria-selected="activeTab === tab.id"
                :class="{ 'tabs__item--active': activeTab === tab.id }"
                class="tabs__item"
                @click="activeTab = tab.id"
              >
                <NIcon :name="tab.icon" :size="15" />
                {{ $t(tab.label) }}
              </button>
            </div>
            <span v-if="draftSaved" class="draft-state">
              <NIcon name="check" :size="13" />
              {{ $t('lyricsReport.draftSaved') }}
            </span>
          </div>

          <KaraokeTimingEditor
            v-if="activeTab === 'words'"
            :lines="lines"
            :audio-url="audioUrl"
            :audio-loading="audioLoading"
          />

          <LyricsPreviewPanel
            v-else-if="activeTab === 'preview'"
            :lines="lines"
            :audio-url="audioUrl"
            :audio-loading="audioLoading"
          />

          <NCard v-else-if="activeTab === 'text'" pad="md" class="plain-editor-card">
            <div class="bilingual-editor">
              <label class="field">
                <span>{{ $t('lyricsReport.plainTextHint') }}</span>
                <textarea
                  v-model="plainLyricsText"
                  class="plain-editor"
                  spellcheck="false"
                  :placeholder="$t('lyricsReport.plainTextPlaceholder')"
                  @input="syncBilingualTextToLines"
                />
              </label>
              <label class="field">
                <span>{{ $t('lyricsReport.translationPlainHint') }}</span>
                <textarea
                  v-model="plainTranslationText"
                  class="plain-editor plain-editor--translation"
                  spellcheck="false"
                  :placeholder="$t('lyricsReport.translationPlainPlaceholder')"
                  @input="syncBilingualTextToLines"
                />
              </label>
            </div>
          </NCard>

          <NCard pad="md" class="submit-card">
            <label class="field">
              <span>{{ $t('lyricsReport.noteTitle') }}</span>
              <NInput
                v-model="note"
                textarea
                :rows="3"
                :maxlength="500"
                :placeholder="$t('lyricsReport.notePlaceholder')"
              />
            </label>
            <div class="submit-card__foot">
              <p>{{ $t('lyricsReport.reviewHint') }}</p>
              <NButton
                variant="primary"
                icon="send"
                :loading="submitting"
                :disabled="submitting"
                @click="submitReport"
              >
                {{ submitting ? $t('lyricsReport.submitting') : $t('lyricsReport.submit') }}
              </NButton>
            </div>
          </NCard>
        </template>
      </template>
    </div>
  </PageShell>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { useAuth } from '@/composables/useAuth'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { useToast } from '@/composables/useToast'
import { getToken } from '@/utils/userStore.js'
import { applyPageSeo, PRIVATE_ROBOTS } from '@/utils/seoMeta'
import { buildNekoDocument, emptyReportLine, reportStats, toReportLines } from '@/utils/lyricsReport'
import { resolveMediaUrl } from '@/utils/mediaUrl.js'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NSpinner, NTag } from '@/ui'
import KaraokeTimingEditor from '@/components/KaraokeTimingEditor.vue'
import LyricsPreviewPanel from '@/components/LyricsPreviewPanel.vue'
import { AmbientBackdrop, PageShell } from '@/layouts'

const route = useRoute()
const router = useRouter()
const toast = useToast()
const { isLoggedIn } = useAuth()

const musicId = computed(() => Number(route.params.id))
const coverUrl = computed(() => `${API_CONFIG.BASE_URL}/api/music/cover/${musicId.value}`)
const audioUrl = ref('')
const audioLoading = ref(false)
const tabs = [
  { id: 'text', label: 'lyricsReport.tabText', icon: 'edit' },
  { id: 'words', label: 'lyricsReport.tabWords', icon: 'file-text' },
  { id: 'preview', label: 'lyricsReport.tabPreview', icon: 'eye' },
]

const loading = ref(true)
const submitting = ref(false)
const submittedId = ref(null)
const music = ref(null)
const lines = ref([])
const plainLyricsText = ref('')
const plainTranslationText = ref('')
const note = ref('')
const activeTab = ref('text')
const draftSaved = ref(false)
const draftKey = computed(() => `nekoLyricsReportDraft:${musicId.value}`)
let lineSequence = 0
let draftTimer = null

const stats = computed(() => reportStats(lines.value))

onMounted(load)

watch(
  [lines, note, activeTab],
  () => scheduleDraftSave(),
  { deep: true },
)

watch(activeTab, (tab, previous) => {
  if (previous === 'text' && tab !== 'text') {
    syncBilingualTextToLines()
  } else if (tab === 'text' && previous !== 'text') {
    syncLinesToEditors()
  }
})

onBeforeUnmount(() => {
  if (draftTimer) window.clearTimeout(draftTimer)
  saveDraft()
})

async function load() {
  loading.value = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId.value}`)
    const payload = await response.json().catch(() => ({}))
    if (!response.ok || !payload.success || !payload.data) {
      music.value = null
      return
    }
    music.value = payload.data
    const initial = toReportLines(payload.data)
    if (restoreDraft()) {
      toast.success('已恢复本机保存的歌词修改')
    } else {
      lines.value = (initial.length ? initial : [emptyReportLine()]).map(withUid)
    }
    syncLinesToEditors()
    loadAudio()
    applyPageSeo({
      title: `${payload.data.title} - 歌词报错 | Neko歌姬计划`,
      description: '协助修改歌词、添加翻译并完善逐字时间轴，提交后由管理员审核。',
      path: `/lyrics-report/${musicId.value}`,
      robots: PRIVATE_ROBOTS,
      jsonLd: null,
    })
  } catch {
    music.value = null
  } finally {
    loading.value = false
  }
}

function syncLinesToEditors() {
  plainLyricsText.value = lines.value.map((line) => line.text || '').join('\n')
  plainTranslationText.value = lines.value.map((line) => line.translation || '').join('\n')
}

function syncBilingualTextToLines() {
  const textLines = plainLyricsText.value.split(/\r?\n/u)
  const translationLines = plainTranslationText.value.split(/\r?\n/u)
  const entries = []
  for (let index = 0; index < textLines.length; index++) {
    const text = textLines[index].trim()
    if (!text) continue
    entries.push({
      text,
      translation: String(translationLines[index] || '').trim(),
    })
  }
  const previousLines = lines.value
  const used = new Set()
  const next = []

  for (let index = 0; index < entries.length; index++) {
    const { text, translation } = entries[index]
    let sourceIndex = -1
    if (previousLines[index] && !used.has(index)
        && String(previousLines[index].text || '').trim() === text) {
      sourceIndex = index
    }
    if (sourceIndex < 0) {
      sourceIndex = previousLines.findIndex((line, lineIndex) =>
        !used.has(lineIndex) && String(line.text || '').trim() === text)
    }
    if (sourceIndex < 0 && previousLines[index] && !used.has(index)) {
      sourceIndex = index
    }

    if (sourceIndex >= 0) {
      const source = previousLines[sourceIndex]
      used.add(sourceIndex)
      const changed = String(source.text || '').trim() !== text
      next.push({
        ...source,
        text,
        translation,
        words: changed ? [] : source.words,
      })
    } else {
      const previous = next[next.length - 1]
      const line = withUid(emptyReportLine(previous ? Number(previous.endSec) || 0 : 0))
      line.text = text
      line.translation = translation
      next.push(line)
    }
  }
  lines.value = next
}

function restoreDraft() {
  try {
    const draft = JSON.parse(window.localStorage.getItem(draftKey.value) || 'null')
    if (!draft || !Array.isArray(draft.lines) || draft.lines.length === 0) return false
    lines.value = draft.lines.map((line) => withUid({
      startSec: Number(line.startSec) || 0,
      endSec: Number(line.endSec) || 0,
      text: String(line.text || ''),
      translation: String(line.translation || ''),
      romanization: String(line.romanization || ''),
      words: Array.isArray(line.words)
        ? line.words.map((word) => ({
            text: String(word.text || ''),
            startSec: word.startSec == null ? null : Number(word.startSec),
            endSec: word.endSec == null ? null : Number(word.endSec),
          }))
        : [],
    }))
    note.value = String(draft.note || '')
    if (tabs.some((tab) => tab.id === draft.activeTab)) {
      activeTab.value = draft.activeTab
    }
    draftSaved.value = true
    return true
  } catch {
    return false
  }
}

function scheduleDraftSave() {
  if (loading.value || submittedId.value) return
  if (draftTimer) window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(saveDraft, 350)
}

function saveDraft() {
  if (loading.value || submittedId.value || !music.value || !lines.value.length) return
  try {
    window.localStorage.setItem(draftKey.value, JSON.stringify({
      version: 1,
      updatedAt: Date.now(),
      activeTab: activeTab.value,
      note: note.value,
      lines: lines.value.map(({ uid: _uid, ...line }) => line),
    }))
    draftSaved.value = true
  } catch {
    draftSaved.value = false
  }
}

async function loadAudio() {
  audioLoading.value = true
  audioUrl.value = ''
  try {
    audioUrl.value = await resolveMediaUrl(musicId.value)
  } catch (error) {
    toast.error(error.message || '音乐加载失败')
  } finally {
    audioLoading.value = false
  }
}

function withUid(line) {
  return {
    ...line,
    uid: ++lineSequence,
    words: Array.isArray(line.words) ? line.words.map((word) => ({ ...word })) : [],
  }
}

function openLogin() {
  openAuthDialog('login')
}

function backToSong() {
  router.push(`/detail/${musicId.value}`)
}

async function submitReport() {
  if (submitting.value) return
  if (!isLoggedIn.value) {
    openLogin()
    return
  }
  const populated = lines.value.filter((line) => String(line.text || '').trim())
  if (!populated.length) {
    toast.error('请至少填写一行歌词')
    return
  }
  for (let index = 0; index < populated.length; index++) {
    if (Number(populated[index].endSec) <= Number(populated[index].startSec)) {
      toast.error(`第 ${index + 1} 行的结束时间必须晚于开始时间`)
      return
    }
  }
  const content = JSON.stringify(buildNekoDocument(populated))
  if (new Blob([content]).size > 1024 * 1024) {
    toast.error('歌词内容不能超过 1MiB')
    return
  }

  submitting.value = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/lyrics/reports`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
      },
      body: JSON.stringify({
        musicId: musicId.value,
        content,
        note: note.value,
      }),
    })
    const payload = await response.json().catch(() => ({}))
    if (response.status === 401) {
      openLogin()
      return
    }
    if (!response.ok || !payload.success) {
      throw new Error(payload.message || '提交失败')
    }
    submittedId.value = payload.data?.id
    try {
      window.localStorage.removeItem(draftKey.value)
    } catch {
      // 本地草稿清理失败不影响提交结果。
    }
  } catch (error) {
    toast.error(error.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

</script>

<style scoped>
.report {
  width: min(1600px, 100%);
  margin: 0 auto;
  padding: clamp(18px, 4vw, 44px) clamp(14px, 4vw, 40px) 80px;
}

.report__head {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
}

.report__intro h1 {
  margin: 0;
  font-size: clamp(26px, 4vw, 42px);
}

.report__intro p {
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-muted);
}

.state,
.success,
.login-card {
  display: grid;
  place-items: center;
  gap: var(--n-space-3);
  text-align: center;
}

.success__icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.success h2,
.success p {
  margin: 0;
}

.song-card {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-4);
}

.song-card__cover {
  width: 72px;
  height: 72px;
  border-radius: var(--n-radius-md);
  object-fit: cover;
  background: var(--n-surface-soft);
}

.song-card__meta {
  flex: 1;
  min-width: 0;
}

.song-card__meta h2,
.song-card__meta p {
  margin: 0;
}

.song-card__meta p {
  margin-top: 4px;
  color: var(--n-text-muted);
}

.song-card__stats {
  font-size: var(--n-text-sm);
}

.login-card {
  grid-template-columns: auto 1fr auto;
  place-items: center start;
  text-align: left;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  margin: var(--n-space-5) 0 var(--n-space-3);
}

.draft-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
}

.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: var(--n-radius-sm);
  color: var(--n-text-muted);
}

.tabs__item--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.lines {
  display: grid;
  gap: var(--n-space-3);
}

.plain-editor-card {
  min-height: 520px;
}

.bilingual-editor {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--n-space-4);
  align-items: start;
}

.plain-editor {
  width: 100%;
  min-height: 62vh;
  padding: var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  outline: none;
  resize: vertical;
  background: var(--n-surface-soft);
  color: var(--n-text);
  font-family: var(--n-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: clamp(15px, 1.2vw, 18px);
  line-height: 1.9;
  tab-size: 2;
}

.plain-editor--translation {
  background: color-mix(in srgb, var(--n-surface-soft) 82%, var(--n-accent-soft));
}

.plain-editor:focus {
  border-color: var(--n-accent-line);
  box-shadow: var(--n-shadow-glow);
}

@media (min-width: 1080px) {
  .lines {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}

.line-card {
  padding: var(--n-space-4);
}

.line-card__head {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.line-card__head strong {
  color: var(--n-text);
}

.line-card__remove,
.word-row__remove {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  margin-left: auto;
  border-radius: var(--n-radius-sm);
  color: var(--n-text-faint);
}

.line-card__remove:hover,
.word-row__remove:hover {
  background: var(--n-danger-soft);
  color: var(--n-danger);
}

.line-card__times {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--n-space-3);
  margin-bottom: var(--n-space-3);
}

.line-card__times label,
.field {
  display: grid;
  gap: 6px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.line-card__times input,
.word-row input {
  min-width: 0;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
}

.line-card__original {
  margin: 0 0 var(--n-space-3);
  padding: 10px 12px;
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
}

.words {
  display: grid;
  gap: 8px;
}

.word-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 88px 88px 32px;
  gap: 8px;
  align-items: center;
}

.submit-card {
  margin-top: var(--n-space-5);
}

.submit-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-top: var(--n-space-4);
}

.submit-card__foot p {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

@media (max-width: 720px) {
  .bilingual-editor {
    grid-template-columns: minmax(0, 1fr);
  }

  .song-card {
    align-items: flex-start;
  }

  .song-card__cover {
    width: 56px;
    height: 56px;
  }

  .login-card {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
  }

  .toolbar,
  .submit-card__foot {
    align-items: stretch;
    flex-direction: column;
  }

  .tabs {
    overflow-x: auto;
  }

  .line-card__times {
    grid-template-columns: 1fr;
  }

  .word-row {
    grid-template-columns: 1fr 1fr 1fr 32px;
  }
}
</style>

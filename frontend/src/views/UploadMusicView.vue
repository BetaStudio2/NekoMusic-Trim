<template>
  <AmbientBackdrop />

  <PageShell width="wide">
    <Transition name="notice">
      <div
        v-if="uploadNotice.visible"
        class="notice"
        :class="`notice--${uploadNotice.type}`"
        role="status"
        aria-live="polite"
      >
        <NIcon :name="uploadNotice.type === 'error' ? 'triangle-alert' : 'circle-check'" :size="16" />
        {{ uploadNotice.message }}
      </div>
    </Transition>

    <div class="upload">
      <!-- 左：封面 + 歌词 -->
      <div class="upload__aside">
        <NCard pad="lg" class="cover-card">
          <h2 class="side-title">{{ $t('upload.coverTitle') }}</h2>
          <div
            class="dropzone dropzone--cover"
            :class="{ 'dropzone--filled': coverFile, 'dropzone--over': isCoverDragging }"
            @dragover.prevent="isCoverDragging = true"
            @dragleave.prevent="isCoverDragging = false"
            @drop.prevent="handleCoverDrop"
          >
            <input ref="coverFileInput" type="file" accept="image/*" class="dropzone__input" @change="handleCoverFileChange" />
            <button type="button" class="dropzone__hit" @click="selectCoverFile">
              <span class="n-visually-hidden">{{ $t('upload.selectCoverAria') }}</span>
              <template v-if="!coverFile">
                <NIcon name="image" :size="44" class="dropzone__icon" />
                <span class="dropzone__title">{{ $t('upload.uploadCover') }}</span>
                <span class="dropzone__hint">{{ $t('upload.coverHint') }}</span>
              </template>
            </button>
            <template v-if="coverFile">
              <img :src="coverPreview" :alt="$t('upload.coverPreviewAlt')" class="cover-preview" />
              <NButton class="cover-change" size="sm" variant="secondary" @click="removeCoverFile">
                {{ $t('upload.removeCover') }}
              </NButton>
            </template>
          </div>
        </NCard>

        <NCard pad="lg" class="lyrics-card">
          <h2 class="side-title">{{ $t('upload.lyricsTitle') }}</h2>
          <div
            class="dropzone dropzone--file"
            :class="{ 'dropzone--filled': lyricsFile, 'dropzone--over': isLyricsDragging }"
            @dragover.prevent="isLyricsDragging = true"
            @dragleave.prevent="isLyricsDragging = false"
            @drop.prevent="handleLyricsFileDrop"
          >
            <input ref="lyricsFileInput" type="file" accept=".lrc,.neko" class="dropzone__input" @change="handleLyricsFileChange" />
            <button type="button" class="dropzone__hit" @click="selectLyricsFile">
              <span class="n-visually-hidden">{{ $t('upload.selectLyricsAria') }}</span>
              <template v-if="!lyricsFile">
                <NIcon name="file-text" :size="22" class="dropzone__icon" />
                <span class="dropzone__hint">{{ $t('upload.chooseLyricsFile') }}</span>
              </template>
            </button>
            <template v-if="lyricsFile">
              <NIcon name="file-text" :size="18" class="dropzone__icon" />
              <span class="dropzone__filename">{{ lyricsFile.name }}</span>
              <button type="button" class="icon-x" :aria-label="$t('upload.removeLyricsAria')" @click="removeLyricsFile">
                <NIcon name="close" :size="14" />
              </button>
            </template>
          </div>

          <!-- 格式说明常驻展开：说明本身是使用前提，不做折叠 -->
          <section class="guide">
            <h3 class="guide__title">{{ $t('upload.guideTitle') }}</h3>
            <p class="guide__text">{{ $t('upload.guideText') }}</p>
            <pre class="guide__code">[00:00.389] ざこざこざこざこ くだらない存在 あわれだね
{"杂鱼杂鱼杂鱼杂鱼 无聊的存在 真可怜呢"}

[00:07.546] ざこざこざこざこ ざこのざこ攻撃 効かないよ
{"杂鱼杂鱼杂鱼杂鱼 杂鱼的杂鱼攻击 根本没用喔"}</pre>
            <ul class="guide__list">
              <li>{{ $t('upload.guideLi1') }}<code>[分:秒.毫秒]</code></li>
              <li>{{ $t('upload.guideLi2') }} <code>{"翻译内容"}</code></li>
              <li>{{ $t('upload.guideLi3') }}</li>
            </ul>
          </section>
        </NCard>
      </div>

      <!-- 右：表单 -->
      <div class="upload__form">
        <h1 class="page-title">{{ $t('upload.pageTitle') }}</h1>
        <p class="page-sub">{{ $t('upload.pageSub') }}</p>

        <form class="form" @submit.prevent="handleSubmit">
          <div
            class="dropzone dropzone--audio"
            :class="{ 'dropzone--filled': musicFile, 'dropzone--over': isDragging }"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="handleDrop"
          >
            <input ref="musicFileInput" type="file" accept="audio/*" class="dropzone__input" @change="handleMusicFileChange" />
            <button type="button" class="dropzone__hit" @click="selectMusicFile">
              <span class="n-visually-hidden">{{ $t('upload.selectAudioAria') }}</span>
              <template v-if="!musicFile">
                <NIcon name="upload" :size="28" class="dropzone__icon" />
                <span class="dropzone__title">{{ $t('upload.chooseAudio') }}</span>
                <span class="dropzone__hint">{{ $t('upload.audioHint') }}</span>
              </template>
            </button>
            <template v-if="musicFile">
              <NIcon name="file-music" :size="22" class="dropzone__icon" />
              <span class="dropzone__filename">{{ musicFile.name }}</span>
              <button type="button" class="icon-x" :aria-label="$t('upload.removeAudioAria')" @click="removeMusicFile">
                <NIcon name="close" :size="14" />
              </button>
            </template>
          </div>

          <div class="field">
            <label class="field__label" for="up-title">{{ $t('upload.labelTitle') }} <span class="req">*</span></label>
            <NInput id="up-title" v-model="formData.title" :placeholder="$t('upload.phTitle')" required />
          </div>

          <div class="field">
            <label class="field__label" for="up-artist">{{ $t('upload.labelArtist') }} <span class="req">*</span></label>
            <NInput id="up-artist" v-model="formData.artist" :placeholder="$t('upload.phArtist')" required />
          </div>

          <div class="field">
            <label class="field__label" for="up-album">{{ $t('upload.labelAlbum') }}</label>
            <NInput id="up-album" v-model="formData.album" :placeholder="$t('upload.phAlbum')" />
          </div>

          <div class="field">
            <label class="field__label" for="up-tags">{{ $t('upload.labelTags') }}</label>
            <NInput id="up-tags" v-model="formData.tags" :placeholder="$t('upload.phTags')" />
          </div>

          <div class="field">
            <label class="field__label" for="up-lang">{{ $t('upload.labelLang') }} <span class="req">*</span></label>
            <select id="up-lang" v-model="formData.language" class="select" required>
              <option value="" disabled>{{ $t('upload.langPlaceholder') }}</option>
              <option value="中文">{{ $t('upload.langChinese') }}</option>
              <option value="粤语">{{ $t('upload.langCantonese') }}</option>
              <option value="上海语">{{ $t('upload.langShanghainese') }}</option>
              <option value="英文">{{ $t('upload.langEnglish') }}</option>
              <option value="日语">{{ $t('upload.langJapanese') }}</option>
              <option value="韩语">{{ $t('upload.langKorean') }}</option>
              <option value="法语">{{ $t('upload.langFrench') }}</option>
              <option value="德语">{{ $t('upload.langGerman') }}</option>
              <option value="俄语">{{ $t('upload.langRussian') }}</option>
              <option value="纯音乐">{{ $t('upload.langInstrumental') }}</option>
            </select>
          </div>

          <div class="field">
            <label class="field__label" for="up-duration">{{ $t('upload.labelDuration') }}</label>
            <div class="duration">
              <NInput
                id="up-duration"
                v-model.number="formData.duration"
                type="number"
                min="0"
                step="1"
                :placeholder="$t('upload.durationPlaceholder')"
                :disabled="parsingDuration"
              />
              <NButton
                variant="secondary"
                icon="timer"
                :disabled="!musicFile || parsingDuration"
                :title="!musicFile ? $t('upload.durationNoFile') : $t('upload.durationParse')"
                @click="parseDuration"
              >
                {{ $t('upload.durationParseBtn') }}
              </NButton>
            </div>
            <p class="field__hint">
              {{ $t('upload.durationCurrent', { duration: formatDuration(formData.duration) }) }}
              <span v-if="formData.duration === 0" class="field__warn">
                <NIcon name="triangle-alert" :size="13" /> {{ $t('upload.durationRequired') }}
              </span>
            </p>
          </div>

          <NButton type="submit" variant="primary" size="lg" block :loading="uploading">
            {{ uploading ? $t('upload.uploading', { percent: uploadProgress }) : $t('upload.submit') }}
          </NButton>
        </form>
      </div>
    </div>
  </PageShell>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from '@/composables/useToast'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NModal, NSpinner } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { t } from '@/i18n'

const toast = useToast()
const router = useRouter()

const musicFileInput = ref(null)
const coverFileInput = ref(null)
const lyricsFileInput = ref(null)

const musicFile = ref(null)
const coverFile = ref(null)
const coverPreview = ref(null)
const lyricsFile = ref(null)

const formData = ref({
  title: '',
  artist: '',
  album: '',
  tags: '',
  language: '',
  duration: 0
})

const uploading = ref(false)
const uploadProgress = ref(0)
const parsingDuration = ref(false)
const uploadNotice = ref({
  visible: false,
  type: 'error',
  message: ''
})
let uploadNoticeTimer = null

const isDragging = ref(false)
const isCoverDragging = ref(false)
const isLyricsDragging = ref(false)

const selectMusicFile = () => {
  musicFileInput.value.click()
}

const selectCoverFile = () => {
  coverFileInput.value.click()
}

// 手动解析时长
const parseDuration = async () => {
  if (!musicFile.value) {
    toast.error(t('upload.needMusicFile'))
    return
  }

  if (parsingDuration.value) {
    return
  }

  parsingDuration.value = true

  try {
    const audio = new Audio()
    const objectUrl = URL.createObjectURL(musicFile.value)
    audio.src = objectUrl

    let metadataLoaded = false
    let canPlayLoaded = false

    // 设置超时（10秒）
    const timeout = setTimeout(() => {
      console.warn('[手动时长解析] 10秒超时')
      if (!metadataLoaded && !canPlayLoaded) {
        URL.revokeObjectURL(objectUrl)
        toast.error(t('upload.parseTimeout'))
      }
      parsingDuration.value = false
    }, 10000)

    // 监听多个事件
    audio.onloadedmetadata = () => {
      clearTimeout(timeout)
      metadataLoaded = true
      checkAndSaveDurationManual(audio, objectUrl)
    }

    audio.oncanplay = () => {
      if (!metadataLoaded && !canPlayLoaded) {
        clearTimeout(timeout)
        canPlayLoaded = true
        checkAndSaveDurationManual(audio, objectUrl)
      }
    }

    audio.oncanplaythrough = () => {
      if (!metadataLoaded && !canPlayLoaded) {
        clearTimeout(timeout)
        canPlayLoaded = true
        checkAndSaveDurationManual(audio, objectUrl)
      }
    }

    audio.onerror = (error) => {
      clearTimeout(timeout)
      console.error('[手动时长解析] 音频加载错误')
      console.error('[手动时长解析] audio.error:', audio.error)
      console.error('[手动时长解析] audio.error.code:', audio.error?.code)
      console.error('[手动时长解析] audio.error.message:', audio.error?.message)
      URL.revokeObjectURL(objectUrl)
      toast.error(t('upload.parseFailed'))
      parsingDuration.value = false
    }

    audio.onloadeddata = () => {
    }

  } catch (error) {
    console.error('[手动时长解析] 解析出错:', error)
    toast.error(t('upload.parseError', { message: error.message }))
    parsingDuration.value = false
  }
}

// 检查并保存时长（手动版本）
const checkAndSaveDurationManual = (audio, objectUrl) => {

  // 尝试多次读取duration
  const checkDuration = (attempts = 0) => {
    const duration = audio.duration

    if (duration && duration > 0 && duration !== Infinity && !isNaN(duration)) {
      formData.value.duration = Math.round(duration)
      URL.revokeObjectURL(objectUrl)
      toast.success(t('upload.parseSuccess', { duration: formatDuration(formData.value.duration) }))
      parsingDuration.value = false
      return true
    }

    // 如果还没成功，继续尝试
    if (attempts < 10) {
      setTimeout(() => checkDuration(attempts + 1), 100)
    } else {
      console.warn('[手动时长解析] ✗✗✗ 多次尝试后仍然无法获取有效时长')
      console.warn('[手动时长解析] 最后的duration值:', duration)
      URL.revokeObjectURL(objectUrl)
      toast.error(t('upload.parseFailed'))
      parsingDuration.value = false
    }
    return false
  }

  checkDuration()
}

const handleMusicFileChange = async (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件格式
    const fileName = file.name.toLowerCase()
    const fileExtension = fileName.split('.').pop()
    const isValidFormat = ['.mp3', '.flac', '.wav'].includes('.' + fileExtension)

    if (!isValidFormat) {
      toast.error(t('upload.invalidAudioFormat'))
      event.target.value = ''
      return
    }

    musicFile.value = file


    // 立即解析元数据
    await parseMetadata(fileExtension, file)
  }
}

// 解析MP3文件的元数据
const parseMP3Metadata = async (file) => {
  try {
    const arrayBuffer = await file.arrayBuffer()
    const dataView = new DataView(arrayBuffer)

    // 检查文件头
    const header = dataView.getString(0, 3)

    if (header === 'ID3') {
      const size = dataView.getUint32(6)
      const headerSize = 10
      let offset = headerSize

      const metadata = {
        title: '',
        artist: '',
        album: '',
        cover: null
      }

      while (offset < headerSize + size) {
        const frameId = dataView.getString(offset, 4)
        const frameSize = dataView.getUint32(offset + 4)

        if (frameSize === 0) break

        const frameDataOffset = offset + 10
        const frameDataSize = frameSize

        if (frameId === 'TIT2') {
          metadata.title = dataView.decodeTextFrame(frameDataOffset, frameDataSize)
        } else if (frameId === 'TPE1') {
          metadata.artist = dataView.decodeTextFrame(frameDataOffset, frameDataSize)
        } else if (frameId === 'TALB') {
          metadata.album = dataView.decodeTextFrame(frameDataOffset, frameDataSize)
        } else if (frameId === 'APIC') {
          // 解析封面图片
          let currentOffset = frameDataOffset
          const textEncoding = dataView.getUint8(currentOffset)
          currentOffset += 1

          // 读取MIME类型
          let mimeTypeEnd = currentOffset
          while (dataView.getUint8(mimeTypeEnd) !== 0) {
            mimeTypeEnd++
          }
          const mimeType = dataView.getString(currentOffset, mimeTypeEnd - currentOffset)
          currentOffset = mimeTypeEnd + 1

          // 跳过图片类型
          currentOffset += 1

          // 读取描述
          let descEnd = currentOffset
          while (dataView.getUint8(descEnd) !== 0) {
            descEnd++
          }
          currentOffset = descEnd + 1

          // 读取图片数据
          const imageSize = frameDataSize - (currentOffset - frameDataOffset)
          if (imageSize > 0) {
            const imageData = new Uint8Array(arrayBuffer, currentOffset, imageSize)
            metadata.cover = new Blob([imageData], { type: mimeType })
          }
        }

        offset += 10 + frameSize
      }

      // 自动填充表单
      if (metadata.title) formData.value.title = metadata.title
      if (metadata.artist) formData.value.artist = metadata.artist
      if (metadata.album) formData.value.album = metadata.album
      if (metadata.cover) {
        coverFile.value = new File([metadata.cover], 'cover.jpg', { type: metadata.cover.type })
        coverPreview.value = URL.createObjectURL(metadata.cover)
      }

      toast.success(t('upload.mp3Parsed'))
    } else {
    }
  } catch (error) {
    console.error('[MP3解析] 解析失败:', error)
  }
}

// 解析FLAC文件的元数据
const parseFlacMetadata = async (file) => {
  try {
    const arrayBuffer = await file.arrayBuffer()
    const dataView = new DataView(arrayBuffer)
    const textDecoder = new TextDecoder('utf-8')

    // 检查FLAC文件头
    const header = String.fromCharCode(
      dataView.getUint8(0),
      dataView.getUint8(1),
      dataView.getUint8(2),
      dataView.getUint8(3)
    )


    if (header !== 'fLaC') {
      toast.warning(t('upload.flacNoMetadata'))
      return
    }

    let offset = 4
    const metadata = {
      title: '',
      artist: '',
      album: '',
      cover: null
    }

    const maxBlocks = 100

    while (offset < arrayBuffer.byteLength - 4) {
      // 读取块头
      const blockHeader = dataView.getUint8(offset)
      const blockType = blockHeader & 0x7F
      const isLast = (blockHeader & 0x80) !== 0

      // 读取块大小（3字节，大端序）
      const blockSize = (dataView.getUint8(offset + 1) << 16) | 
                        (dataView.getUint8(offset + 2) << 8) | 
                        dataView.getUint8(offset + 3)

      offset += 4

      // 检查边界
      if (offset + blockSize > arrayBuffer.byteLength) {
        console.warn('FLAC块大小超出文件范围，停止解析')
        break
      }

      // VORBIS_COMMENT块
      if (blockType === 4 && blockSize > 8) {
        try {
          let dataOffset = 0
          
          // 读取vendor length（小端序）
          const vendorLength = dataView.getUint32(offset + dataOffset, true)
          dataOffset += 4 + vendorLength

          // 检查边界
          if (dataOffset + 4 > blockSize) break

          // 读取comments count（小端序）
          const commentsCount = dataView.getUint32(offset + dataOffset, true)
          dataOffset += 4

          // 解析每个comment
          for (let i = 0; i < commentsCount && dataOffset + 4 <= blockSize; i++) {
            const commentLength = dataView.getUint32(offset + dataOffset, true)
            dataOffset += 4

            if (dataOffset + commentLength > blockSize) break

            const commentBytes = new Uint8Array(arrayBuffer, offset + dataOffset, commentLength)
            const comment = textDecoder.decode(commentBytes)
            dataOffset += commentLength

            const equalIndex = comment.indexOf('=')
            if (equalIndex !== -1) {
              const field = comment.substring(0, equalIndex).toUpperCase()
              const value = comment.substring(equalIndex + 1)

              if (field === 'TITLE') metadata.title = value
              else if (field === 'ARTIST') metadata.artist = value
              else if (field === 'ALBUM') metadata.album = value
            }
          }
        } catch (e) {
          console.warn('解析VORBIS_COMMENT块失败:', e)
        }
      }
      // PICTURE块
      else if (blockType === 6 && blockSize > 32) {
        try {
          let picOffset = offset

          // 读取图片类型（大端序）
          const pictureType = dataView.getUint32(picOffset, false)
          picOffset += 4

          // 读取MIME类型长度（大端序）
          const mimeLength = dataView.getUint32(picOffset, false)
          picOffset += 4

          // 检查边界
          if (picOffset + mimeLength > offset + blockSize) {
            console.warn('FLAC图片MIME类型超出范围')
            break
          }

          const mimeBytes = new Uint8Array(arrayBuffer, picOffset, mimeLength)
          const mimeType = textDecoder.decode(mimeBytes)
          picOffset += mimeLength

          // 读取描述长度（大端序）
          const descLength = dataView.getUint32(picOffset, false)
          picOffset += 4 + descLength

          // 跳过宽度、高度、颜色深度、颜色数（各4字节）
          picOffset += 16

          // 读取图片数据长度（大端序）
          const pictureLength = dataView.getUint32(picOffset, false)
          picOffset += 4

          if (pictureLength > 0 && picOffset + pictureLength <= offset + blockSize) {
            const imageData = new Uint8Array(arrayBuffer, picOffset, pictureLength)
            metadata.cover = new Blob([imageData], { type: mimeType })
          }
        } catch (e) {
          console.warn('解析PICTURE块失败:', e)
        }
      }

      // 如果是最后一个块，停止
      if (isLast) break

      // 移动到下一个块
      offset += blockSize
    }

    // 自动填充表单
    if (metadata.title) formData.value.title = metadata.title
    if (metadata.artist) formData.value.artist = metadata.artist
    if (metadata.album) formData.value.album = metadata.album
    if (metadata.cover) {
      coverFile.value = new File([metadata.cover], 'cover.jpg', { type: metadata.cover.type })
      coverPreview.value = URL.createObjectURL(metadata.cover)
    }

    toast.success(t('upload.flacParsed'))
  } catch (error) {
    console.error('[FLAC解析] 解析失败:', error)
    toast.warning(t('upload.flacParseFailed'))
  }
}

// 解析WAV文件的元数据（简化版）
const parseWavMetadata = async (file) => {
  // WAV文件通常不包含ID3标签，这里只做简单处理
  toast.warning(t('upload.wavNoMetadata'))
}

const handleCoverFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    coverFile.value = file
    coverPreview.value = URL.createObjectURL(file)
  }
}

const removeMusicFile = () => {
  musicFile.value = null
  musicFileInput.value.value = ''
}

const removeCoverFile = () => {
  coverFile.value = null
  coverPreview.value = null
  coverFileInput.value.value = ''
}

const selectLyricsFile = () => {
  lyricsFileInput.value.click()
}

const handleLyricsFileChange = (event) => {
  const file = event.target.files[0]
  if (file && isSupportedLyricsFile(file)) {
    lyricsFile.value = file
  } else if (file) {
    event.target.value = ''
    toast.error(t('upload.dropLrc'))
  }
}

const isSupportedLyricsFile = (file) => /\.(lrc|neko)$/i.test(file?.name || '')

const removeLyricsFile = () => {
  lyricsFile.value = null
  lyricsFileInput.value.value = ''
}

const handleDrop = async (event) => {
  event.preventDefault()
  isDragging.value = false
  
  const files = event.dataTransfer.files
  if (files.length > 0) {
    const file = files[0]
    if (file.type.startsWith('audio/')) {
      musicFile.value = file
      
      // 读取音频时长
      const audio = new Audio()
      audio.src = URL.createObjectURL(file)
      audio.onloadedmetadata = async () => {
        // 自动解析元数据
        const fileName = file.name.toLowerCase()
        const fileExtension = fileName.split('.').pop()
        
        if (fileExtension === 'mp3') {
          await parseMP3Metadata(file)
        } else if (fileExtension === 'flac') {
          await parseFlacMetadata(file)
        } else if (fileExtension === 'wav') {
          await parseWavMetadata(file)
        }
        URL.revokeObjectURL(audio.src)
      }
    } else {
      toast.error(t('upload.dropAudio'))
    }
  }
}

// 格式化时长（秒转分:秒）
const formatDuration = (seconds) => {
  if (!seconds || seconds <= 0) return t('upload.notSet')
  const minutes = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${minutes}:${secs.toString().padStart(2, '0')}`
}

// 解析元数据的辅助函数
const parseMetadata = async (fileExtension, file) => {
  
  try {
    if (fileExtension === 'mp3') {
      await parseMP3Metadata(file)
    } else if (fileExtension === 'flac') {
      await parseFlacMetadata(file)
    } else if (fileExtension === 'wav') {
      await parseWavMetadata(file)
    }
    
  } catch (error) {
    console.error('[元数据解析] 解析过程出错:', error)
  }
}

// 扩展DataView以支持读取字符串
DataView.prototype.getString = function(offset, length) {
  let result = ''
  for (let i = 0; i < length; i++) {
    const byte = this.getUint8(offset + i)
    if (byte === 0) break
    result += String.fromCharCode(byte)
  }
  return result
}

// 解码文本帧
DataView.prototype.decodeTextFrame = function(offset, length) {
  if (length === 0) return ''

  const encoding = this.getUint8(offset)
  const textData = new Uint8Array(this.buffer, this.byteOffset + offset + 1, length - 1)

  switch (encoding) {
    case 0:
      return this.decodeISO88591(textData)
    case 1:
      return this.decodeUTF16(textData)
    case 2:
      return this.decodeUTF16BE(textData)
    case 3:
      return this.decodeUTF8(textData)
    default:
      return this.decodeISO88591(textData)
  }
}

DataView.prototype.decodeISO88591 = function(data) {
  let result = ''
  for (let i = 0; i < data.length; i++) {
    result += String.fromCharCode(data[i])
  }
  return result
}

DataView.prototype.decodeUTF16 = function(data) {
  if (data.length < 2) return ''

  const bom = (data[0] << 8) | data[1]

  if (bom === 0xFEFF) {
    return this.decodeUTF16BE(data)
  } else if (bom === 0xFFFE) {
    return this.decodeUTF16LE(data)
  } else {
    return this.decodeUTF16BE(data)
  }
}

DataView.prototype.decodeUTF16BE = function(data) {
  let result = ''
  for (let i = 0; i < data.length; i += 2) {
    if (i + 1 < data.length) {
      const codePoint = (data[i] << 8) | data[i + 1]
      if (codePoint === 0) break
      result += String.fromCharCode(codePoint)
    }
  }
  return result
}

DataView.prototype.decodeUTF16LE = function(data) {
  let result = ''
  for (let i = 0; i < data.length; i += 2) {
    if (i + 1 < data.length) {
      const codePoint = data[i] | (data[i + 1] << 8)
      if (codePoint === 0) break
      result += String.fromCharCode(codePoint)
    }
  }
  return result
}

DataView.prototype.decodeUTF8 = function(data) {
  let result = ''
  let i = 0

  while (i < data.length) {
    const byte1 = data[i]

    if (byte1 === 0) break

    if (byte1 < 0x80) {
      result += String.fromCharCode(byte1)
      i++
    } else if ((byte1 & 0xE0) === 0xC0) {
      if (i + 1 < data.length) {
        const codePoint = ((byte1 & 0x1F) << 6) | (data[i + 1] & 0x3F)
        result += String.fromCharCode(codePoint)
        i += 2
      } else {
        i++
      }
    } else if ((byte1 & 0xF0) === 0xE0) {
      if (i + 2 < data.length) {
        const codePoint = ((byte1 & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)
        result += String.fromCharCode(codePoint)
        i += 3
      } else {
        i++
      }
    } else if ((byte1 & 0xF8) === 0xF0) {
      if (i + 3 < data.length) {
        const codePoint = ((byte1 & 0x07) << 18) | ((data[i + 1] & 0x3F) << 12) | ((data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F)
        result += String.fromCodePoint(codePoint)
        i += 4
      } else {
        i++
      }
    } else {
      i++
    }
  }

  return result
}

const handleCoverDrop = (event) => {
  isCoverDragging.value = false
  const file = event.dataTransfer.files[0]
  if (file && file.type.startsWith('image/')) {
    coverFile.value = file
    coverPreview.value = URL.createObjectURL(file)
  }
}

const handleLyricsFileDrop = (event) => {
  event.preventDefault()
  
  const files = event.dataTransfer.files
  if (files.length > 0) {
    const file = files[0]
    if (isSupportedLyricsFile(file)) {
      lyricsFile.value = file
    } else {
      toast.error(t('upload.dropLrc'))
    }
  }
}

const showUploadNotice = (type, message) => {
  const normalizedType = type === 'success' ? 'success' : 'error'
  const normalizedMessage = message || (normalizedType === 'success' ? t('upload.uploadSuccess') : t('upload.uploadFailed'))

  uploadNotice.value = {
    visible: true,
    type: normalizedType,
    message: normalizedMessage
  }

  if (uploadNoticeTimer) {
    clearTimeout(uploadNoticeTimer)
  }
  uploadNoticeTimer = setTimeout(() => {
    uploadNotice.value.visible = false
  }, 5000)

  if (normalizedType === 'success') {
    toast.success(normalizedMessage)
  } else {
    toast.error(normalizedMessage)
  }
}

const showUploadResultToast = (result, fallbackMessage) => {
  const message = result?.message || fallbackMessage || t('upload.uploadFailed')
  if (result?.success === true) {
    showUploadNotice('success', message)
    return true
  }
  showUploadNotice('error', message)
  return false
}

/**
 * 带真实上传进度的 POST。
 * fetch 不支持上传进度，只有 XHR 的 `upload.onprogress` 能拿到已发送字节数；
 * 发送阶段最多报到 99%，剩下的 1% 留给服务端处理，收到响应后再由调用方置 100。
 */
function uploadWithProgress(url, form, token, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    if (token) {
      xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    }
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || !event.total) return
      onProgress(Math.min(99, Math.round((event.loaded / event.total) * 100)))
    }
    xhr.onload = () => {
      onProgress(100)
      resolve({ status: xhr.status, responseText: xhr.responseText })
    }
    xhr.onerror = () => reject(new Error(t('upload.networkErrorUpload')))
    xhr.onabort = () => reject(new Error(t('upload.uploadCanceled')))
    xhr.ontimeout = () => reject(new Error(t('upload.uploadTimeout')))
    xhr.send(form)
  })
}

const handleSubmit = async () => {
  
  if (!musicFile.value) {
    showUploadNotice('error', t('upload.needMusicFile'))
    return
  }

  if (!formData.value.title || !formData.value.artist || !formData.value.language) {
    showUploadNotice('error', t('upload.needFields'))
    return
  }

  uploading.value = true
  uploadProgress.value = 0

  try {
    const form = new FormData()
    form.append('title', formData.value.title)
    form.append('artist', formData.value.artist)
    form.append('language', formData.value.language)
    form.append('tags', formData.value.tags || '')
    form.append('album', formData.value.album || '')
    form.append('duration', formData.value.duration)
    form.append('uploadUserId', 0)
    form.append('musicFile', musicFile.value)
    
    if (coverFile.value) {
      form.append('coverFile', coverFile.value)
    }
    
    if (lyricsFile.value) {
      form.append('lyricsFile', lyricsFile.value)
    }

    const uploadUrl = `${API_CONFIG.BASE_URL}/api/user/upload`
    const token = localStorage.getItem('userToken')

    uploadProgress.value = 0
    const { status, responseText } = await uploadWithProgress(uploadUrl, form, token, (percent) => {
      uploadProgress.value = percent
    })

    let result = {}
    try {
      result = responseText ? JSON.parse(responseText) : {}
    } catch (parseError) {
      console.error('上传接口返回内容不是JSON:', responseText)
    }

    const fallbackMessage = result.error || responseText || t('upload.uploadFailedHttp', { status })

    if (showUploadResultToast(result, fallbackMessage)) {
      setTimeout(() => {
        router.push('/')
      }, 1500)
    }

  } catch (error) {
    console.error('上传错误:', error)
    showUploadNotice('error', t('upload.uploadFailedRetry'))
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
/* ==================== 提示条 ==================== */
.notice {
  position: sticky;
  /* 用实测顶栏高度：手机上顶栏两行 + 安全区，写死 64 会被压住 */
  top: calc(var(--app-header-h, var(--n-header-height)) + var(--n-space-3));
  z-index: var(--n-z-sticky);
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  width: fit-content;
  max-width: 100%;
  margin: 0 auto var(--n-space-5);
  padding: var(--n-space-3) var(--n-space-5);
  border: 1px solid var(--n-success-soft);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-strong);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
  box-shadow: var(--n-shadow);
  color: var(--n-text);
  font-size: var(--n-text-sm);
}

.notice--error {
  border-color: rgba(255, 107, 107, 0.32);
  color: #ffb3b3;
}

.notice-enter-active,
.notice-leave-active {
  transition: opacity var(--n-duration) var(--n-ease), transform var(--n-duration) var(--n-ease);
}

.notice-enter-from,
.notice-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ==================== 布局 ==================== */
.upload {
  display: grid;
  grid-template-columns: minmax(0, 340px) minmax(0, 1fr);
  gap: clamp(20px, 3vw, 32px);
  align-items: start;
}

.upload__aside {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-5);
}

.side-title {
  margin: 0 0 var(--n-space-4);
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
}

.page-title {
  margin: 0 0 var(--n-space-1);
  font-size: clamp(1.45rem, 3vw, 1.85rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
}

.page-sub {
  margin: 0 0 var(--n-space-6);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== 拖放区 ==================== */
.dropzone {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-2);
  padding: var(--n-space-5);
  border: 1px dashed var(--n-line-strong);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
  text-align: center;
  cursor: pointer;
  transition:
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .dropzone:hover {
    border-color: var(--n-accent-line);
    background: var(--n-surface-hover);
  }
}

.dropzone--over {
  border-color: var(--n-accent);
  background: var(--n-accent-soft);
}

.dropzone--filled {
  border-style: solid;
  border-color: var(--n-line);
}

.dropzone--cover {
  aspect-ratio: 1;
  padding: 0;
  overflow: hidden;
}

.dropzone--audio {
  padding: var(--n-space-8) var(--n-space-5);
  margin-bottom: var(--n-space-6);
}

.dropzone__input {
  display: none;
}

/* 覆盖整个拖拽区的真实按钮：避免 role="button" 与内部「移除」按钮嵌套交互 */
.dropzone__hit {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-2);
  padding: var(--n-space-5);
  border: 0;
  border-radius: inherit;
  background: transparent;
  color: inherit;
  text-align: center;
  cursor: pointer;
}

.dropzone__hit:focus-visible {
  outline: var(--n-focus-ring);
  outline-offset: 3px;
  border-radius: var(--n-radius-lg);
}

.dropzone__icon {
  color: var(--n-text-faint);
}

.dropzone__title {
  color: var(--n-text);
  font-size: var(--n-text-base);
  font-weight: var(--n-weight-semibold);
}

.dropzone__hint {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.dropzone__filename {
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  max-width: 100%;
  overflow-wrap: anywhere;
}

.cover-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-change {
  position: absolute;
  right: var(--n-space-3);
  bottom: var(--n-space-3);
  z-index: 2;
}

.icon-x {
  position: absolute;
  right: var(--n-space-2);
  top: var(--n-space-2);
  z-index: 2;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--n-radius-xs);
  background: var(--n-surface-strong);
  color: var(--n-text-muted);
  transition: color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .icon-x:hover {
    color: var(--n-danger);
  }
}

/* ==================== 歌词格式说明 ==================== */
.guide {
  margin-top: var(--n-space-4);
  padding-top: var(--n-space-4);
  border-top: 1px solid var(--n-line);
}

.guide__title {
  margin: 0 0 var(--n-space-2);
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-bold);
}

.guide__text {
  margin: 0 0 var(--n-space-3);
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
}

.guide__code {
  margin: 0 0 var(--n-space-3);
  padding: var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-sm);
  background: var(--n-surface-sunken);
  color: var(--n-text-muted);
  font-family: var(--n-font-mono);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
  overflow-x: auto;
  white-space: pre;
}

.guide__list {
  margin: 0;
  padding-left: var(--n-space-5);
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-loose);
}

.guide__list code {
  font-family: var(--n-font-mono);
  color: var(--n-accent-strong);
}

/* ==================== 表单 ==================== */
.form {
  display: flex;
  flex-direction: column;
}

.field {
  margin-bottom: var(--n-space-5);
}

.field__label {
  display: block;
  margin-bottom: var(--n-space-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

.req {
  color: var(--n-danger);
}

.select {
  width: 100%;
  height: 40px;
  padding: 0 var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
  font-size: var(--n-text-base);
  outline: none;
  transition: border-color var(--n-duration-fast) var(--n-ease), box-shadow var(--n-duration-fast) var(--n-ease);
}

.select:focus {
  border-color: var(--n-accent-line);
  box-shadow: var(--n-shadow-glow);
}

.select option {
  background: var(--n-bg-elevated);
  color: var(--n-text);
}

.duration {
  display: flex;
  gap: var(--n-space-3);
}

.duration :deep(.n-input) {
  flex: 1;
  min-width: 0;
}

.duration :deep(.n-btn) {
  flex: none;
}

.field__hint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--n-space-2);
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.field__warn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--n-warning);
}

/* ==================== 响应式 ==================== */
@media (max-width: 900px) {
  .upload {
    grid-template-columns: 1fr;
  }

  .dropzone--cover {
    max-width: 320px;
    margin: 0 auto;
    width: 100%;
  }
}

@media (max-width: 560px) {
  .duration {
    flex-direction: column;
  }

  .duration :deep(.n-btn) {
    width: 100%;
    justify-content: center;
  }
}
</style>

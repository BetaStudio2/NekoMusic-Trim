<template>
  <div class="subpage">
    <header class="subpage__head">
      <div>
        <h1 class="subpage__title">音乐管理</h1>
        <p class="subpage__desc">管理平台音乐资源，包括添加、编辑、删除音乐等操作。</p>
      </div>
      <NButton variant="primary" icon="plus" @click="showAddForm = true">添加音乐</NButton>
    </header>

    <!-- ==================== 添加音乐 ==================== -->
    <NModal v-model="showAddForm" title="添加音乐" size="lg" @close="closeAddModal">
      <div class="grid2">
        <div class="col">
          <div class="field">
            <label class="field__label">音乐文件 <span class="req">*</span></label>
            <input type="file" class="file" accept=".mp3,.flac,.wav" @change="handleMusicFileChange" />
            <p v-if="newMusic.fileName" class="field__file">已选择：{{ newMusic.fileName }}</p>
            <p class="field__hint">支持 MP3 / FLAC / WAV；上传 MP3 会自动解析封面、名称、艺术家与专辑。</p>
          </div>

          <div class="field">
            <label class="field__label">音乐图标</label>
            <input type="file" class="file" accept="image/*" @change="handleCoverFileChange" />
            <p v-if="newMusic.coverFileName" class="field__file">已选择：{{ newMusic.coverFileName }}</p>
            <p class="field__hint">不选择时使用音频内嵌封面。</p>
          </div>

          <div class="field">
            <label class="field__label" for="add-duration">时长（秒）</label>
            <NInput id="add-duration" v-model="newMusic.duration" type="number" placeholder="音乐时长" readonly />
            <p class="field__hint">自动从音频文件中读取。</p>
          </div>

          <div class="field field--last">
            <label class="field__label" for="add-lang">语言 <span class="req">*</span></label>
            <select id="add-lang" v-model="newMusic.language" class="select">
              <option value="" disabled>请选择语言</option>
              <option value="中文">中文</option>
              <option value="粤语">粤语</option>
              <option value="上海语">上海语</option>
              <option value="英文">英文</option>
              <option value="日语">日语</option>
              <option value="韩语">韩语</option>
              <option value="法语">法语</option>
              <option value="德语">德语</option>
              <option value="俄语">俄语</option>
              <option value="纯音乐">纯音乐</option>
            </select>
          </div>
        </div>

        <div class="col">
          <div class="field">
            <label class="field__label" for="add-title">音乐名称 <span class="req">*</span></label>
            <NInput id="add-title" v-model="newMusic.title" placeholder="请输入音乐名称" />
            <p class="field__hint">自动从音频文件中读取。</p>
          </div>

          <div class="field">
            <label class="field__label" for="add-artist">艺术家 <span class="req">*</span></label>
            <NInput id="add-artist" v-model="newMusic.artist" placeholder="请输入艺术家" />
            <p class="field__hint">自动从音频文件中读取。</p>
          </div>

          <div class="field">
            <label class="field__label" for="add-tags">标签</label>
            <NInput id="add-tags" v-model="newMusic.tags" placeholder="多个标签用逗号分隔" />
          </div>

          <div class="field">
            <label class="field__label" for="add-album">专辑</label>
            <NInput id="add-album" v-model="newMusic.album" placeholder="请输入专辑" />
            <p class="field__hint">自动从音频文件中读取。</p>
          </div>

          <div class="field field--last">
            <label class="field__label">歌词文件 <span class="req">*</span></label>
            <input type="file" class="file" accept=".lrc" @change="handleLyricsFileChange" />
            <p v-if="newMusic.lyricsFileName" class="field__file">已选择：{{ newMusic.lyricsFileName }}</p>
            <p class="field__hint">请上传 .lrc 格式的歌词文件。</p>
          </div>
        </div>
      </div>

      <template #footer>
        <NButton variant="ghost" @click="closeAddModal">取消</NButton>
        <NButton variant="primary" icon="check" @click="addMusic">添加音乐</NButton>
      </template>
    </NModal>

    <!-- ==================== 编辑音乐 ==================== -->
    <NModal
      :model-value="!!editingMusic"
      title="编辑音乐"
      size="lg"
      @close="closeEditModal"
    >
      <div v-if="editingMusic" class="grid2">
        <div class="col">
          <div class="field">
            <label class="field__label">音乐图标</label>
            <input type="file" class="file" accept="image/*" @change="handleEditCoverFileChange" />
            <p v-if="editingMusic.coverFileName" class="field__file">已选择：{{ editingMusic.coverFileName }}</p>
            <p
              v-else-if="editingMusic.coverUrl && !editingMusic.coverUrl.startsWith('data:image')"
              class="field__file"
            >
              当前图标：{{ editingMusic.coverUrl.split('/').pop() }}
            </p>
          </div>

          <div class="field">
            <label class="field__label">音乐文件</label>
            <input type="file" class="file" accept=".mp3,.flac,.wav" @change="handleEditMusicFileChange" />
            <p v-if="editingMusic.fileName" class="field__file">已选择：{{ editingMusic.fileName }}</p>
            <p v-else-if="editingMusic.filePath" class="field__file">
              当前文件：{{ editingMusic.filePath.split('/').pop() }}
            </p>
            <p class="field__hint">可选；不选择时保留当前音乐文件。</p>
          </div>

          <div class="field field--last">
            <label class="field__label" for="edit-lang">语言</label>
            <select id="edit-lang" v-model="editingMusic.language" class="select">
              <option value="" disabled>请选择语言</option>
              <option value="中文">中文</option>
              <option value="粤语">粤语</option>
              <option value="上海语">上海语</option>
              <option value="英文">英文</option>
              <option value="日语">日语</option>
              <option value="韩语">韩语</option>
              <option value="法语">法语</option>
              <option value="德语">德语</option>
              <option value="俄语">俄语</option>
              <option value="纯音乐">纯音乐</option>
            </select>
          </div>
        </div>

        <div class="col">
          <div class="field">
            <label class="field__label" for="edit-title">音乐名称</label>
            <NInput id="edit-title" v-model="editingMusic.title" placeholder="请输入音乐名称" />
          </div>

          <div class="field">
            <label class="field__label" for="edit-artist">艺术家</label>
            <NInput id="edit-artist" v-model="editingMusic.artist" placeholder="请输入艺术家" />
          </div>

          <div class="field">
            <label class="field__label" for="edit-tags">标签</label>
            <NInput id="edit-tags" v-model="editingMusic.tags" placeholder="多个标签用逗号分隔" />
          </div>

          <div class="field">
            <label class="field__label" for="edit-album">专辑</label>
            <NInput id="edit-album" v-model="editingMusic.album" placeholder="请输入专辑" />
          </div>

          <div class="field field--last">
            <label class="field__label">歌词文件</label>
            <input type="file" class="file" accept=".lrc" @change="handleEditLyricsFileChange" />
            <p v-if="editingMusic.lyricsFileName" class="field__file">已选择：{{ editingMusic.lyricsFileName }}</p>
            <p class="field__hint">可选；不选择时保留当前歌词。选择后请使用 .lrc 格式。</p>
          </div>
        </div>
      </div>

      <template #footer>
        <NButton variant="ghost" @click="cancelEdit">取消</NButton>
        <NButton variant="primary" icon="check" @click="saveEdit">保存更改</NButton>
      </template>
    </NModal>

    <!-- ==================== 列表 ==================== -->
    <NCard pad="lg" class="card">
      <header class="card__head">
        <h2 class="card__title">音乐列表</h2>
        <div class="card__search-panel">
          <NInput
            v-model="searchQuery"
            icon="search"
            placeholder="例如：id=148  artist=周杰伦  language=中文"
            clearable
            class="card__search"
            @update:model-value="updateSearchResults"
          />
          <p class="card__search-hint">
            支持 id、title、artist、album、language、tag、user、quality，多条件用空格分隔
          </p>
        </div>
      </header>

      <div v-if="isLoading" class="state">
        <NSpinner :size="26" />
      </div>

      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>封面</th>
              <th>音乐名称</th>
              <th>艺术家</th>
              <th>专辑</th>
              <th>时长</th>
              <th>上传时间</th>
              <th aria-label="操作" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="music in paginatedMusicList()" :key="music.id">
              <td class="cell-dim">{{ music.id }}</td>
              <td>
                <img
                  :src="getCoverUrl(music.id)"
                  :alt="music.title"
                  class="cell-cover"
                  loading="lazy"
                  @error="handleImageError"
                />
              </td>
              <td class="cell-strong">{{ music.title }}</td>
              <td>{{ music.artist }}</td>
              <td class="cell-dim">{{ music.album }}</td>
              <td class="cell-dim">{{ formatDuration(music.duration) }}</td>
              <td class="cell-dim">{{ formatDate(music.createdAt) }}</td>
              <td class="cell-actions">
                <NButton
                  v-if="canEditMusic"
                  size="sm"
                  variant="secondary"
                  icon="pencil"
                  @click="editMusic(music)"
                >
                  编辑
                </NButton>
                <NButton
                  v-if="canDeleteMusic"
                  size="sm"
                  variant="danger"
                  icon="trash-2"
                  @click="deleteMusic(music.id)"
                >
                  删除
                </NButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="!isLoading && filteredMusicList.length === 0" class="state-empty">
        <NIcon name="music" :size="22" />
        暂无音乐数据
      </p>

      <div v-if="!isLoading && filteredMusicList.length > 0" class="pager">
        <p class="pager__info">
          显示第 {{ (currentPage - 1) * pageSize + 1 }} -
          {{ Math.min(currentPage * pageSize, filteredMusicList.length) }} 条，共
          {{ filteredMusicList.length }} 条
        </p>
        <div class="pager__controls">
          <NButton size="sm" variant="secondary" :disabled="currentPage === 1" @click="prevPage">
            上一页
          </NButton>
          <div class="pager__pages">
            <button
              v-for="page in Math.min(totalPages, 5)"
              :key="page"
              type="button"
              class="pager__page"
              :class="{ 'pager__page--active': currentPage === getDisplayPage(page) }"
              @click="goToPage(getDisplayPage(page))"
            >
              {{ getDisplayPage(page) }}
            </button>
          </div>
          <NButton
            size="sm"
            variant="secondary"
            :disabled="currentPage === totalPages"
            @click="nextPage"
          >
            下一页
          </NButton>
        </div>
      </div>
    </NCard>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from '@/composables/useToast'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NInput, NModal, NSpinner, NTag } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'

const toast = useToast()

const router = useRouter()

// 切换侧边栏

// 添加文件处理函数
const handleMusicFileChange = async (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为支持的格式：MP3、FLAC、WAV
    const fileName = file.name.toLowerCase()
    const fileExtension = fileName.split('.').pop()

    // 主要依赖文件扩展名来识别格式
    const isValidFormat = ['.mp3', '.flac', '.wav'].includes('.' + fileExtension)

    if (!isValidFormat) {
      toast.error('请选择 MP3、FLAC 或 WAV 格式的音乐文件')
      event.target.value = '' // 清空选择
      return
    }


    newMusic.value.file = file
    newMusic.value.fileName = file.name

    // 读取音频时长
    const audio = new Audio()
    audio.src = URL.createObjectURL(file)
    audio.onloadedmetadata = async () => {
      newMusic.value.duration = Math.floor(audio.duration)

      // 根据文件扩展名解析元数据
      if (fileExtension === 'mp3') {
        await parseMP3Metadata(file)
      } else if (fileExtension === 'flac') {
        await parseFlacMetadata(file)
      } else if (fileExtension === 'wav') {
        await parseWavMetadata(file)
      }
      URL.revokeObjectURL(audio.src) // 释放对象URL
    }
  }
}

// 解析MP3文件的元数据
const parseMP3Metadata = async (file) => {
  try {
    const arrayBuffer = await file.arrayBuffer()
    const dataView = new DataView(arrayBuffer)

    // 检查文件头
    const header = dataView.getString(0, 3)

    // 查找ID3v2标签
    if (header === 'ID3') {
      const version = dataView.getUint8(3)
      const revision = dataView.getUint8(4)
      const flags = dataView.getUint8(5)
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
        const frameFlags = dataView.getUint16(offset + 8)


        if (frameSize === 0) {
          break
        }

        // 跳过帧头
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

          const picOffset = frameDataOffset
          const textEncoding = dataView.getUint8(picOffset)

          let currentOffset = picOffset + 1

          // 读取MIME类型
          let mimeTypeEnd = currentOffset
          while (dataView.getUint8(mimeTypeEnd) !== 0) {
            mimeTypeEnd++
          }
          const mimeType = dataView.getString(currentOffset, mimeTypeEnd - currentOffset)
          currentOffset = mimeTypeEnd + 1

          // 跳过图片类型
          const pictureType = dataView.getUint8(currentOffset)
          currentOffset += 1

          // 读取描述
          let descEnd = currentOffset
          while (dataView.getUint8(descEnd) !== 0) {
            descEnd++
          }
          const description = dataView.decodeTextString(currentOffset, descEnd - currentOffset, textEncoding)
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
      if (metadata.title) {
        newMusic.value.title = metadata.title
      }
      if (metadata.artist) {
        newMusic.value.artist = metadata.artist
      }
      if (metadata.album) {
        newMusic.value.album = metadata.album
      }
      if (metadata.cover) {
        newMusic.value.coverFile = new File([metadata.cover], 'cover.jpg', { type: metadata.cover.type })
        newMusic.value.coverFileName = 'cover.jpg'
      }

      toast.success('已自动解析MP3文件信息')
    } else {
      toast.warning('该MP3文件不包含ID3标签，无法自动解析信息')
    }
  } catch (error) {
    console.error('解析MP3元数据失败:', error)
    console.error('错误详情:', error.stack)
    toast.warning('无法自动解析MP3文件信息，请手动填写')
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

// 解码文本帧（处理不同的编码）
DataView.prototype.decodeTextFrame = function(offset, length) {
  if (length === 0) return ''

  const encoding = this.getUint8(offset)
  const textData = new Uint8Array(this.buffer, this.byteOffset + offset + 1, length - 1)

  switch (encoding) {
    case 0: // ISO-8859-1
      return this.decodeISO88591(textData)
    case 1: // UTF-16 with BOM
      return this.decodeUTF16(textData)
    case 2: // UTF-16BE without BOM
      return this.decodeUTF16BE(textData)
    case 3: // UTF-8
      return this.decodeUTF8(textData)
    default:
      console.warn('未知编码:', encoding, '使用ISO-8859-1')
      return this.decodeISO88591(textData)
  }
}

// 解码ISO-8859-1编码
DataView.prototype.decodeISO88591 = function(data) {
  let result = ''
  for (let i = 0; i < data.length; i++) {
    result += String.fromCharCode(data[i])
  }
  return result
}

// 解码UTF-16 with BOM
DataView.prototype.decodeUTF16 = function(data) {
  if (data.length < 2) return ''

  const bom = (data[0] << 8) | data[1]

  if (bom === 0xFEFF) {
    // Big Endian
    return this.decodeUTF16BE(data)
  } else if (bom === 0xFFFE) {
    // Little Endian
    return this.decodeUTF16LE(data)
  } else {
    // 无BOM，假设Big Endian
    return this.decodeUTF16BE(data)
  }
}

// 解码UTF-16BE
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

// 解码UTF-16LE
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

// 解码UTF-8
DataView.prototype.decodeUTF8 = function(data) {
  let result = ''
  let i = 0

  while (i < data.length) {
    const byte1 = data[i]

    if (byte1 === 0) break

    if (byte1 < 0x80) {
      // 1字节
      result += String.fromCharCode(byte1)
      i++
    } else if ((byte1 & 0xE0) === 0xC0) {
      // 2字节
      if (i + 1 < data.length) {
        const codePoint = ((byte1 & 0x1F) << 6) | (data[i + 1] & 0x3F)
        result += String.fromCharCode(codePoint)
        i += 2
      } else {
        i++
      }
    } else if ((byte1 & 0xF0) === 0xE0) {
      // 3字节
      if (i + 2 < data.length) {
        const codePoint = ((byte1 & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)
        result += String.fromCharCode(codePoint)
        i += 3
      } else {
        i++
      }
    } else if ((byte1 & 0xF8) === 0xF0) {
      // 4字节
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

// 解码文本字符串（带编码）
DataView.prototype.decodeTextString = function(offset, length, encoding) {
  const textData = new Uint8Array(this.buffer, this.byteOffset + offset, length)

  switch (encoding) {
    case 0: // ISO-8859-1
      return this.decodeISO88591(textData)
    case 1: // UTF-16 with BOM
      return this.decodeUTF16(textData)
    case 2: // UTF-16BE without BOM
      return this.decodeUTF16BE(textData)
    case 3: // UTF-8
      return this.decodeUTF8(textData)
    default:
      return this.decodeISO88591(textData)
  }
}

// 解析FLAC文件的元数据
const parseFlacMetadata = async (file, targetMusic = newMusic) => {
  try {
    const arrayBuffer = await file.arrayBuffer()
    const dataView = new DataView(arrayBuffer)
    const textDecoder = new TextDecoder('utf-8')

    // 检查FLAC文件头（fLaC）
    // FLAC文件头: 0x66 0x4C 0x61 0x43 ('fLaC')
    const byte0 = dataView.getUint8(0)
    const byte1 = dataView.getUint8(1)
    const byte2 = dataView.getUint8(2)
    const byte3 = dataView.getUint8(3)

    const header = String.fromCharCode(byte0, byte1, byte2, byte3)

    if (header !== 'fLaC') {
      toast.warning('该FLAC文件不包含元数据标签，无法自动解析信息')
      return
    }


    // FLAC元数据块解析
    let offset = 4 // 跳过"fLaC"标识
    const metadata = {
      title: '',
      artist: '',
      album: '',
      cover: null
    }

    let blockCount = 0
    const maxBlocks = 100 // 防止无限循环


    // 遍历元数据块
    while (offset < arrayBuffer.byteLength && blockCount < maxBlocks) {
      blockCount++


      // 读取块头
      const blockHeader = dataView.getUint8(offset)
      const isLast = (blockHeader & 0x80) !== 0
      const blockType = blockHeader & 0x7F

      // 块大小是3字节，需要正确读取
      const byte1 = dataView.getUint8(offset + 1)
      const byte2 = dataView.getUint8(offset + 2)
      const byte3 = dataView.getUint8(offset + 3)
      const blockSize = (byte1 << 16) | (byte2 << 8) | byte3


      offset += 4 // 跳过块头

      // 检查是否超出文件范围
      if (offset + blockSize > arrayBuffer.byteLength) {
        console.warn('块大小超出文件范围，停止解析')
        break
      }

      // VORBIS_COMMENT块（类型4）包含元数据
      if (blockType === 4) {

        // 读取vendor length和vendor string
        let dataOffset = 0
        const vendorLength = dataView.getUint32(offset + dataOffset, true)
        dataOffset += 4

        dataOffset += vendorLength // 跳过vendor string

        // 读取comments count
        const commentsCount = dataView.getUint32(offset + dataOffset, true)
        dataOffset += 4


        // 解析每个comment
        for (let i = 0; i < commentsCount; i++) {
          const commentLength = dataView.getUint32(offset + dataOffset, true)
          dataOffset += 4

          if (dataOffset + commentLength > blockSize) {
            console.warn('注释长度超出块范围，停止解析')
            break
          }

          // 使用 TextDecoder 解码 UTF-8 字符串
          const commentBytes = new Uint8Array(arrayBuffer, offset + dataOffset, commentLength)
          const comment = textDecoder.decode(commentBytes)
          dataOffset += commentLength


          // 解析comment格式: FIELD=value
          const equalIndex = comment.indexOf('=')
          if (equalIndex !== -1) {
            const field = comment.substring(0, equalIndex).toUpperCase()
            const value = comment.substring(equalIndex + 1)


            if (field === 'TITLE') {
              metadata.title = value
            } else if (field === 'ARTIST') {
              metadata.artist = value
            } else if (field === 'ALBUM') {
              metadata.album = value
            }
          }
        }
      }
      // PICTURE块（类型6）包含封面图片
      else if (blockType === 6) {

        let picOffset = offset

        // 读取图片类型（大端序）
        const pictureType = dataView.getUint32(picOffset, false)
        picOffset += 4

        // 读取MIME类型长度和MIME类型（大端序）
        const mimeLength = dataView.getUint32(picOffset, false)
        picOffset += 4

        if (picOffset + mimeLength > offset + blockSize) {
          console.warn('>>> MIME类型长度超出块范围')
        } else {
          // 使用 TextDecoder 解码 MIME 类型
          const mimeBytes = new Uint8Array(arrayBuffer, picOffset, mimeLength)
          const mimeType = textDecoder.decode(mimeBytes)
          picOffset += mimeLength

          // 读取描述长度和描述（大端序）
          const descLength = dataView.getUint32(picOffset, false)
          picOffset += 4
          picOffset += descLength // 跳过描述

          // 读取宽度、高度、颜色深度、颜色数（大端序）
          const width = dataView.getUint32(picOffset, false)
          picOffset += 4
          const height = dataView.getUint32(picOffset, false)
          picOffset += 4
          const colorDepth = dataView.getUint32(picOffset, false)
          picOffset += 4
          const colorCount = dataView.getUint32(picOffset, false)
          picOffset += 4

          // 读取图片数据长度和图片数据（大端序）
          const pictureLength = dataView.getUint32(picOffset, false)
          picOffset += 4


          if (pictureLength > 0 && picOffset + pictureLength <= offset + blockSize) {
            const imageData = new Uint8Array(arrayBuffer, picOffset, pictureLength)
            metadata.cover = new Blob([imageData], { type: mimeType })
          } else {
            console.warn(`>>> ❌ 封面图片数据超出范围: ${picOffset + pictureLength} > ${offset + blockSize}`)
          }
        }
      }
      // 其他块类型
      else {
      }

      offset += blockSize

      if (isLast) {
        break
      }
    }

    // 自动填充表单

    if (metadata.title) {
      targetMusic.value.title = metadata.title
    } else {
    }
    if (metadata.artist) {
      targetMusic.value.artist = metadata.artist
    } else {
    }
    if (metadata.album) {
      targetMusic.value.album = metadata.album
    } else {
    }
    if (metadata.cover) {
      targetMusic.value.coverFile = new File([metadata.cover], 'cover.jpg', { type: metadata.cover.type })
      targetMusic.value.coverFileName = 'cover.jpg'
    } else {
    }

    toast.success('已自动解析FLAC文件信息')
  } catch (error) {
    console.error('解析FLAC元数据失败:', error)
    console.error('错误详情:', error.stack)
    toast.warning('无法自动解析FLAC文件信息，请手动填写')
  }
}

// 解析WAV文件的元数据（WAV格式通常不包含ID3标签，但有一些变种支持）
const parseWavMetadata = async (file) => {
  try {
    const arrayBuffer = await file.arrayBuffer()
    const dataView = new DataView(arrayBuffer)

    // 检查RIFF文件头
    const riffHeader = dataView.getString(0, 4)
    if (riffHeader !== 'RIFF') {
      toast.warning('该WAV文件不包含元数据标签，无法自动解析信息')
      return
    }

    // 检查WAVE格式
    const waveHeader = dataView.getString(8, 4)
    if (waveHeader !== 'WAVE') {
      toast.warning('该WAV文件不包含元数据标签，无法自动解析信息')
      return
    }


    // WAV文件通常不包含ID3标签，但有些变种支持INFO LIST或ID3标签
    // 这里我们尝试查找ID3标签（某些WAV文件可能包含）
    let offset = 12 // 跳过RIFF头

    // 查找LIST INFO块
    while (offset < arrayBuffer.length - 8) {
      const chunkId = dataView.getString(offset, 4)
      const chunkSize = dataView.getUint32(offset + 4, true)


      if (chunkId === 'LIST') {
        const listType = dataView.getString(offset + 8, 4)
        if (listType === 'INFO') {
          const metadata = {
            title: '',
            artist: '',
            album: ''
          }

          let infoOffset = offset + 12
          const infoEnd = offset + 8 + chunkSize

          while (infoOffset < infoEnd - 8) {
            const infoId = dataView.getString(infoOffset, 4)
            const infoSize = dataView.getUint32(infoOffset + 4, true)
            infoOffset += 8

            if (infoId === 'INAM') {
              // Title
              const titleBytes = new Uint8Array(arrayBuffer, infoOffset, infoSize)
              metadata.title = dataView.decodeUTF8(titleBytes).replace(/\0/g, '')
            } else if (infoId === 'IART') {
              // Artist
              const artistBytes = new Uint8Array(arrayBuffer, infoOffset, infoSize)
              metadata.artist = dataView.decodeUTF8(artistBytes).replace(/\0/g, '')
            } else if (infoId === 'IPRD') {
              // Album
              const albumBytes = new Uint8Array(arrayBuffer, infoOffset, infoSize)
              metadata.album = dataView.decodeUTF8(albumBytes).replace(/\0/g, '')
            }

            infoOffset += infoSize
            // 对齐到偶数字节
            if (infoSize % 2 !== 0) {
              infoOffset++
            }
          }

          // 自动填充表单
          if (metadata.title) {
            newMusic.value.title = metadata.title
          }
          if (metadata.artist) {
            newMusic.value.artist = metadata.artist
          }
          if (metadata.album) {
            newMusic.value.album = metadata.album
          }

          toast.success('已自动解析WAV文件信息')
          return
        }
      } else if (chunkId === 'ID3 ' || chunkId === 'id3 ') {
        // 某些WAV文件可能包含ID3标签
        // 可以复用parseMP3Metadata的逻辑
        const id3Data = arrayBuffer.slice(offset + 8, offset + 8 + chunkSize)
        // 这里简化处理，WAV的ID3标签较少见
        toast.warning('该WAV文件包含ID3标签，但暂不支持解析')
        return
      }

      offset += 8 + chunkSize
      // 对齐到偶数字节
      if (chunkSize % 2 !== 0) {
        offset++
      }
    }

    toast.warning('该WAV文件不包含元数据标签，无法自动解析信息')
  } catch (error) {
    console.error('解析WAV元数据失败:', error)
    console.error('错误详情:', error.stack)
    toast.warning('无法自动解析WAV文件信息，请手动填写')
  }
}

const handleCoverFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为图片
    if (!file.type.startsWith('image/')) {
      toast.error('请选择图片格式的图标文件')
      event.target.value = '' // 清空选择
      return
    }
    
    newMusic.value.coverFile = file
    newMusic.value.coverFileName = file.name
  }
}

const handleEditMusicFileChange = async (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为支持的格式：MP3、FLAC、WAV
    const fileName = file.name.toLowerCase()
    const fileExtension = fileName.split('.').pop()

    // 主要依赖文件扩展名来识别格式
    const isValidFormat = ['.mp3', '.flac', '.wav'].includes('.' + fileExtension)

    if (!isValidFormat) {
      toast.error('请选择 MP3、FLAC 或 WAV 格式的音乐文件')
      event.target.value = '' // 清空选择
      return
    }


    editingMusic.value.file = file
    editingMusic.value.fileName = file.name

    // 读取音频时长
    const audio = new Audio()
    audio.src = URL.createObjectURL(file)
    audio.onloadedmetadata = async () => {
      editingMusic.value.duration = Math.floor(audio.duration)

      // 根据文件扩展名解析元数据
      if (fileExtension === 'mp3') {
        await parseMP3Metadata(file, editingMusic)
      } else if (fileExtension === 'flac') {
        await parseFlacMetadata(file, editingMusic)
      } else if (fileExtension === 'wav') {
        await parseWavMetadata(file, editingMusic)
      }
      URL.revokeObjectURL(audio.src) // 释放对象URL
    }
  }
}

const handleEditCoverFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为图片
    if (!file.type.startsWith('image/')) {
      toast.error('请选择图片格式的图标文件')
      event.target.value = '' // 清空选择
      return
    }
    
    editingMusic.value.coverFile = file
    editingMusic.value.coverFileName = file.name
  }
}

const handleLyricsFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为LRC
    if (!file.name.toLowerCase().endsWith('.lrc')) {
      toast.error('请选择LRC格式的歌词文件')
      event.target.value = '' // 清空选择
      return
    }
    
    newMusic.value.lyricsFile = file
    newMusic.value.lyricsFileName = file.name
  }
}

const handleEditLyricsFileChange = (event) => {
  const file = event.target.files[0]
  if (file) {
    // 检查文件类型是否为LRC
    if (!file.name.toLowerCase().endsWith('.lrc')) {
      toast.error('请选择LRC格式的歌词文件')
      event.target.value = '' // 清空选择
      return
    }
    
    editingMusic.value.lyricsFile = file
    editingMusic.value.lyricsFileName = file.name
  }
}

// 管理员信息
const adminInfo = ref({})

// 检查管理员登录状态
onMounted(() => {
  const storedToken = localStorage.getItem('adminToken')
  const storedAdminInfo = localStorage.getItem('adminInfo')
  
  if (storedToken && storedAdminInfo) {
    try {
      const parsedInfo = JSON.parse(storedAdminInfo)
      adminInfo.value = parsedInfo
    } catch (e) {
      console.error('解析管理员信息失败:', e)
      router.push('/admin/login')
    }
  } else {
    router.push('/admin/login')
  }
  
  // 获取音乐列表
  fetchMusicList()
})

// 权限检查计算属性
const canEditMusic = ref(false)
const canDeleteMusic = ref(false)
const canAddMusic = ref(false)

// 根据管理员角色更新权限
watch(adminInfo, (newAdminInfo) => {
  const role = newAdminInfo.role || 'admin'
  
  if (role === 'super_admin') {
    // 超级管理员拥有所有权限
    canEditMusic.value = true
    canDeleteMusic.value = true
    canAddMusic.value = true
  } else if (role === 'admin') {
    // 管理员拥有音乐管理的所有权限
    canEditMusic.value = true
    canDeleteMusic.value = true
    canAddMusic.value = true
  } else if (role === 'auditor') {
    // 审核员没有音乐管理权限
    canEditMusic.value = false
    canDeleteMusic.value = false
    canAddMusic.value = false
  }
}, { immediate: true })

// 音乐数据
const musicList = ref([])
const filteredMusicList = ref([])

// 分页相关数据
const currentPage = ref(1)
const pageSize = ref(100)
const totalPages = ref(1)

// 表单状态
const showAddForm = ref(false)
const newMusic = ref({
  title: '',
  artist: '',
  language: '',
  album: '',
  duration: 0,
  filePath: '',
  uploadUserId: 0,
  file: null,
  fileName: '',
  coverFile: null,
  coverFileName: '',
  lyricsFile: null,
  lyricsFileName: ''
})
const editingMusic = ref(null)
const editOriginalMusic = ref(null)
const searchQuery = ref('')
const isLoading = ref(false)

// 模态框引用
const editModalRef = ref(null)
const addModalRef = ref(null)
const modalPosition = ref({ x: 0, y: 0 })
const isDragging = ref(false)
const dragOffset = ref({ x: 0, y: 0 })

// 获取音乐列表
const fetchMusicList = async () => {
  isLoading.value = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/list`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      }
    })

    const data = await response.json()
    if (data.success) {
      // 将API返回的音乐数据转换为前端可用的格式
      musicList.value = data.data.map(music => ({
        ...music,
        uploadTime: new Date(music.createdAt) // 转换日期格式
      }))
      updateSearchResults()
    } else {
      console.error('获取音乐列表失败:', data.message)
      toast.error(data.message || '获取音乐列表失败')
    }
  } catch (error) {
    console.error('获取音乐列表时出错:', error)
    toast.error('获取音乐列表时出错')
  } finally {
    isLoading.value = false
  }
}

// 添加音乐
const addMusic = async () => {
  if (!newMusic.value.title || !newMusic.value.artist || !newMusic.value.language) {
    toast.error('请填写音乐名称、艺术家和语言')
    return
  }
  
  if (!newMusic.value.file) {
    toast.error('请选择音乐文件')
    return
  }
  
  // 验证歌词文件必填
  if (!newMusic.value.lyricsFile) {
    toast.error('请选择歌词文件（必填项）')
    return
  }
  
  // 创建FormData对象，用于上传文件
  const formData = new FormData()
  formData.append('title', newMusic.value.title)
  formData.append('artist', newMusic.value.artist)
  formData.append('language', newMusic.value.language)
  formData.append('tags', newMusic.value.tags || '')
  formData.append('album', newMusic.value.album || '未知专辑')
  formData.append('duration', newMusic.value.duration || 0)
  formData.append('uploadUserId', newMusic.value.uploadUserId || 0)
  formData.append('musicFile', newMusic.value.file)
  formData.append('lyricsFile', newMusic.value.lyricsFile)
  
  if (newMusic.value.coverFile) {
    formData.append('coverFile', newMusic.value.coverFile)
  }
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/upload`, {
      method: 'POST',
      headers: {
        // 注意：使用FormData时，不要设置Content-Type，浏览器会自动设置
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: formData
    })
    
    const data = await response.json()
    if (data.success) {
      // 添加新音乐到列表顶部
      musicList.value.unshift({
        ...data.data,
        uploadTime: new Date(data.data.createdAt)
      })
      updateSearchResults()
      resetForm()
      toast.success('添加音乐成功')
    } else {
      console.error('添加音乐失败:', data.message)
      toast.error(data.message || '添加音乐失败')
    }
  } catch (error) {
    console.error('添加音乐时出错:', error)
    toast.error('添加音乐时出错')
  }
}

// 重置表单
const resetForm = () => {
  newMusic.value = {
    title: '',
    artist: '',
    language: '',
    album: '',
    duration: 0,
    filePath: '',
    uploadUserId: 0,
    file: null,
    fileName: '',
    coverFile: null,
    coverFileName: '',
    lyricsFile: null,
    lyricsFileName: ''
  }
  showAddForm.value = false
}

// 关闭添加音乐模态框
const closeAddModal = () => {
  resetForm()
}

// 编辑音乐
const editMusic = async (music) => {
  editingMusic.value = { ...music, lyricsFile: null, lyricsFileName: '' } // 复制音乐对象以避免直接修改原数据
  editOriginalMusic.value = {
    title: music.title ?? '',
    artist: music.artist ?? '',
    language: music.language ?? '',
    tags: music.tags ?? '',
    album: music.album ?? ''
  }
}

// 关闭编辑悬浮窗
const closeEditModal = () => {
  editingMusic.value = null
  editOriginalMusic.value = null
}

// 开始拖动悬浮窗
const startDrag = (e) => {
  if (e.target.classList.contains('close-btn')) return // 防止在关闭按钮上拖动
  
  isDragging.value = true
  const rect = editModalRef.value.getBoundingClientRect()
  // 计算鼠标相对于悬浮窗的偏移量
  dragOffset.value = {
    x: e.clientX - rect.x,  // 使用rect.x而不是rect.left
    y: e.clientY - rect.y   // 使用rect.y而不是rect.top
  }
  
  // 添加事件监听器
  const handleMouseMove = (e) => {
    if (!isDragging.value) return
    
    // 根据鼠标位置和偏移量计算新位置
    let x = e.clientX - dragOffset.value.x
    let y = e.clientY - dragOffset.value.y
    
    // 确保悬浮窗在屏幕范围内
    const maxX = window.innerWidth - editModalRef.value.offsetWidth
    const maxY = window.innerHeight - editModalRef.value.offsetHeight
    
    // 确保不小于0，不大于最大值
    x = Math.max(0, Math.min(x, maxX))
    y = Math.max(0, Math.min(y, maxY))
    
    modalPosition.value = { x, y }
  }
  
  const handleMouseUp = () => {
    isDragging.value = false
    // 移除事件监听器
    document.removeEventListener('mousemove', handleMouseMove)
    document.removeEventListener('mouseup', handleMouseUp)
  }
  
  // 添加事件监听器
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
  
  // 阻止默认行为（如选择文本）
  e.preventDefault()
}

// 停止拖动
const stopDrag = () => {
  isDragging.value = false
}

// 保存编辑
const saveEdit = async () => {
  try {
    // 创建FormData对象，用于上传文件
    const formData = new FormData()
    formData.append('id', editingMusic.value.id)

    // 只提交实际修改的文本字段，未提交的字段由后端保持原值。
    for (const field of ['title', 'artist', 'language', 'tags', 'album']) {
      const currentValue = editingMusic.value[field] ?? ''
      const originalValue = editOriginalMusic.value?.[field] ?? ''
      if (currentValue !== originalValue) {
        formData.append(field, currentValue)
      }
    }
    
    if (editingMusic.value.file) {
      formData.append('musicFile', editingMusic.value.file)
      formData.append('duration', editingMusic.value.duration ?? 0)
    }
    
    if (editingMusic.value.coverFile) {
      formData.append('coverFile', editingMusic.value.coverFile)
    }

    if (editingMusic.value.lyricsFile) {
      formData.append('lyricsFile', editingMusic.value.lyricsFile)
    }
    
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/upload`, {
      method: 'PUT',
      headers: {
        // 注意：使用FormData时，不要设置Content-Type，浏览器会自动设置
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: formData
    })
    
    const data = await response.json()
    if (data.success) {
      // 更新本地列表
      const index = musicList.value.findIndex(m => m.id === editingMusic.value.id)
      if (index !== -1) {
        musicList.value[index] = {
          ...data.data,
          uploadTime: new Date(data.data.createdAt)
        }
        updateSearchResults()
      }
      editingMusic.value = null
      editOriginalMusic.value = null
      toast.success('编辑音乐成功')
    } else {
      console.error('编辑音乐失败:', data.message)
      toast.error(data.message || '编辑音乐失败')
    }
  } catch (error) {
    console.error('编辑音乐时出错:', error)
    toast.error('编辑音乐时出错')
  }
}

// 取消编辑
const cancelEdit = () => {
  editingMusic.value = null
  editOriginalMusic.value = null
}

// 删除音乐
const deleteMusic = async (id) => {
  if (confirm('确定要删除这首音乐吗？此操作不可撤销。')) {
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/delete/${id}`, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
        }
      })
      
      const data = await response.json()
      if (data.success) {
        musicList.value = musicList.value.filter(music => music.id !== id)
        updateSearchResults()
        toast.success('删除音乐成功')
      } else {
        console.error('删除音乐失败:', data.message)
        toast.error(data.message || '删除音乐失败')
      }
    } catch (error) {
      console.error('删除音乐时出错:', error)
      toast.error('删除音乐时出错')
    }
  }
}

// 格式化日期
const formatDate = (date) => {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

// 格式化音乐时长为 xx分xx秒 格式
const formatDuration = (duration) => {
  if (!duration || duration < 0) return '0分0秒'
  
  const minutes = Math.floor(duration / 60)
  const seconds = duration % 60
  
  return `${minutes}分${seconds}秒`
}

// 使用API搜索音乐
const searchMusicAPI = async (query) => {
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: JSON.stringify({ query: query })
    })
    
    const data = await response.json()
    if (data.success && data.results) {
      // 将API返回的音乐数据转换为前端可用的格式
      return data.results.map(music => ({
        ...music,
        uploadTime: new Date(music.createdAt) // 转换日期格式
      }))
    } else {
      console.error('搜索音乐失败:', data.message)
      return []
    }
  } catch (error) {
    console.error('搜索音乐时出错:', error)
    return []
  }
}

const MUSIC_SEARCH_FIELD_ALIASES = {
  id: 'id',
  title: 'title',
  name: 'title',
  song: 'title',
  artist: 'artist',
  singer: 'artist',
  album: 'album',
  language: 'language',
  lang: 'language',
  tag: 'tags',
  tags: 'tags',
  user: 'uploadUserId',
  uploader: 'uploadUserId',
  quality: 'quality'
}

const MUSIC_SEARCH_TEXT_FIELDS = [
  'id',
  'title',
  'artist',
  'album',
  'language',
  'tags',
  'uploadUserId',
  'quality'
]

const parseMusicSearchQuery = (query) => {
  const raw = typeof query === 'string' ? query.trim() : ''
  if (!raw) return { filters: [], freeText: [] }

  const filters = []
  const freeText = []
  for (const token of raw.replace(/\s*=\s*/g, '=').split(/\s+/)) {
    const separator = token.indexOf('=')
    if (separator > 0) {
      const key = token.slice(0, separator).toLowerCase()
      const value = token.slice(separator + 1).trim()
      const field = MUSIC_SEARCH_FIELD_ALIASES[key]
      if (field && value) {
        filters.push({ key: field, value })
        continue
      }
    }
    if (token) freeText.push(token.toLowerCase())
  }
  return { filters, freeText }
}

const getMusicSearchFieldValue = (music, field) => {
  if (field === 'tags') return String(music.tags ?? '')
  if (field === 'quality') return String(music.maxQuality ?? '')
  return String(music[field] ?? '')
}

const matchesMusicSearch = (music, search) => {
  const filtersMatch = search.filters.every(({ key, value }) => {
    const actual = getMusicSearchFieldValue(music, key).toLowerCase()
    const expected = value.toLowerCase()
    return key === 'id' || key === 'uploadUserId' ? actual === expected : actual.includes(expected)
  })
  if (!filtersMatch) return false

  return search.freeText.every((keyword) =>
    MUSIC_SEARCH_TEXT_FIELDS.some((field) =>
      getMusicSearchFieldValue(music, field).toLowerCase().includes(keyword)
    )
  )
}

// 根据搜索语法过滤音乐列表
const updateSearchResults = async () => {
  const query = searchQuery.value.trim()
  if (!query) {
    filteredMusicList.value = [...musicList.value]
  } else {
    const search = parseMusicSearchQuery(query)
    filteredMusicList.value = musicList.value.filter(music => matchesMusicSearch(music, search))
  }
  // 重新计算总页数并重置到第一页
  totalPages.value = Math.ceil(filteredMusicList.value.length / pageSize.value)
  currentPage.value = 1
}

// 监听搜索查询变化
searchQuery.value = ''
updateSearchResults()

// 计算当前页的音乐列表
const paginatedMusicList = () => {
  const startIndex = (currentPage.value - 1) * pageSize.value
  const endIndex = startIndex + pageSize.value
  return filteredMusicList.value.slice(startIndex, endIndex)
}

// 上一页
const prevPage = () => {
  if (currentPage.value > 1) {
    currentPage.value--
  }
}

// 下一页
const nextPage = () => {
  if (currentPage.value < totalPages.value) {
    currentPage.value++
  }
}

// 跳转到指定页
const goToPage = (page) => {
  if (page >= 1 && page <= totalPages.value) {
    currentPage.value = page
  }
}

// 获取显示的页码（用于处理页码显示逻辑）
const getDisplayPage = (index) => {
  const maxVisible = 5
  if (totalPages.value <= maxVisible) {
    return index
  }

  const half = Math.floor(maxVisible / 2)
  let start = Math.max(1, currentPage.value - half)
  let end = Math.min(totalPages.value, start + maxVisible - 1)

  if (end - start < maxVisible - 1) {
    start = Math.max(1, end - maxVisible + 1)
  }

  return start + index - 1
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  // 返回新的API端点，通过音乐ID获取封面
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  // 如果图片加载失败，使用后端API的默认图标
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`;
}

</script>

<style scoped>
.subpage {
  width: 100%;
}

.subpage__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-6);
}

.subpage__title {
  margin: 0 0 var(--n-space-1);
  font-size: clamp(1.25rem, 2.6vw, 1.6rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.02em;
  color: var(--n-text);
}

.subpage__desc {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== 卡片 ==================== */
.card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-5);
}

.card__title {
  margin: 0;
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
}

.card__search-panel {
  flex: 1 1 320px;
  max-width: 560px;
  min-width: 0;
}

.card__search {
  width: 100%;
}

.card__search-hint {
  margin: var(--n-space-1) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.state {
  display: flex;
  justify-content: center;
  padding: var(--n-space-12) 0;
}

.state-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-2);
  padding: var(--n-space-12) 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

/* ==================== 表格 ==================== */
.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-surface-sunken);
}

.table {
  width: 100%;
  border-collapse: collapse;
  color: var(--n-text);
  font-size: var(--n-text-sm);
}

.table th,
.table td {
  padding: var(--n-space-3) var(--n-space-4);
  text-align: left;
  border-bottom: 1px solid var(--n-line-subtle);
  vertical-align: middle;
  white-space: nowrap;
}

.table th {
  background: var(--n-accent-soft);
  font-weight: var(--n-weight-semibold);
}

.table tbody tr:last-child td {
  border-bottom: none;
}

@media (hover: hover) {
  .table tbody tr:hover td {
    background: var(--n-surface-soft);
  }
}

.cell-dim {
  color: var(--n-text-muted);
}

.cell-strong {
  color: var(--n-text);
  font-weight: var(--n-weight-medium);
}

.cell-cover {
  width: 44px;
  height: 44px;
  border-radius: var(--n-radius-xs);
  object-fit: cover;
  border: 1px solid var(--n-line-subtle);
  display: block;
}

.cell-actions {
  display: flex;
  gap: var(--n-space-2);
}

/* ==================== 分页 ==================== */
.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-top: var(--n-space-5);
}

.pager__info {
  margin: 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-variant-numeric: tabular-nums;
}

.pager__controls {
  display: flex;
  flex-wrap: wrap; /* 手机上「上一页 + 5 个页码 + 下一页」会超出卡片，必须能换行 */
  align-items: center;
  justify-content: center;
  gap: var(--n-space-3);
}

.pager__pages {
  display: flex;
  gap: var(--n-space-1);
}

.pager__page {
  min-width: 30px;
  height: 30px;
  padding: 0 8px;
  border-radius: var(--n-radius-xs);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-variant-numeric: tabular-nums;
  transition: background var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .pager__page:not(.pager__page--active):hover {
    background: var(--n-surface-soft);
    color: var(--n-text);
  }
}

.pager__page--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  font-weight: var(--n-weight-semibold);
}

/* ==================== 弹窗表单 ==================== */
.grid2 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 0 var(--n-space-6);
}

.col {
  min-width: 0;
}

.field {
  margin-bottom: var(--n-space-5);
}

.field--last {
  margin-bottom: 0;
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

.field__hint {
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
}

.field__file {
  margin: var(--n-space-2) 0 0;
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  overflow-wrap: anywhere;
}

/* 原生文件选择：统一为圆角一致的容器 */
.file {
  width: 100%;
  padding: var(--n-space-2) var(--n-space-3);
  border: 1px dashed var(--n-line-strong);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  cursor: pointer;
  transition: border-color var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .file:hover {
    border-color: var(--n-accent-line);
    background: var(--n-surface-hover);
  }
}

.file::file-selector-button {
  margin-right: var(--n-space-3);
  padding: 5px 12px;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xs);
  background: var(--n-surface-hover);
  color: var(--n-text);
  font: inherit;
  font-size: var(--n-text-sm);
  cursor: pointer;
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
</style>

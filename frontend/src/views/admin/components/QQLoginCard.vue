<script setup>
/**
 * QQ 音乐扫码登录卡片（后台「外站补全」系统设置用）。
 * ------------------------------------------------------------
 * 展示 QQ 音乐登录状态，点按生成二维码（后端直出 QQ 下发的 PNG），
 * 轮询扫码结果；确认后由后端保存登录 Cookie。组件自管状态，无需父级传参。
 *
 * 注意：这是 QQ 互联授权码，必须用「手机 QQ」扫一扫；用相机 / 微信 / QQ 音乐 App
 * 扫描会把它当普通链接打开并跳到 im.qq.com，无法完成登录。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NModal, NSpinner } from '@/ui'
import { useToast } from '@/composables/useToast'
import {
  checkQqQr,
  createQqQrKey,
  fetchQqLoginStatus,
  logoutQq
} from '@/api/adminQq.js'

const toast = useToast()

const statusLoading = ref(false)
const loggedIn = ref(false)
const nickname = ref('')
const avatarUrl = ref('')

const modalOpen = ref(false)
const qrDataUrl = ref('')
// idle | loading | pending | scanned | expired | failed
const qrState = ref('idle')
const qrKey = ref('')
const qrError = ref('')

let pollTimer = null
let generation = 0

const qrTip = computed(() => {
  switch (qrState.value) {
    case 'loading':
      return '正在生成二维码…'
    case 'pending':
      return '请打开手机 QQ「扫一扫」扫描并确认'
    case 'scanned':
      return '已扫码，请在手机上确认登录'
    case 'expired':
      return '二维码已过期，请刷新'
    case 'failed':
      return qrError.value || '扫码登录失败，请重试'
    default:
      return ''
  }
})

async function loadStatus() {
  statusLoading.value = true
  try {
    const data = await fetchQqLoginStatus()
    loggedIn.value = !!data?.loggedIn
    nickname.value = data?.nickname || ''
    avatarUrl.value = data?.avatarUrl || ''
  } catch {
    // 状态查询失败不弹错，保持「未登录」展示，避免打扰
    loggedIn.value = false
    nickname.value = ''
    avatarUrl.value = ''
  } finally {
    statusLoading.value = false
  }
}

function stopPolling() {
  generation += 1
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function resetQr() {
  stopPolling()
  qrState.value = 'idle'
  qrKey.value = ''
  qrDataUrl.value = ''
  qrError.value = ''
}

async function startQr() {
  stopPolling()
  const gen = generation
  qrDataUrl.value = ''
  qrError.value = ''
  qrState.value = 'loading'
  try {
    const data = await createQqQrKey()
    if (gen !== generation) return
    const key = data?.key
    if (!key) {
      throw new Error('未返回二维码 key')
    }
    qrKey.value = key
    if (!data?.qrImage) {
      throw new Error('未返回二维码图像')
    }
    qrDataUrl.value = data.qrImage
    if (gen !== generation) return
    qrState.value = 'pending'
    pollTimer = setInterval(() => pollOnce(gen), 2000)
  } catch (e) {
    if (gen !== generation) return
    qrState.value = 'failed'
    qrError.value = e?.message || '二维码加载失败，请重试'
  }
}

async function pollOnce(gen) {
  if (gen !== generation || !qrKey.value) return
  try {
    const data = await checkQqQr(qrKey.value)
    if (gen !== generation) return
    const status = data?.status
    if (status === 'scanned') {
      qrState.value = 'scanned'
      return
    }
    if (status === 'confirmed') {
      stopPolling()
      if (!data?.loggedIn) {
        qrState.value = 'failed'
        qrError.value = '已扫码，但获取 QQ 音乐账号资料失败，请重试'
        return
      }
      loggedIn.value = true
      nickname.value = data?.nickname || ''
      avatarUrl.value = data?.avatarUrl || ''
      modalOpen.value = false
      toast.success(data?.nickname ? `已登录 QQ 音乐：${data.nickname}` : 'QQ 音乐扫码登录成功')
      return
    }
    if (status === 'expired') {
      stopPolling()
      qrState.value = 'expired'
      return
    }
    qrState.value = 'pending'
  } catch (e) {
    if (gen !== generation) return
    // 409 = 当前扫码状态冲突，视为瞬时，继续轮询；
    // 其余明确的业务/服务端错误直接展示并停止，避免像过期那样无声失败。
    if (e?.status && e.status !== 409) {
      stopPolling()
      qrState.value = 'failed'
      qrError.value = e.message || '扫码登录失败，请重试'
    }
  }
}

async function openLogin() {
  modalOpen.value = true
  await startQr()
}

async function doLogout() {
  if (!window.confirm('确定退出 QQ 音乐登录吗？退出后补全将按游客态请求。')) return
  statusLoading.value = true
  try {
    await logoutQq()
    loggedIn.value = false
    nickname.value = ''
    avatarUrl.value = ''
    toast.success('已退出 QQ 音乐登录')
  } catch (e) {
    toast.error(e?.message || '退出登录失败')
  } finally {
    statusLoading.value = false
  }
}

watch(modalOpen, (open) => {
  if (!open) resetQr()
})

onBeforeUnmount(stopPolling)

loadStatus()
</script>

<template>
  <div class="qq-login">
    <div class="qq-login__status">
      <img
        v-if="loggedIn && avatarUrl"
        :src="avatarUrl"
        alt=""
        class="qq-login__avatar"
        referrerpolicy="no-referrer"
      />
      <span v-else class="qq-login__avatar qq-login__avatar--empty">
        <NIcon name="user" :size="16" />
      </span>
      <div class="qq-login__meta">
        <span class="qq-login__name">
          <template v-if="statusLoading">查询中…</template>
          <template v-else-if="loggedIn">{{ nickname || '已登录' }}</template>
          <template v-else>未登录 QQ 音乐</template>
        </span>
        <span class="qq-login__hint">
          {{ loggedIn ? '补全使用该账号的登录态' : '扫码登录以获取高音质' }}
        </span>
      </div>
    </div>

    <div class="qq-login__actions">
      <NButton
        v-if="loggedIn"
        size="sm"
        variant="ghost"
        icon="logout"
        :disabled="statusLoading"
        @click="doLogout"
      >
        退出登录
      </NButton>
      <NButton
        size="sm"
        variant="primary"
        icon="qrcode"
        :disabled="statusLoading"
        @click="openLogin"
      >
        {{ loggedIn ? '重新登录' : '扫码登录' }}
      </NButton>
    </div>

    <NModal v-model="modalOpen" title="扫码登录 QQ 音乐" size="sm">
      <div class="qr">
        <div class="qr__box">
          <img v-if="qrDataUrl" :src="qrDataUrl" alt="QQ 音乐登录二维码" class="qr__img" />
          <div v-else class="qr__placeholder">
            <NSpinner v-if="qrState === 'loading'" />
            <span v-else>二维码加载失败</span>
          </div>
        </div>
        <p class="qr__tip">{{ qrTip }}</p>
        <p class="qr__note">
          这是 QQ 互联授权码，请用<strong>手机 QQ</strong>的「扫一扫」扫描；
          用相机、微信或 QQ 音乐 App 扫描会把它当成普通链接打开（跳到 im.qq.com），无法登录。
        </p>
        <NButton
          v-if="qrState === 'expired' || qrState === 'failed'"
          size="sm"
          variant="secondary"
          icon="refresh"
          @click="startQr"
        >
          刷新二维码
        </NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.qq-login {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-3);
}

.qq-login__status {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  min-width: 0;
}

.qq-login__avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: var(--n-radius-circle);
  object-fit: cover;
  border: 1px solid var(--n-line);
}

.qq-login__avatar--empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--n-surface-soft);
  color: var(--n-text-faint);
}

.qq-login__meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.qq-login__name {
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qq-login__hint {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.qq-login__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-2);
}

.qr {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-4);
  text-align: center;
}

.qr__box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 240px;
  height: 240px;
  padding: var(--n-space-2);
  border-radius: var(--n-radius-lg);
  background: #ffffff;
}

.qr__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.qr__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-2);
  color: #6b7280;
  font-size: var(--n-text-sm);
}

.qr__tip {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.qr__note {
  margin: 0;
  max-width: 320px;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: 1.6;
}

.qr__note strong {
  color: var(--n-text-muted);
  font-weight: var(--n-weight-medium);
}
</style>

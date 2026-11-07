<script setup>
/**
 * 网易云扫码登录卡片（后台系统设置用）。
 * ------------------------------------------------------------
 * 替换原来的「粘贴 Cookie」输入：展示登录状态，点按生成二维码，
 * 轮询扫码结果；确认后由后端保存登录 Cookie。组件自管状态，无需父级传参。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NModal, NSpinner } from '@/ui'
import { useToast } from '@/composables/useToast'
import {
  checkNeteaseQr,
  createNeteaseQrKey,
  fetchNeteaseLoginStatus,
  logoutNetease
} from '@/api/adminNetease.js'

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
      return '请使用网易云音乐 App 扫码并确认'
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
    const data = await fetchNeteaseLoginStatus()
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
    const data = await createNeteaseQrKey()
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
    const data = await checkNeteaseQr(qrKey.value)
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
        qrError.value = '已扫码，但获取网易云账号资料失败，请重试'
        return
      }
      loggedIn.value = true
      nickname.value = data?.nickname || ''
      avatarUrl.value = data?.avatarUrl || ''
      modalOpen.value = false
      toast.success(data?.nickname ? `已登录网易云：${data.nickname}` : '网易云扫码登录成功')
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
  if (!window.confirm('确定退出网易云登录吗？退出后补全将按游客态请求。')) return
  statusLoading.value = true
  try {
    await logoutNetease()
    loggedIn.value = false
    nickname.value = ''
    avatarUrl.value = ''
    toast.success('已退出网易云登录')
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
  <div class="netease-login">
    <div class="netease-login__status">
      <img
        v-if="loggedIn && avatarUrl"
        :src="avatarUrl"
        alt=""
        class="netease-login__avatar"
        referrerpolicy="no-referrer"
      />
      <span v-else class="netease-login__avatar netease-login__avatar--empty">
        <NIcon name="user" :size="16" />
      </span>
      <div class="netease-login__meta">
        <span class="netease-login__name">
          <template v-if="statusLoading">查询中…</template>
          <template v-else-if="loggedIn">{{ nickname || '已登录' }}</template>
          <template v-else>未登录网易云</template>
        </span>
        <span class="netease-login__hint">
          {{ loggedIn ? '补全使用该账号的登录态' : '扫码登录以获取高音质' }}
        </span>
      </div>
    </div>

    <div class="netease-login__actions">
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

    <NModal v-model="modalOpen" title="扫码登录网易云" size="sm">
      <div class="qr">
        <div class="qr__box">
          <img v-if="qrDataUrl" :src="qrDataUrl" alt="网易云登录二维码" class="qr__img" />
          <div v-else class="qr__placeholder">
            <NSpinner v-if="qrState === 'loading'" />
            <span v-else>二维码加载失败</span>
          </div>
        </div>
        <p class="qr__tip">{{ qrTip }}</p>
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
.netease-login {
  display: flex;
  flex-direction: column;
  gap: var(--n-space-3);
}

.netease-login__status {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  min-width: 0;
}

.netease-login__avatar {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: var(--n-radius-circle);
  object-fit: cover;
  border: 1px solid var(--n-line);
}

.netease-login__avatar--empty {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--n-surface-soft);
  color: var(--n-text-faint);
}

.netease-login__meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.netease-login__name {
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.netease-login__hint {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.netease-login__actions {
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
</style>

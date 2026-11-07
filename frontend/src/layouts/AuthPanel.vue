<template>
  <div class="auth">
    <span class="auth__glow" aria-hidden="true" />

    <div class="auth__card">
      <div class="auth__grid">
        <aside class="auth__qr-col">
          <h2 class="auth__qr-title">{{ $t('auth.qrTitle') }}</h2>

          <div class="auth__qr-box">
            <img v-if="qrImageUrl" class="auth__qr-img" :src="qrImageUrl" :alt="$t('auth.qrAlt')" />
            <div v-else class="auth__qr-skeleton" aria-hidden="true">
              <NIcon name="qrcode" :size="44" />
            </div>
            <div v-if="qrRefreshable" class="auth__qr-mask">
              <NButton variant="secondary" size="sm" @click="startQrLogin">
                <NIcon name="refresh" :size="14" />
                {{ $t('auth.qrRefresh') }}
              </NButton>
            </div>
          </div>

          <p class="auth__qr-status" :class="`auth__qr-status--${qrStatus}`" role="status">
            {{ qrStatusText || '\u00A0' }}
          </p>
          <p class="auth__qr-hint">
            {{ $t('auth.qrHint1') }}<br />
            {{ $t('auth.qrHint2') }}
          </p>
        </aside>

        <section class="auth__form-col">
          <div class="auth__tabs" role="tablist" :aria-label="$t('auth.tabsAria')">
            <button
              type="button"
              role="tab"
              class="auth__tab"
              :class="{ 'auth__tab--active': activeTab === 'login' }"
              :aria-selected="activeTab === 'login'"
              @click="switchTab('login')"
            >
              {{ $t('auth.tabLogin') }}
            </button>
            <button
              type="button"
              role="tab"
              class="auth__tab"
              :class="{ 'auth__tab--active': activeTab === 'register' }"
              :aria-selected="activeTab === 'register'"
              @click="switchTab('register')"
            >
              {{ $t('auth.tabRegister') }}
            </button>
          </div>

          <div class="auth__form">
            <p class="auth__eyebrow">NEKO ID · SINGLE SIGN-ON</p>
            <p class="auth__sso-desc">
              NekoMusic 不再单独保存邮箱、密码或用户 Token。账号注册、邮箱验证、密码管理与登录会话均由 NekoAuth 统一处理。
            </p>
            <p class="auth__error" role="alert">{{ loginError || '\u00A0' }}</p>
            <NButton variant="primary" size="lg" block :loading="loginLoading" @click="startNekoAuthLogin">
              {{ activeTab === 'login' ? '前往 NekoAuth 登录' : '前往 NekoAuth 注册' }}
            </NButton>
            <p class="auth__sso-hint">
              没有账号时，可在 NekoAuth 登录页直接注册；完成授权后会自动回到当前页面。
            </p>
          </div>

          <div class="auth__aux">
            <a href="/forgot-password" class="auth__link" @click="emit('close')">
              {{ $t('auth.forgotPassword') }}
            </a>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AuthPanel —— NekoAuth 统一账号入口 + 手机 App 扫码登录。
 *
 * <p>浏览器端不再收集邮箱、密码或验证码，也不再调用 NekoMusic 的本地登录 / 注册接口。
 * 用户 token 只由 NekoAuth 签发，本组件仅保存服务端返回的 token 与本地业务资料。</p>
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import axios from 'axios'
import API_CONFIG from '@/config/apiConfig.js'
import NIcon from '@/icons/NIcon.vue'
import { NButton } from '@/ui'
import { useToast } from '@/composables/useToast'
import { setUser } from '@/utils/userStore.js'
import { t } from '@/i18n'

const emit = defineEmits(['authenticated', 'close'])

const props = defineProps({
  initialTab: {
    type: String,
    default: 'login',
    validator: (value) => ['login', 'register'].includes(value),
  },
})

const toast = useToast()
const activeTab = ref(props.initialTab)
const loginLoading = ref(false)
const loginError = ref('')

watch(
  () => props.initialTab,
  (tab) => {
    if (tab !== activeTab.value) activeTab.value = tab
  }
)

function switchTab(tab) {
  if (tab === activeTab.value) return
  activeTab.value = tab
  loginError.value = ''
}

async function startNekoAuthLogin() {
  if (loginLoading.value) return
  loginLoading.value = true
  loginError.value = ''
  const current = window.location.pathname + window.location.search + window.location.hash
  const safeReturnTo = current.startsWith('/oauth/nekocore/') || current.startsWith('/sso/') ? '/' : current
  try {
    const response = activeTab.value === 'register'
      ? await axios.get(`${API_CONFIG.BASE_URL}/api/user/sso/portal-url`, {
          params: { target: 'register' },
        })
      : await axios.post(`${API_CONFIG.BASE_URL}/api/user/sso/login-entry`, {
          client: 'web',
          returnTo: safeReturnTo || '/',
        })
    const url = response.data?.data?.url
    if (!response.data?.success || typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
      throw new Error(response.data?.message || t('auth.loginFailed'))
    }
    window.location.assign(url)
  } catch (error) {
    loginLoading.value = false
    loginError.value = error.response?.data?.message || error.message || t('auth.loginFailed')
  }
}

function applyLogin(token, user) {
  const previousToken = localStorage.getItem('userToken')
  localStorage.setItem('userToken', token)
  setUser(user)
  if (previousToken !== token) {
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'userToken',
        oldValue: previousToken,
        newValue: token,
      })
    )
  }
  toast.success(t('auth.loginSuccess'))
  emit('authenticated')
}

const qrImageUrl = ref('')
const qrStatus = ref('idle')
const qrRefreshable = ref(false)

let qrEventSource = null
let qrExpireTimer = null
let qrGeneration = 0
let qrDeliveryKey = ''

const qrStatusText = computed(() => {
  switch (qrStatus.value) {
    case 'loading':
      return t('auth.qrGenerating')
    case 'pending':
      return t('auth.qrWaiting')
    case 'scanned':
      return t('auth.qrScanned')
    case 'canceled':
      return t('auth.qrCanceled')
    case 'expired':
      return t('auth.qrExpired')
    case 'failed':
      return t('auth.qrLoadFailed')
    default:
      return ''
  }
})

function closeQrStream() {
  if (qrEventSource) {
    qrEventSource.close()
    qrEventSource = null
  }
  if (qrExpireTimer) {
    window.clearTimeout(qrExpireTimer)
    qrExpireTimer = null
  }
}

function stopQrSession() {
  qrGeneration += 1
  closeQrStream()
  qrDeliveryKey = ''
}

function markQrNeedsRefresh() {
  qrRefreshable.value = true
}

async function startQrLogin() {
  stopQrSession()
  const generation = qrGeneration
  qrImageUrl.value = ''
  qrStatus.value = 'loading'
  qrRefreshable.value = false

  try {
    const response = await axios.post(`${API_CONFIG.BASE_URL}/api/user/qrlogin/create`)
    if (generation !== qrGeneration) return
    const payload = response.data?.data
    if (!response.data?.success || !payload?.sessionId || !payload?.qrImage || !payload?.deliveryKey) {
      throw new Error(response.data?.message || t('auth.qrCreateFailed'))
    }

    qrDeliveryKey = payload.deliveryKey
    qrImageUrl.value = payload.qrImage
    qrStatus.value = 'pending'

    const ttlSeconds = Number(payload.expiresIn) > 0 ? Number(payload.expiresIn) : 180
    qrExpireTimer = window.setTimeout(() => {
      if (generation !== qrGeneration) return
      stopQrSession()
      qrStatus.value = 'expired'
      markQrNeedsRefresh()
    }, ttlSeconds * 1000)

    watchQrSession(payload.sessionId, generation)
  } catch (error) {
    if (generation !== qrGeneration) return
    console.error('扫码登录初始化失败:', error)
    qrStatus.value = 'failed'
    markQrNeedsRefresh()
  }
}

function watchQrSession(sessionId, generation) {
  const source = new EventSource(
    `${API_CONFIG.BASE_URL}/api/user/qrlogin/status?sessionId=${encodeURIComponent(sessionId)}`
  )
  qrEventSource = source

  source.addEventListener('status', async (event) => {
    if (generation !== qrGeneration) {
      source.close()
      return
    }

    let data
    try {
      data = JSON.parse(event.data)
    } catch {
      return
    }

    switch (data?.status) {
      case 'pending':
        qrStatus.value = 'pending'
        break
      case 'scanned':
        qrStatus.value = 'scanned'
        break
      case 'confirmed':
        await claimQrLoginToken(sessionId, generation)
        break
      case 'canceled':
      case 'expired':
        stopQrSession()
        qrStatus.value = data.status
        markQrNeedsRefresh()
        break
      default:
        break
    }
  })

  source.onerror = () => {
    if (generation !== qrGeneration) return
    if (source.readyState === EventSource.CLOSED) {
      qrEventSource = null
      qrStatus.value = 'failed'
      markQrNeedsRefresh()
    }
  }
}

async function claimQrLoginToken(sessionId, generation) {
  const deliveryKey = qrDeliveryKey
  closeQrStream()
  qrDeliveryKey = ''
  if (generation !== qrGeneration) return
  if (!deliveryKey) {
    qrStatus.value = 'expired'
    markQrNeedsRefresh()
    return
  }
  try {
    const response = await axios.post(`${API_CONFIG.BASE_URL}/api/user/qrlogin/claim`, {
      sessionId,
      deliveryKey,
    })
    if (generation !== qrGeneration) return
    const payload = response.data?.data
    if (response.data?.success && payload?.status === 'confirmed' && payload?.token && payload?.user) {
      applyLogin(payload.token, payload.user)
      return
    }
    qrStatus.value = 'expired'
    markQrNeedsRefresh()
  } catch (error) {
    if (generation !== qrGeneration) return
    console.error('扫码登录令牌领取失败:', error)
    qrStatus.value = 'expired'
    markQrNeedsRefresh()
  }
}

onMounted(startQrLogin)
onUnmounted(stopQrSession)
</script>

<style scoped>
.auth {
  position: relative;
  width: 100%;
}

.auth__glow {
  position: absolute;
  inset: -14% -10%;
  z-index: -1;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 40%, rgba(95, 208, 224, 0.22), transparent 62%);
  filter: blur(48px);
  pointer-events: none;
  animation: authGlow 8s var(--n-ease-in-out) infinite;
}

@keyframes authGlow {
  0%,
  100% { opacity: 0.75; }
  50% { opacity: 1; }
}

.auth__card {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  background: var(--n-surface-strong);
  box-shadow: var(--n-shadow-lg);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
  animation: authCardIn 0.5s var(--n-ease) both;
}

.auth__card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--n-accent-line), transparent);
}

@keyframes authCardIn {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.auth__grid {
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr);
  min-width: 0;
}

.auth__qr-col,
.auth__form-col {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: var(--n-space-6) var(--n-space-5);
}

.auth__qr-col {
  align-items: center;
  border-right: 1px solid var(--n-line);
  background: linear-gradient(180deg, rgba(95, 208, 224, 0.07), transparent 68%);
}

.auth__qr-title {
  margin: 0 0 var(--n-space-3);
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-bold);
}

.auth__qr-box {
  position: relative;
  display: grid;
  place-items: center;
  width: 168px;
  height: 168px;
  padding: 8px;
  overflow: hidden;
  box-sizing: border-box;
  border-radius: var(--n-radius);
  background: #fff;
}

.auth__qr-img {
  display: block;
  width: 100%;
  height: 100%;
}

.auth__qr-skeleton {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--n-text-faint);
  animation: authQrPulse 1.2s var(--n-ease-in-out) infinite;
}

@keyframes authQrPulse {
  0%,
  100% { opacity: 0.45; }
  50% { opacity: 0.9; }
}

.auth__qr-mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: var(--n-space-4);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
}

.auth__qr-status {
  min-height: 18px;
  margin: var(--n-space-3) 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-medium);
  text-align: center;
}

.auth__qr-status--scanned {
  color: var(--n-accent-strong);
}

.auth__qr-hint {
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: 1.7;
  text-align: center;
}

.auth__tabs {
  display: flex;
  align-items: center;
  gap: var(--n-space-5);
  margin-bottom: var(--n-space-5);
}

.auth__tab {
  position: relative;
  padding: 0 0 6px;
  border: 0;
  background: none;
  color: var(--n-text-muted);
  font-family: inherit;
  font-size: var(--n-text-md);
  font-weight: var(--n-weight-medium);
  cursor: pointer;
  transition: color var(--n-duration-fast) var(--n-ease);
}

.auth__tab::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  border-radius: 2px;
  background: transparent;
  transition: background var(--n-duration-fast) var(--n-ease);
}

.auth__tab--active {
  color: var(--n-text);
  font-weight: var(--n-weight-bold);
}

.auth__tab--active::after {
  background: var(--n-accent-strong);
}

.auth__form {
  display: flex;
  flex-direction: column;
}

.auth__eyebrow {
  margin: 0 0 var(--n-space-2);
  color: var(--n-accent-strong);
  font-size: 11px;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.12em;
}

.auth__sso-desc {
  margin: 0 0 var(--n-space-4);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: 1.75;
}

.auth__error {
  min-height: 18px;
  margin: 0 0 var(--n-space-3);
  color: var(--n-danger);
  font-size: var(--n-text-xs);
  text-align: center;
}

.auth__sso-hint {
  margin: var(--n-space-3) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: 1.7;
  text-align: center;
}

.auth__aux {
  margin-top: var(--n-space-4);
  text-align: right;
}

.auth__link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

@media (hover: hover) {
  .auth__tab:hover,
  .auth__link:hover {
    color: var(--n-accent-strong);
  }
}

@media (max-width: 760px) {
  .auth__grid {
    grid-template-columns: 1fr;
  }

  .auth__qr-col {
    display: none;
  }

  .auth__form-col {
    padding: var(--n-space-6) var(--n-space-5);
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth__card,
  .auth__glow {
    animation: none;
  }

  .auth__tab,
  .auth__tab::after {
    transition: none;
  }
}
</style>

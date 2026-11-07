<script setup>
/**
 * NekoAuth 浏览器 SSO 完成页。
 *
 * <p>URL 里只有一次性 handoff，不包含用户 token。页面先读取并立即从地址栏清除 handoff，
 * 再向同源后端领取 NekoAuth token。token 只写入 localStorage，不写回 URL。</p>
 */
import { onMounted, ref } from 'vue'
import axios from 'axios'
import API_CONFIG from '@/config/apiConfig.js'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { AmbientBackdrop, PageShell } from '@/layouts'
import { setUser } from '@/utils/userStore.js'

const status = ref('loading')
const errorMessage = ref('')

function readHandoff() {
  const params = new URLSearchParams(window.location.search)
  const value = params.get('handoff') || ''
  window.history.replaceState(null, '', '/sso/complete')
  return value
}

function safeReturnTo(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/'
  if (value.includes('\\') || [...value].some((char) => char.charCodeAt(0) < 0x20)) return '/'
  if (value.startsWith('/oauth/nekocore/') || value.startsWith('/sso/')) return '/'
  return value
}

function persistLogin(token, user) {
  const previousToken = localStorage.getItem('userToken')
  localStorage.setItem('userToken', token)
  setUser(user)
  window.dispatchEvent(
    new StorageEvent('storage', {
      key: 'userToken',
      oldValue: previousToken,
      newValue: token,
    })
  )
}

async function claimLogin(handoff) {
  if (!handoff) {
    status.value = 'error'
    errorMessage.value = '登录交接参数缺失，请重新发起登录。'
    return
  }

  try {
    const response = await axios.post(`${API_CONFIG.BASE_URL}/api/user/sso/claim`, { handoff })
    const payload = response.data?.data
    if (!response.data?.success || !payload?.token || !payload?.user) {
      throw new Error(response.data?.message || '登录交接失败')
    }
    persistLogin(payload.token, payload.user)
    window.location.replace(safeReturnTo(payload.returnTo))
  } catch (error) {
    status.value = 'error'
    errorMessage.value = error.response?.data?.message || error.message || '登录交接已过期，请重新登录。'
  }
}

function retryLogin() {
  window.location.assign('/login')
}

onMounted(() => claimLogin(readHandoff()))
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="narrow" centered>
    <NCard pad="lg" class="sso-complete">
      <template v-if="status === 'loading'">
        <NSpinner size="lg" />
        <h1 class="sso-complete__title">正在完成 NekoAuth 登录</h1>
        <p class="sso-complete__desc">正在领取一次性登录交接，请稍候喵。</p>
      </template>

      <template v-else>
        <span class="sso-complete__icon sso-complete__icon--error">
          <NIcon name="alert" :size="30" />
        </span>
        <h1 class="sso-complete__title">登录未能完成</h1>
        <p class="sso-complete__desc" role="alert">{{ errorMessage }}</p>
        <div class="sso-complete__actions">
          <NButton variant="primary" icon="refresh" @click="retryLogin">
            重新登录
          </NButton>
          <NButton variant="secondary" to="/">返回首页</NButton>
        </div>
      </template>
    </NCard>
  </PageShell>
</template>

<style scoped>
.sso-complete {
  width: min(520px, 100%);
  padding: clamp(36px, 6vw, 56px) clamp(24px, 5vw, 42px);
  text-align: center;
}

.sso-complete :deep(.n-spinner) {
  margin: 0 auto var(--n-space-5);
}

.sso-complete__icon {
  display: inline-grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin-bottom: var(--n-space-4);
  border-radius: 50%;
  color: var(--n-danger);
  background: color-mix(in srgb, var(--n-danger) 12%, transparent);
}

.sso-complete__title {
  margin: 0 0 var(--n-space-2);
  font-size: clamp(1.3rem, 3vw, 1.7rem);
  font-weight: var(--n-weight-bold);
}

.sso-complete__desc {
  margin: 0;
  color: var(--n-text-muted);
  line-height: var(--n-leading-normal);
}

.sso-complete__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3);
  justify-content: center;
  margin-top: var(--n-space-7);
}

@media (max-width: 560px) {
  .sso-complete__actions :deep(.n-btn) {
    width: 100%;
    justify-content: center;
  }
}
</style>

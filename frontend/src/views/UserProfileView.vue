<template>
  <AmbientBackdrop />

  <PageShell width="narrow">
    <div v-if="user" class="profile">
      <!-- 头部 -->
      <header class="profile__head">
        <div class="profile__avatar-wrap">
          <img
            :src="userAvatar"
            :alt="$t('nav.avatarAlt')"
            class="profile__avatar"
            @error="handleAvatarError"
          />
          <button
            type="button"
            class="profile__avatar-btn"
            :title="$t('profile.changeAvatar')"
            :disabled="accountCenterLoading"
            @click="openNekoAuthAccountCenter"
          >
            <NIcon name="user" :size="16" />
          </button>
        </div>

        <div class="profile__ident">
          <h1 class="profile__name">
            {{ user.nickname }}
            <RouterLink v-if="user.isVip" to="/vip" class="profile__vip">VIP</RouterLink>
          </h1>
          <p class="profile__email">{{ user.email }}</p>
          <p class="profile__joined">
            <NIcon name="calendar" :size="14" />
            {{ $t('profile.joinedAt', { date: formatDate(user.createdAt) }) }}
          </p>
        </div>
      </header>

      <!-- 标签页 -->
      <div class="tabs" role="tablist" :aria-label="$t('nav.account')">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="tabs__btn"
          :class="{ 'tabs__btn--active': activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="changeTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>

      <!-- 个人信息 -->
      <div v-if="activeTab === 'profile'" class="panel">
        <h2 class="panel__title">{{ $t('profile.infoTitle') }}</h2>

        <div class="info">
          <span class="info__label">{{ $t('auth.nickname') }}</span>
          <span class="info__text">{{ user.nickname }}</span>
        </div>

        <div class="info">
          <span class="info__label">{{ $t('auth.email') }}</span>
          <span class="info__text">{{ user.email }}</span>
        </div>

        <div class="info">
          <span class="info__label">{{ $t('profile.vipStatus') }}</span>
          <span class="info__text">{{ user.isVip ? $t('profile.vipMember') : $t('profile.vipNonMember') }}</span>
        </div>

        <div class="info">
          <span class="info__label">{{ $t('profile.vipExpires') }}</span>
          <span class="info__text">{{ formatVipExpiresAt(user.vipExpiresAt) }}</span>
        </div>

        <div class="info">
          <span class="info__label">{{ $t('profile.registeredAt') }}</span>
          <span class="info__text">{{ formatDate(user.createdAt) }}</span>
        </div>

        <div class="info info--last">
          <span class="info__label">{{ $t('profile.accountManagement') }}</span>
          <div class="info__value">
            <span class="info__text">{{ $t('profile.accountManagedBy') }}</span>
            <NButton
              size="sm"
              variant="secondary"
              icon="user-cog"
              :loading="accountCenterLoading"
              @click="openNekoAuthAccountCenter"
            >
              {{ $t('profile.openAccountCenter') }}
            </NButton>
          </div>
        </div>
      </div>

      <!-- 安全设置 -->
      <div v-else class="panel">
        <h2 class="panel__title">{{ $t('profile.securityTitle') }}</h2>
        <p class="panel__desc">{{ $t('profile.securityManagedBy') }}</p>
        <NButton
          variant="primary"
          icon="shield-check"
          :loading="accountCenterLoading"
          @click="openNekoAuthAccountCenter"
        >
          {{ $t('profile.openAccountCenter') }}
        </NButton>
      </div>
    </div>

    <div v-else class="profile profile--loading">
      <p class="profile__hint">{{ $t('profile.loadFailedHint') }}</p>
      <NButton variant="primary" icon="refresh-cw" @click="reloadUserInfo">{{ $t('profile.reload') }}</NButton>
    </div>
  </PageShell>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from '@/composables/useToast'
import { t, locale } from '@/i18n'
import NIcon from '@/icons/NIcon.vue'
import { NButton } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { formatVipExpiresAt, syncUserVipFromPlaylistsApi, USER_VIP_SYNC_EVENT } from '@/utils/userVip.js'
import { avatarUrl, useAvatarVersion } from '@/utils/userAvatar.js'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { useAuth } from '@/composables/useAuth'
import { getUser, loadUserInfo } from '@/utils/userStore.js'

const toast = useToast()

const vipSyncTick = ref(0)

// 用户资料只放在内存里（不落盘），进入本页会重新拉一次
// vipSyncTick 用于在歌单接口合并 VIP / 资料刷新后触发重算
const user = computed(() => {
  vipSyncTick.value
  return getUser()
})

const { token: authToken } = useAuth()
const avatarVersion = useAvatarVersion()

// 检查用户是否登录（用响应式 token，弹窗里登录后能立刻更新）
const isLoggedIn = computed(() => !!authToken.value)

const bumpUserFromStorage = () => {
  vipSyncTick.value++
}

// 未登录：弹登录窗（登录成功后 watch 会重新读取用户信息）
if (!isLoggedIn.value || !user.value) {
  openAuthDialog('login');
}

watch(authToken, (token) => {
  if (token) {
    bumpUserFromStorage()
    syncUserVipFromPlaylistsApi().then(bumpUserFromStorage)
  }
})

onMounted(async () => {
  window.addEventListener(USER_VIP_SYNC_EVENT, bumpUserFromStorage)
  // 昵称等资料不落盘：进页面时用 Token 拉一次最新值
  await loadUserInfo({ force: true })
  bumpUserFromStorage()
  await syncUserVipFromPlaylistsApi()
  bumpUserFromStorage()
})

/** 资料没拉到时手动重试 */
async function reloadUserInfo() {
  await loadUserInfo({ force: true })
  bumpUserFromStorage()
}

onUnmounted(() => {
  window.removeEventListener(USER_VIP_SYNC_EVENT, bumpUserFromStorage)
})

const userAvatar = computed(() => {
  // 使用用户 ID 获取头像；版本号让换头像后 URL 变化，绕过浏览器缓存
  const userId = user.value ? user.value.id : 'default'
  return avatarUrl(userId, avatarVersion.value)
})

const activeTab = ref('profile')
const accountCenterLoading = ref(false)

const tabs = computed(() => [
  { key: 'profile', label: t('profile.infoTitle') },
  { key: 'security', label: t('profile.securityTitle') }
])

// 格式化日期
const formatDate = (dateString) => {
  if (!dateString) return t('profile.unknown');
  const date = new Date(dateString);
  return date.toLocaleDateString(locale.value, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  });
}

// 切换标签页
const changeTab = (tabKey) => {
  activeTab.value = tabKey
}

// 昵称、邮箱、密码与登录设备统一由 NekoAuth 账号中心维护。
const openNekoAuthAccountCenter = async () => {
  if (accountCenterLoading.value) return
  accountCenterLoading.value = true
  try {
    const response = await fetch(
      `${API_CONFIG.BASE_URL}/api/user/sso/portal-url?target=account`
    )
    const data = await response.json().catch(() => ({}))
    const url = data?.data?.url
    if (!response.ok || !data.success || typeof url !== 'string' || !/^https?:\/\//i.test(url)) {
      throw new Error(data.message || t('profile.accountCenterFailed'))
    }
    window.location.assign(url)
  } catch (error) {
    console.error('打开 NekoAuth 账号中心失败:', error)
    toast.error(error.message || t('profile.accountCenterFailed'))
  } finally {
    accountCenterLoading.value = false
  }
}

// 处理头像加载错误
const handleAvatarError = (event) => {
  event.target.src = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="150" height="150" viewBox="0 0 150 150"><rect width="150" height="150" fill="%236a5acd"/><text x="75" y="95" font-family="Arial" font-size="24" fill="white" text-anchor="middle">U</text></svg>';
}

</script>

<style scoped>
.profile {
  width: 100%;
}

/* 资料没拉到时（网络异常 / 登录失效）的兜底 */
.profile--loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-4);
  padding: clamp(32px, 6vw, 64px) clamp(20px, 3vw, 28px);
  text-align: center;
}

.profile__hint {
  margin: 0;
  color: var(--n-text-dim);
}

/* ==================== 头部 ==================== */
.profile__head {
  display: flex;
  align-items: center;
  gap: clamp(18px, 3vw, 28px);
  padding: clamp(20px, 3vw, 28px);
  margin-bottom: var(--n-space-5);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  background: var(--n-surface);
  box-shadow: var(--n-shadow);
}

.profile__avatar-wrap {
  position: relative;
  flex: none;
  width: clamp(88px, 16vw, 112px);
  aspect-ratio: 1;
}

.profile__avatar {
  width: 100%;
  height: 100%;
  border-radius: var(--n-radius-lg);
  object-fit: cover;
  border: 1px solid var(--n-line-strong);
  background: var(--n-surface-soft);
}

.profile__file {
  display: none;
}

.profile__avatar-btn {
  position: absolute;
  right: -6px;
  bottom: -6px;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--n-radius-control);
  background: var(--n-accent);
  color: var(--n-text-inverse);
  border: 2px solid var(--n-bg);
  cursor: pointer;
  transition: transform var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .profile__avatar-btn:hover {
    transform: translateY(-1px);
    background: var(--n-accent-strong);
  }
}

.profile__avatar--busy {
  opacity: 0.55;
}

.profile__avatar-btn--busy {
  cursor: progress;
}

.profile__avatar-btn--busy :deep(.n-icon) {
  animation: profile-avatar-spin 0.8s linear infinite;
}

@keyframes profile-avatar-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .profile__avatar-btn--busy :deep(.n-icon) {
    animation-duration: 1.6s;
  }
}

.profile__ident {
  min-width: 0;
}

.profile__name {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  margin: 0 0 var(--n-space-2);
  font-size: clamp(1.3rem, 3vw, 1.7rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
  overflow-wrap: anywhere;
}

.profile__vip {
  flex: none;
  padding: 4px 10px;
  border-radius: var(--n-radius-xs);
  background: linear-gradient(135deg, var(--n-accent-strong), var(--n-accent));
  color: var(--n-text-inverse);
  font-size: 0.66rem;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.1em;
}

.profile__email {
  margin: 0 0 var(--n-space-1);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  overflow-wrap: anywhere;
}

.profile__joined {
  display: inline-flex;
  align-items: center;
  gap: var(--n-space-2);
  margin: 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

/* ==================== 标签页 ==================== */
.tabs {
  display: flex;
  gap: var(--n-space-1);
  padding: 4px;
  margin-bottom: var(--n-space-5);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
}

.tabs__btn {
  flex: 1;
  padding: 9px var(--n-space-4);
  border-radius: var(--n-radius-xs);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  transition: background var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .tabs__btn:hover {
    color: var(--n-text);
  }
}

.tabs__btn--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

/* ==================== 面板 ==================== */
.panel {
  padding: clamp(20px, 3vw, 28px);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  background: var(--n-surface);
}

.panel__title {
  margin: 0 0 var(--n-space-5);
  font-size: var(--n-text-lg);
  font-weight: var(--n-weight-semibold);
  letter-spacing: -0.01em;
}

.panel__desc {
  max-width: 62ch;
  margin: 0 0 var(--n-space-5);
  color: var(--n-text-muted);
  line-height: 1.75;
}

/* ==================== 信息行 ==================== */
.info {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  padding: var(--n-space-3) 0;
  border-bottom: 1px solid var(--n-line-subtle);
}

.info--last {
  border-bottom: none;
  padding-bottom: 0;
}

.info__label {
  flex: none;
  width: 84px;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

.info__value {
  display: flex;
  align-items: center;
  gap: var(--n-space-2);
  flex: 1;
  min-width: 0;
}

.info__text {
  color: var(--n-text);
  font-size: var(--n-text-base);
  overflow-wrap: anywhere;
}

@media (max-width: 560px) {
  .profile__head {
    flex-direction: column;
    text-align: center;
  }

  .profile__name {
    justify-content: center;
  }

  .info {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--n-space-2);
  }

  .info__label {
    width: auto;
  }

  .info__value {
    width: 100%;
    flex-wrap: wrap;
  }
}
</style>

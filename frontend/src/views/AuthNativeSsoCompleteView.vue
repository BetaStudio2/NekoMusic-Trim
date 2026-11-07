<script setup>
/**
 * 原生客户端 SSO 回跳引导页。
 *
 * <p>后端先跳转到本页，再由浏览器打开客户端的自定义协议。这样 CDN 始终只处理
 * HTTPS Location，不依赖其是否允许直接重定向到 `nekomusic://`。</p>
 */
import { computed, onMounted, ref } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { AmbientBackdrop, PageShell } from '@/layouts'

const handoff = ref('')
const client = ref('')
const attempted = ref(false)

const clientName = computed(() => (client.value === 'android' ? 'Android' : 'PC'))
const deepLink = computed(() => {
  if (!handoff.value) return ''
  return `nekomusic://sso/complete?handoff=${encodeURIComponent(handoff.value)}`
})

function openClient() {
  if (!deepLink.value) return
  attempted.value = true
  window.location.assign(deepLink.value)
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  handoff.value = params.get('handoff') || ''
  client.value = params.get('client') || ''
  window.history.replaceState(null, '', '/sso/native-complete')
  if (handoff.value) {
    window.setTimeout(openClient, 80)
  }
})
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="narrow" centered>
    <NCard pad="lg" class="native-sso">
      <template v-if="handoff">
        <NSpinner size="lg" />
        <h1 class="native-sso__title">正在返回 {{ clientName }} 客户端</h1>
        <p class="native-sso__desc">
          如果没有自动打开 NekoMusic，请点击下方按钮继续完成登录。
        </p>
        <div class="native-sso__actions">
          <NButton variant="primary" icon="external-link" @click="openClient">
            {{ attempted ? '再次打开 NekoMusic' : '打开 NekoMusic' }}
          </NButton>
        </div>
      </template>

      <template v-else>
        <span class="native-sso__icon">
          <NIcon name="alert" :size="30" />
        </span>
        <h1 class="native-sso__title">登录交接已失效</h1>
        <p class="native-sso__desc" role="alert">请返回客户端重新发起登录。</p>
      </template>
    </NCard>
  </PageShell>
</template>

<style scoped>
.native-sso {
  width: min(520px, 100%);
  padding: clamp(36px, 6vw, 56px) clamp(24px, 5vw, 42px);
  text-align: center;
}

.native-sso :deep(.n-spinner) {
  margin: 0 auto var(--n-space-5);
}

.native-sso__icon {
  display: inline-grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin-bottom: var(--n-space-4);
  border-radius: 50%;
  color: var(--n-danger);
  background: color-mix(in srgb, var(--n-danger) 12%, transparent);
}

.native-sso__title {
  margin: 0 0 var(--n-space-2);
  font-size: clamp(1.3rem, 3vw, 1.7rem);
  font-weight: var(--n-weight-bold);
}

.native-sso__desc {
  margin: 0;
  color: var(--n-text-muted);
  line-height: var(--n-leading-normal);
}

.native-sso__actions {
  display: flex;
  justify-content: center;
  margin-top: var(--n-space-7);
}
</style>

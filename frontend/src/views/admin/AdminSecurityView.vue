<template>
  <div class="subpage">
    <header class="subpage__head">
      <div>
        <h1 class="subpage__title">安全</h1>
        <p class="subpage__desc">
          访问日志与安全分析：访问量、来源 IP 与网段、User-Agent、状态码、耗时与威胁判定。
          后端会自动标记正常 / 疑似攻击 / 攻击，覆盖高危载荷、失败请求簇与跨网段轮换攻击。
          数据落在独立日志库，不参与业务库备份；后台写操作的留痕见「操作日志」。
        </p>
      </div>
    </header>

    <AccessLogPanel v-if="canViewAccessLog" />
  </div>
</template>

<script setup>
/**
 * 安全：访问日志（访问量统计 + 安全分析）。
 *
 * 从首页「统计分析」拆出来单独成页：访问日志是排查异常流量的第一现场，
 * 和业务数据趋势不是一回事，混在同一页会让两边都要滚动。
 */
import { computed, onMounted, ref } from 'vue'
import AccessLogPanel from '@/views/admin/components/AccessLogPanel.vue'

const adminInfo = ref({})

/** 访问日志对「除普通用户外的角色」开放，与后端 ACCESS_LOG_VIEW 一致 */
const canViewAccessLog = computed(() =>
  ['super_admin', 'admin', 'auditor'].includes(adminInfo.value?.role || 'admin')
)

onMounted(() => {
  const stored = localStorage.getItem('adminInfo')
  if (!stored) return
  try {
    adminInfo.value = JSON.parse(stored)
  } catch {
    adminInfo.value = {}
  }
})
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
  margin-bottom: var(--n-space-5);
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
  max-width: 72ch;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}
</style>

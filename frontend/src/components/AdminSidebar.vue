<script setup>
/**
 * AdminSidebar —— 管理后台侧栏
 * ------------------------------------------------------------
 * 视觉参考 ArchoeraMusic 的侧栏（SMenu 观感）：
 *  - 圆角导航项 + 选中态 primary@10% 背景 + 内缩圆角指示条（非整行 border-left）
 *  - 悬浮态为中性 onSurface@5%
 *  - 分组标题（概览 / 内容 / 运营）
 *  - 顶部 Logo 字标
 * 权限：保留原有 hasPermission 矩阵（super_admin / admin / auditor）。
 */
import { useRoute } from 'vue-router'
import { computed, onMounted, ref } from 'vue'
import NIcon from '@/icons/NIcon.vue'

const route = useRoute()
const adminInfo = ref(null)
const isOpen = ref(false)

/** 移动端侧栏开关 */
const toggleSidebar = () => {
  isOpen.value = !isOpen.value
}
const closeSidebar = () => {
  isOpen.value = false
}
defineExpose({ toggleSidebar, closeSidebar })

/** 精确匹配 /admin，其余按前缀匹配 */
const isActiveRoute = (path) => {
  if (path === '/admin') return route.path === '/admin'
  return route.path === path || route.path.startsWith(path + '/')
}

onMounted(() => {
  const stored = localStorage.getItem('adminInfo')
  if (!stored) return
  try {
    adminInfo.value = JSON.parse(stored)
  } catch {
    adminInfo.value = null
  }
})

const hasPermission = (permission) => {
  if (!adminInfo.value) return false
  const role = adminInfo.value.role || 'admin'

  if (role === 'super_admin') return true

  if (role === 'admin') {
    return [
      'music_view',
      'music_add',
      'music_edit',
      'music_delete',
      'audit_view',
      'audit_approve',
      'audit_reject',
      'user_view',
      'user_edit',
      'user_delete',
      'stats_view',
      'release_manage',
      'settings_view',
      'access_log_view',
      'operation_log_view',
    ].includes(permission)
  }

  if (role === 'auditor') {
    return [
      'audit_view',
      'audit_approve',
      'audit_reject',
      'stats_view',
      'user_view',
      // 访问日志用于安全分析，审核员同样可以查看
      'access_log_view',
    ].includes(permission)
  }

  return false
}

/** 分组导航：组内无可见项时不渲染该组 */
const navGroups = computed(() => {
  // 首页 = 统计分析：只看业务数据趋势
  const overview = [{ to: '/admin', icon: 'chart', label: '统计分析' }]

  // 安全 = 访问日志（访问量与来源），单独成组放在「概览」下方
  const security = []
  if (hasPermission('access_log_view')) {
    security.push({ to: '/admin/security', icon: 'shield-check', label: '安全' })
  }

  const content = []
  if (hasPermission('music_view')) {
    content.push({ to: '/admin/music', icon: 'music', label: '音乐管理' })
    content.push({ to: '/admin/lyrics', icon: 'file-text', label: '歌词编辑' })
  }
  if (hasPermission('audit_view')) {
    content.push({ to: '/admin/audit', icon: 'clipboard-check', label: '审核管理' })
  }

  const ops = []
  if (hasPermission('user_view')) {
    ops.push({ to: '/admin/users', icon: 'users', label: '用户管理' })
  }
  if (hasPermission('user_edit')) {
    ops.push({ to: '/admin/vip-pricing', icon: 'gem', label: 'VIP 价目' })
  }
  if (hasPermission('release_manage')) {
    ops.push({ to: '/admin/releases', icon: 'package', label: '客户端更新' })
  }
  if (hasPermission('settings_view')) {
    ops.push({ to: '/admin/settings', icon: 'settings', label: '系统设置' })
  }
  if (hasPermission('operation_log_view')) {
    // 操作日志 = 后台写操作留痕（改了什么、谁改的）；访问日志在「安全」页里
    ops.push({ to: '/admin/logs', icon: 'clipboard-check', label: '操作日志' })
  }

  return [
    { title: '概览', items: overview },
    { title: '安全', items: security },
    { title: '内容', items: content },
    { title: '运营', items: ops },
  ].filter((g) => g.items.length > 0)
})
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--open': isOpen }">
    <!-- 品牌 -->
    <div class="sidebar__brand">
      <span class="sidebar__mark">
        <NIcon name="cat" :size="20" />
      </span>
      <span class="sidebar__wordmark">管理中心</span>
      <button type="button" class="sidebar__close" aria-label="关闭菜单" @click="toggleSidebar">
        <NIcon name="close" :size="18" />
      </button>
    </div>

    <nav class="sidebar__nav" aria-label="管理导航">
      <template v-for="group in navGroups" :key="group.title">
        <p class="sidebar__group">{{ group.title }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.to"
          :to="item.to"
          class="sidebar__item"
          :class="{ 'sidebar__item--active': isActiveRoute(item.to) }"
          @click="closeSidebar"
        >
          <NIcon :name="item.icon" :size="18" class="sidebar__icon" />
          <span class="sidebar__label">{{ item.label }}</span>
        </RouterLink>
      </template>
    </nav>
  </aside>
</template>

<style scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  z-index: var(--n-z-modal);
  display: flex;
  flex-direction: column;
  width: 250px;
  height: 100dvh;
  /* 抽屉贴物理屏边：刘海/状态栏/圆角不能压住品牌与关闭按钮 */
  padding-top: var(--n-safe-top);
  padding-left: var(--n-safe-left);
  padding-bottom: var(--n-safe-bottom);
  border-right: 1px solid var(--n-line);
  background: var(--n-bg-soft);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
}

/* ==================== 品牌 ==================== */
.sidebar__brand {
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  flex: none;
  min-height: 64px;
  padding: var(--n-space-2) var(--n-space-5);
  border-bottom: 1px solid var(--n-line-subtle);
}

.sidebar__mark {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: var(--n-radius-sm);
  background: linear-gradient(135deg, var(--n-accent-strong), var(--n-accent));
  color: var(--n-text-inverse);
  box-shadow: 0 6px 18px rgba(95, 208, 224, 0.24);
  transition: transform var(--n-duration-fast) var(--n-ease);
}

.sidebar__brand:hover .sidebar__mark {
  transform: scale(1.06);
}

.sidebar__wordmark {
  color: var(--n-accent-strong);
  font-size: var(--n-text-base);
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.02em;
}

.sidebar__close {
  display: none;
  margin-left: auto;
  place-items: center;
  width: var(--n-tap-min);
  height: var(--n-tap-min);
  border-radius: var(--n-radius-xs);
  color: var(--n-text-muted);
  transition: background var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease);
}

/* ==================== 导航 ==================== */
.sidebar__nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--n-space-2) 10px var(--n-space-5);
}

.sidebar__group {
  margin: 0;
  padding: var(--n-space-4) var(--n-space-3) var(--n-space-2);
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-medium);
  letter-spacing: 0.05em;
}

.sidebar__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--n-space-3);
  height: 40px;
  padding: 0 var(--n-space-3);
  border-radius: var(--n-radius-sm);
  color: var(--n-text-muted);
  font-size: var(--n-text-base);
  transition:
    background var(--n-duration-fast) var(--n-ease),
    color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .sidebar__item:not(.sidebar__item--active):hover {
    background: var(--n-surface-soft);
    color: var(--n-text);
  }
}

.sidebar__item--active {
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  font-weight: var(--n-weight-semibold);
}

/* 选中指示条：内缩 + 圆角（对齐参考实现，非整行 border-left） */
.sidebar__item--active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 2px;
  background: var(--n-accent);
}

.sidebar__icon {
  flex: none;
}

.sidebar__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ==================== 移动端：抽屉 ==================== */
@media (max-width: 900px) {
  .sidebar {
    transform: translateX(-100%);
    transition: transform var(--n-duration) var(--n-ease);
    box-shadow: var(--n-shadow-lg);
  }

  .sidebar--open {
    transform: translateX(0);
  }

  .sidebar__close {
    display: grid;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar {
    transition: none;
  }
}
</style>

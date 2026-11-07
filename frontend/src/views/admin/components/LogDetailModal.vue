<template>
  <NModal v-model="open" :title="title" size="lg">
    <div class="detail">
      <section v-if="changes.length" class="detail__changes">
        <h3 class="detail__subtitle">{{ changesTitle }}</h3>
        <table class="detail__table">
          <thead>
            <tr>
              <th>字段</th>
              <th>原值</th>
              <th>新值</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(change, index) in changes" :key="index">
              <td class="detail__key">{{ change.label }}</td>
              <td class="detail__value detail__value--before">{{ change.before }}</td>
              <td class="detail__value detail__value--after">{{ change.after }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section class="detail__fields">
        <h3 class="detail__subtitle">全部字段</h3>
        <dl class="detail__list">
          <div v-for="field in fields" :key="field.label" class="detail__row" :class="{ 'detail__row--block': field.block }">
            <dt class="detail__label">{{ field.label }}</dt>
            <dd class="detail__content" :class="{ 'detail__content--mono': field.mono }">{{ display(field.value) }}</dd>
          </div>
        </dl>
      </section>
    </div>

    <template #footer>
      <NButton variant="secondary" @click="open = false">关闭</NButton>
    </template>
  </NModal>
</template>

<script setup>
/**
 * 日志详情弹窗 —— 访问日志 / 操作日志共用。
 *
 * 列表只放得下几列，安全分析真正要看的字段（完整 UA、Referer、Origin、网段、线程…）
 * 必须在详情里一个不落地能查到。`changes` 有值时先渲染「字段 / 原值 / 新值」三列，
 * 让「改了什么」一眼可见（见 utils/operationDetail.js）。
 */
import { NButton, NModal } from '@/ui'

const open = defineModel({ type: Boolean, default: false })

defineProps({
  title: { type: String, default: '日志详情' },
  /** [{ label, value, mono?, block? }] */
  fields: { type: Array, default: () => [] },
  /** [{ label, before, after }]，来自 parseOperationDetail */
  changes: { type: Array, default: () => [] },
  changesTitle: { type: String, default: '变更内容' }
})

const display = (value) => {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
</script>

<style scoped>
.detail__subtitle {
  margin: 0 0 var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-medium);
  letter-spacing: 0.05em;
}

.detail__changes {
  margin-bottom: var(--n-space-5);
}

.detail__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--n-text-sm);
}

.detail__table th,
.detail__table td {
  padding: var(--n-space-2) var(--n-space-3);
  border-bottom: 1px solid var(--n-line-subtle);
  text-align: left;
  vertical-align: top;
}

.detail__table th {
  color: var(--n-text-faint);
  font-weight: var(--n-weight-medium);
  font-size: var(--n-text-xs);
  white-space: nowrap;
}

.detail__key {
  color: var(--n-text);
  font-weight: var(--n-weight-semibold);
}

.detail__value {
  color: var(--n-text-muted);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.detail__value--before {
  color: var(--n-text-faint);
  text-decoration: line-through;
  text-decoration-color: var(--n-line);
}

.detail__value--after {
  color: var(--n-accent-strong);
}

.detail__list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--n-space-2);
}

.detail__row {
  display: flex;
  gap: var(--n-space-3);
  align-items: baseline;
}

.detail__row--block {
  flex-direction: column;
  align-items: stretch;
  gap: var(--n-space-1);
}

.detail__label {
  flex: none;
  width: 9em;
  color: var(--n-text-faint);
  font-size: var(--n-text-sm);
}

.detail__content {
  margin: 0;
  min-width: 0;
  color: var(--n-text);
  font-size: var(--n-text-sm);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.detail__content--mono {
  font-family: var(--n-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: var(--n-text-xs);
}

@media (max-width: 560px) {
  .detail__row {
    flex-direction: column;
    align-items: stretch;
    gap: var(--n-space-1);
  }
}
</style>

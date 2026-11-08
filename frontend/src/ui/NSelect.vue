<script setup>
/**
 * NSelect —— 项目内自定义下拉选择器
 * ------------------------------------------------------------
 * 不使用浏览器原生 select 弹层，保持站点深色主题与触控尺寸。
 * 菜单通过 Teleport 挂到 body，避免被 Modal 的滚动容器裁切。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref, useId, watch } from 'vue'
import NIcon from '@/icons/NIcon.vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'change'])

const rootRef = ref(null)
const triggerRef = ref(null)
const menuRef = ref(null)
const open = ref(false)
const activeIndex = ref(-1)
const menuStyle = reactive({
  top: '0px',
  left: '0px',
  width: '0px',
  maxHeight: '320px',
})
const listboxId = useId()

const enabledOptions = computed(() => props.options.filter((option) => !option.disabled))

const selectedOption = computed(() =>
  props.options.find((option) => option.value === props.modelValue) || null,
)

function updatePosition() {
  const trigger = triggerRef.value
  if (!trigger || typeof window === 'undefined') return
  const rect = trigger.getBoundingClientRect()
  const desiredHeight = Math.min(360, Math.max(180, props.options.length * 54 + 12))
  const spaceBelow = window.innerHeight - rect.bottom - 12
  const spaceAbove = rect.top - 12
  const openAbove = spaceBelow < Math.min(desiredHeight, 260) && spaceAbove > spaceBelow
  const maxHeight = Math.max(150, Math.min(360, openAbove ? spaceAbove : spaceBelow))
  menuStyle.width = `${rect.width}px`
  menuStyle.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - rect.width - 8))}px`
  menuStyle.top = `${openAbove ? Math.max(8, rect.top - Math.min(desiredHeight, maxHeight) - 6) : rect.bottom + 6}px`
  menuStyle.maxHeight = `${maxHeight}px`
}

function openMenu(direction = 0) {
  if (props.disabled || !props.options.length) return
  open.value = true
  const selectedIndex = enabledOptions.value.findIndex((option) => option.value === props.modelValue)
  activeIndex.value = selectedIndex >= 0 ? selectedIndex : direction > 0 ? 0 : enabledOptions.value.length - 1
  nextTick(() => {
    updatePosition()
    scrollActiveIntoView()
  })
}

function closeMenu({ focus = false } = {}) {
  if (!open.value) return
  open.value = false
  if (focus) nextTick(() => triggerRef.value?.focus())
}

function toggleMenu() {
  if (open.value) closeMenu()
  else openMenu()
}

function selectOption(option) {
  if (!option || option.disabled) return
  emit('update:modelValue', option.value)
  emit('change', option.value, option)
  closeMenu({ focus: true })
}

function moveActive(delta) {
  if (!enabledOptions.value.length) return
  if (!open.value) {
    openMenu(delta)
    return
  }
  const count = enabledOptions.value.length
  activeIndex.value = (activeIndex.value + delta + count) % count
  nextTick(scrollActiveIntoView)
}

function scrollActiveIntoView() {
  const active = menuRef.value?.querySelector('[data-active="true"]')
  active?.scrollIntoView({ block: 'nearest' })
}

function onTriggerKeydown(event) {
  if (props.disabled) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActive(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActive(-1)
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (open.value) selectOption(enabledOptions.value[activeIndex.value])
    else openMenu(1)
  } else if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    closeMenu({ focus: true })
  } else if (event.key === 'Tab') {
    closeMenu()
  }
}

function onDocumentPointerDown(event) {
  if (!open.value) return
  if (rootRef.value?.contains(event.target) || menuRef.value?.contains(event.target)) return
  closeMenu()
}

function bindWindowListeners() {
  window.addEventListener('resize', updatePosition)
  window.addEventListener('scroll', updatePosition, true)
  document.addEventListener('pointerdown', onDocumentPointerDown, true)
}

function unbindWindowListeners() {
  window.removeEventListener('resize', updatePosition)
  window.removeEventListener('scroll', updatePosition, true)
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
}

watch(open, (isOpen) => {
  if (isOpen) bindWindowListeners()
  else unbindWindowListeners()
})

watch(
  () => props.options,
  () => {
    if (open.value) nextTick(updatePosition)
  },
)

onBeforeUnmount(unbindWindowListeners)
</script>

<template>
  <div ref="rootRef" class="n-select" :class="{ 'n-select--open': open, 'n-select--disabled': disabled }">
    <button
      ref="triggerRef"
      type="button"
      class="n-select__trigger"
      role="combobox"
      aria-haspopup="listbox"
      :aria-label="ariaLabel || undefined"
      :aria-expanded="open"
      :aria-controls="open ? listboxId : undefined"
      :aria-activedescendant="open ? `${listboxId}-option-${activeIndex}` : undefined"
      :disabled="disabled"
      @click="toggleMenu"
      @keydown="onTriggerKeydown"
    >
      <span class="n-select__value" :class="{ 'n-select__value--placeholder': !selectedOption }">
        <span class="n-select__label">{{ selectedOption?.label || placeholder }}</span>
        <span v-if="selectedOption?.description" class="n-select__description">
          {{ selectedOption.description }}
        </span>
      </span>
      <NIcon name="chevron-down" :size="16" class="n-select__chevron" />
    </button>

    <Teleport to="body">
      <Transition name="n-select-menu">
        <div
          v-if="open"
          :id="listboxId"
          ref="menuRef"
          class="n-select__menu"
          role="listbox"
          :style="menuStyle"
        >
          <button
            v-for="(option, index) in enabledOptions"
            :key="option.value"
            type="button"
            role="option"
            :id="`${listboxId}-option-${index}`"
            class="n-select__option"
            :class="{
              'n-select__option--selected': option.value === modelValue,
              'n-select__option--active': index === activeIndex,
            }"
            :data-active="index === activeIndex"
            :aria-selected="option.value === modelValue"
            @mouseenter="activeIndex = index"
            @click="selectOption(option)"
          >
            <span class="n-select__option-copy">
              <span class="n-select__option-label">{{ option.label }}</span>
              <span v-if="option.description" class="n-select__option-description">
                {{ option.description }}
              </span>
            </span>
            <NIcon v-if="option.value === modelValue" name="check" :size="16" class="n-select__check" />
          </button>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.n-select {
  position: relative;
  width: 100%;
}

.n-select__trigger {
  display: flex;
  width: 100%;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  padding: 8px var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text);
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease),
    box-shadow var(--n-duration-fast) var(--n-ease);
}

.n-select__trigger:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.n-select--open .n-select__trigger,
.n-select__trigger:focus-visible {
  border-color: var(--n-accent-line);
  background: var(--n-surface-active);
  box-shadow: 0 0 0 3px rgba(95, 208, 224, 0.12);
  outline: none;
}

.n-select__value {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.n-select__label {
  overflow: hidden;
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.n-select__value--placeholder .n-select__label {
  color: var(--n-text-faint);
  font-weight: var(--n-weight-regular);
}

.n-select__description {
  overflow: hidden;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.n-select__chevron {
  flex: none;
  color: var(--n-text-muted);
  transition: transform var(--n-duration-fast) var(--n-ease);
}

.n-select--open .n-select__chevron {
  transform: rotate(180deg);
}

.n-select__menu {
  position: fixed;
  z-index: calc(var(--n-z-modal) + 20);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: var(--n-space-1);
  border: 1px solid var(--n-line-strong);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-strong);
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.52);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
}

.n-select__option {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-3);
  padding: var(--n-space-2) var(--n-space-3);
  border: 0;
  border-radius: var(--n-radius-xs);
  background: transparent;
  color: var(--n-text);
  text-align: left;
  cursor: pointer;
}

.n-select__option--active {
  background: var(--n-surface-active);
}

.n-select__option--selected {
  color: var(--n-accent-strong);
}

.n-select__option-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.n-select__option-label {
  overflow: hidden;
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.n-select__option-description {
  overflow: hidden;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.n-select__check {
  flex: none;
}

.n-select-menu-enter-active,
.n-select-menu-leave-active {
  transition: opacity var(--n-duration-fast) var(--n-ease), transform var(--n-duration-fast) var(--n-ease);
  transform-origin: top center;
}

.n-select-menu-enter-from,
.n-select-menu-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.985);
}
</style>

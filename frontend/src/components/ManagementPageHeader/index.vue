<template>
  <div class="management-page-header">
    <div class="management-page-header__title-block">
      <h2 class="management-page-title">{{ title }}</h2>
      <p v-if="subtitle" class="management-page-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="management-page-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
/**
 * 管理后台页面标题区（领域无关）
 *
 * 为知识业务页面与系统管理/监控页面提供统一的标题/说明/操作布局。
 * 不读取任何 feature store，不包含业务逻辑；只负责稳定的标题层级与间距。
 * 视觉规则由 assets/styles/management.scss 的 .management-page-* 统一约束。
 */
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>

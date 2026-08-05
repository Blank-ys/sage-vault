<template>
  <div class="qa-layout">
    <router-view />
  </div>
</template>

<script setup>
// Full-screen layout for the knowledge QA workbench.
// Deliberately omits the RuoYi backend sidebar, navbar, breadcrumb and tags view.
</script>

<style scoped>
.qa-layout {
  position: fixed;
  inset: 0;
  background: var(--el-bg-color-page);
  overflow: hidden;
}
</style>

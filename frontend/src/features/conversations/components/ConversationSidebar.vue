<template>
  <aside class="qa-sidebar">
    <div class="qa-brand">
      <span class="qa-brand-logo"><el-icon><ChatRound /></el-icon></span>
      <span class="qa-brand-text">
        <span class="qa-brand-name">Sage Vault</span>
        <span class="qa-brand-sub">企业知识库问答</span>
      </span>
    </div>

    <div class="qa-new-area">
      <el-button type="primary" class="qa-new-btn" icon="Plus" @click="$emit('new')">新建会话</el-button>
      <el-button v-if="hasAdminAccess" class="qa-admin-btn" icon="Setting" @click="$emit('admin')">管理后台</el-button>
    </div>

    <el-input
      :model-value="searchKey"
      class="qa-search"
      placeholder="搜索会话标题"
      clearable
      @update:model-value="$emit('update:searchKey', $event)"
    >
      <template #prefix><el-icon><Search /></el-icon></template>
    </el-input>

    <div class="qa-list">
      <el-empty v-if="!conversations.length" description="暂无会话" :image-size="56" />
      <div
        v-for="item in conversations"
        :key="item.id"
        class="qa-list-item"
        :class="{ active: item.id === activeId }"
        @click="$emit('select', item.id)"
      >
        <div class="qa-list-item-body">
          <span class="qa-list-item-title" :title="displayTitle(item)">{{ displayTitle(item) }}</span>
          <el-tag v-if="item.knowledgeBaseDeleted" type="info" size="small" class="qa-list-item-tag" disable-transitions>已删除</el-tag>
        </div>
        <el-dropdown trigger="click" @command="(cmd) => onCommand(cmd, item.id)">
          <span class="qa-list-item-more" @click.stop><el-icon><MoreFilled /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
            <el-dropdown-item command="rename" icon="Edit">重命名</el-dropdown-item>
            <el-dropdown-item command="delete" icon="Delete" divided>删除</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { Search, MoreFilled, ChatRound } from '@element-plus/icons-vue'

defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: Number, default: null },
  searchKey: { type: String, default: '' },
  hasAdminAccess: { type: Boolean, default: false }
})

const emit = defineEmits(['update:searchKey', 'select', 'new', 'admin', 'rename', 'delete'])

function displayTitle(conversation) {
  return conversation.title?.trim() || '未命名会话'
}

function onCommand(command, id) {
  if (command === 'rename') emit('rename', id)
  else if (command === 'delete') emit('delete', id)
}
</script>

<style scoped lang="scss">
.qa-sidebar {
  width: 300px;
  flex: none;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
  padding: 16px;
  box-sizing: border-box;
}

.qa-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 4px 16px;
}
.qa-brand-logo {
  width: 36px;
  height: 36px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 20px;
}
.qa-brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.qa-brand-name {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.2;
}
.qa-brand-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qa-new-area {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.qa-new-btn {
  flex: 1;
}
.qa-admin-btn {
  flex: none;
}

.qa-search {
  margin-bottom: 12px;
}

.qa-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin: 0 -4px;
  padding: 0 4px;
}
.qa-list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.qa-list-item:hover {
  background: var(--el-fill-color-light);
}
.qa-list-item.active {
  background: var(--el-color-primary-light-9);
}
.qa-list-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.qa-list-item-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.qa-list-item-tag {
  flex: none;
}
.qa-list-item-more {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}
.qa-list-item-more:hover {
  background: var(--el-fill-color);
  color: var(--el-text-color-primary);
}
</style>

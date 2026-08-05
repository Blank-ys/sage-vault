<template>
  <aside class="qa-sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="qa-sidebar-top">
      <div class="qa-brand">
        <span class="qa-brand-logo"><el-icon><ChatRound /></el-icon></span>
        <span v-if="!collapsed" class="qa-brand-text">
          <span class="qa-brand-name">Sage Vault</span>
          <span class="qa-brand-sub">企业知识库问答</span>
        </span>
      </div>
      <el-tooltip v-if="!collapsed" content="收起侧栏" placement="bottom">
        <button class="qa-icon-action qa-collapse-btn" type="button" @click="$emit('toggle-collapse')">
          <el-icon><Fold /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <div class="qa-new-area">
      <el-tooltip v-if="collapsed" content="新建会话" placement="right">
        <el-button type="primary" class="qa-new-btn qa-new-btn--collapsed" icon="Plus" @click="$emit('new')" />
      </el-tooltip>
      <el-button v-else type="primary" class="qa-new-btn" icon="Plus" @click="$emit('new')">新建会话</el-button>
      <el-tooltip v-if="hasAdminAccess" :content="collapsed ? '管理后台' : ''" placement="right" :disabled="!collapsed">
        <el-button class="qa-admin-btn" :class="{ 'qa-admin-btn--collapsed': collapsed }" icon="Setting" @click="$emit('admin')" />
      </el-tooltip>
      <el-tooltip v-if="collapsed" content="展开侧栏" placement="right">
        <button class="qa-icon-action qa-expand-btn" type="button" @click="$emit('toggle-collapse')">
          <el-icon><Expand /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <el-input
      v-if="!collapsed"
      :model-value="searchKey"
      class="qa-search"
      placeholder="搜索会话标题"
      clearable
      @update:model-value="$emit('update:searchKey', $event)"
    >
      <template #prefix><el-icon><Search /></el-icon></template>
    </el-input>

    <div v-if="!collapsed" class="qa-list">
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

    <div class="qa-footer">
      <el-dropdown trigger="hover" @command="handleUserCommand" placement="top">
        <div class="qa-user">
          <img :src="userStore.avatar" class="qa-user-avatar" :title="collapsed ? userStore.nickName : ''" />
          <span v-if="!collapsed" class="qa-user-name">{{ userStore.nickName }}</span>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">个人中心</el-dropdown-item>
            <el-dropdown-item command="lock">锁定屏幕</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <div v-if="!collapsed" class="qa-footer-actions">
        <header-notice class="qa-footer-notice" />
        <el-tooltip content="主题模式" placement="top">
          <button class="qa-icon-action" type="button" @click="toggleTheme">
            <el-icon v-if="settingsStore.isDark"><Sunny /></el-icon>
            <el-icon v-else><Moon /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ElMessageBox } from 'element-plus'
import { Search, MoreFilled, ChatRound, Fold, Expand, Sunny, Moon } from '@element-plus/icons-vue'
import HeaderNotice from '@/layout/components/HeaderNotice'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: Number, default: null },
  searchKey: { type: String, default: '' },
  hasAdminAccess: { type: Boolean, default: false },
  collapsed: { type: Boolean, default: false }
})

const emit = defineEmits(['update:searchKey', 'select', 'new', 'admin', 'rename', 'delete', 'toggle-collapse'])

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const lockStore = useLockStore()
const settingsStore = useSettingsStore()

function displayTitle(conversation) {
  return conversation.title?.trim() || '未命名会话'
}

function onCommand(command, id) {
  if (command === 'rename') emit('rename', id)
  else if (command === 'delete') emit('delete', id)
}

function handleUserCommand(command) {
  if (command === 'profile') goProfile()
  else if (command === 'lock') lockScreen()
  else if (command === 'logout') logout()
}

// 进入个人中心：从问答进入后返回问答
function goProfile() {
  router.push({ path: '/user/profile', query: { returnTo: '/sage/qa' } })
}

function lockScreen() {
  lockStore.lockScreen(route.fullPath)
  router.push('/lock')
}

function logout() {
  ElMessageBox.confirm('确定注销并退出系统吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    userStore.logOut().then(() => {
      location.href = '/index'
    })
  }).catch(() => {})
}

function toggleTheme() {
  settingsStore.toggleTheme()
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
  transition: width 0.2s ease;

  &.is-collapsed {
    width: 64px;
    padding: 12px 8px;
  }
}

.qa-sidebar-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.qa-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 4px 16px;
  min-width: 0;
  flex: 1;
}
.is-collapsed .qa-brand {
  padding: 4px 0 16px;
  justify-content: center;
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
  color: var(--el-text-color-primary);
}
.qa-brand-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.qa-icon-action {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  padding: 0;
  font-size: 16px;
  transition: background 0.15s, color 0.15s;

  &:hover {
    background: var(--el-fill-color-light);
    color: var(--el-color-primary);
  }
}

.qa-collapse-btn {
  flex: none;
}

.qa-new-area {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.is-collapsed .qa-new-area {
  flex-direction: column;
  align-items: stretch;
}
.qa-new-btn {
  flex: 1;
}
.qa-new-btn--collapsed {
  flex: none;
  width: 100%;
  padding-left: 0;
  padding-right: 0;
}
.qa-admin-btn {
  flex: none;
}
.qa-admin-btn--collapsed {
  width: 100%;
  padding-left: 0;
  padding-right: 0;
}
.qa-expand-btn {
  width: 100%;
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

.qa-footer {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 4px;
}
.is-collapsed .qa-footer {
  flex-direction: column;
  gap: 4px;
  padding-top: 8px;
}
.qa-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: background 0.15s;
  min-width: 0;

  &:hover {
    background: var(--el-fill-color-light);
  }
}
.is-collapsed .qa-user {
  justify-content: center;
  padding: 4px 0;
}
.qa-user-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
  flex: none;
}
.qa-user-name {
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.qa-footer-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  flex: none;
}
.qa-footer-notice {
  display: flex;
  align-items: center;
}
</style>

<template>
  <div class="admin-overview">
    <div class="overview-header">
      <div class="overview-title">
        <h2>Sage Vault 管理后台</h2>
        <p class="overview-subtitle">按现有菜单权限组织的管理功能导航</p>
      </div>
      <el-button type="primary" plain icon="Back" @click="backToWorkspace">返回问答</el-button>
    </div>

    <el-alert
      v-if="!adminMenus.length"
      title="当前账号未配置可用的管理菜单"
      type="info"
      show-icon
      :closable="false"
      class="empty-alert"
    />

    <div v-else class="menu-grid">
      <div
        v-for="menu in adminMenus"
        :key="menu.path"
        class="menu-card"
        @click="enterMenu(menu)"
      >
        <div class="menu-card-icon">
          <svg-icon :icon-class="menu.meta?.icon || 'component'" />
        </div>
        <div class="menu-card-body">
          <div class="menu-card-title">{{ menu.meta?.title || menu.path }}</div>
          <div class="menu-card-desc">{{ menuDescription(menu) }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { isExternal } from '@/utils/validate'
import { getNormalPath } from '@/utils/ruoyi'
import usePermissionStore from '@/store/modules/permission'

const router = useRouter()
const permissionStore = usePermissionStore()

// 只展示后端动态菜单中可见的顶层管理入口
const adminMenus = computed(() => {
  return (permissionStore.topbarRouters || []).filter(route =>
    !route.hidden && route.meta && route.meta.title
  )
})

function firstNavigableChild(route) {
  if (!route.children || !route.children.length) return route
  const visible = route.children.filter(child => !child.hidden)
  if (!visible.length) return route
  // RuoYi 约定：只有一个可见子菜单时直接进入该子菜单
  return visible[0]
}

function resolveMenuPath(route) {
  const target = firstNavigableChild(route)
  if (isExternal(target.path)) return target.path
  const base = route.path || ''
  const childPath = target.path || ''
  return getNormalPath(`${base}/${childPath}`)
}

function enterMenu(route) {
  const path = resolveMenuPath(route)
  if (isExternal(path)) {
    window.open(path, '_blank')
    return
  }
  router.push(path)
}

// 顶层菜单的简短职责说明：沿用子菜单标题作为线索，不伪造统计或新增文案
function menuDescription(route) {
  if (!route.children || !route.children.length) return '进入该管理功能'
  const visible = route.children.filter(child => !child.hidden && child.meta && child.meta.title)
  if (!visible.length) return '进入该管理功能'
  return visible.slice(0, 3).map(child => child.meta.title).join(' · ')
}

function backToWorkspace() {
  router.push('/sage/qa')
}
</script>

<style scoped lang="scss">
.admin-overview {
  padding: 24px;
}

.overview-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
  gap: 16px;
}

.overview-title h2 {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 600;
}

.overview-subtitle {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.empty-alert {
  margin-top: 16px;
}

.menu-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}

.menu-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;

  &:hover {
    border-color: var(--el-color-primary);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
    transform: translateY(-1px);
  }
}

.menu-card-icon {
  width: 40px;
  height: 40px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 20px;
}

.menu-card-body {
  min-width: 0;
}

.menu-card-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-card-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

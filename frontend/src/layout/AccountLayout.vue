<template>
  <div class="account-layout">
    <header class="account-header">
      <div class="account-header-left">
        <el-tooltip content="返回" effect="dark" placement="bottom">
          <el-button text class="account-back" @click="goBack">
            <el-icon><ArrowLeft /></el-icon>
            <span class="account-back-text">返回</span>
          </el-button>
        </el-tooltip>
        <span class="account-title">个人中心</span>
      </div>
      <div class="account-header-right">
        <el-tooltip content="主题模式" effect="dark" placement="bottom">
          <button class="account-icon-btn" type="button" @click="toggleTheme">
            <el-icon v-if="settingsStore.isDark"><Sunny /></el-icon>
            <el-icon v-else><Moon /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip content="消息通知" effect="dark" placement="bottom">
          <header-notice class="account-icon-btn" />
        </el-tooltip>
        <el-dropdown trigger="hover" @command="handleCommand" class="account-avatar">
          <div class="avatar-wrapper">
            <img :src="userStore.avatar" class="user-avatar" />
            <span class="user-nickname">{{ userStore.nickName }}</span>
            <el-icon class="avatar-caret"><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="setLayout" v-if="settingsStore.showSettings">
                <span>布局设置</span>
              </el-dropdown-item>
              <el-dropdown-item command="lockScreen">
                <span>锁定屏幕</span>
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <span>退出登录</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <main class="account-main">
      <router-view />
    </main>
    <settings ref="settingRef" />
  </div>
</template>

<script setup>
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowDown, Sunny, Moon } from '@element-plus/icons-vue'
import HeaderNotice from './components/HeaderNotice'
import Settings from './components/Settings'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const lockStore = useLockStore()
const settingsStore = useSettingsStore()

const settingRef = ref(null)

// 返回来源：优先使用 returnTo 查询参数，无则按来源回退，再无则回到问答工作台
function goBack() {
  const returnTo = route.query.returnTo
  if (returnTo && !Array.isArray(returnTo) && returnTo !== route.fullPath) {
    router.push(returnTo)
    return
  }
  // 默认回到问答工作台
  router.push('/sage/qa')
}

function handleCommand(command) {
  if (command === 'setLayout') setLayout()
  else if (command === 'lockScreen') lockScreen()
  else if (command === 'logout') logout()
}

function setLayout() {
  settingRef.value?.openSetting()
}

function lockScreen() {
  const currentPath = route.fullPath
  lockStore.lockScreen(currentPath)
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

async function toggleTheme(event) {
  const x = event?.clientX || window.innerWidth / 2
  const y = event?.clientY || window.innerHeight / 2
  const wasDark = settingsStore.isDark

  const isReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const isSupported = document.startViewTransition && !isReducedMotion

  if (!isSupported) {
    settingsStore.toggleTheme()
    return
  }

  try {
    const transition = document.startViewTransition(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10))
      settingsStore.toggleTheme()
      await nextTick()
    })
    await transition.ready

    const endRadius = Math.hypot(Math.max(x, window.innerWidth - x), Math.max(y, window.innerHeight - y))
    const clipPath = [`circle(0px at ${x}px ${y}px)`, `circle(${endRadius}px at ${x}px ${y}px)`]
    document.documentElement.animate(
      { clipPath: !wasDark ? [...clipPath].reverse() : clipPath },
      {
        duration: 650,
        easing: 'cubic-bezier(0.4, 0, 0.2, 1)',
        fill: 'forwards',
        pseudoElement: !wasDark ? '::view-transition-old(root)' : '::view-transition-new(root)'
      }
    )
    await transition.finished
  } catch (error) {
    console.warn('View transition failed, falling back to immediate toggle:', error)
    settingsStore.toggleTheme()
  }
}
</script>

<style lang="scss" scoped>
.account-layout {
  position: fixed;
  inset: 0;
  background: var(--el-bg-color-page);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.account-header {
  flex: none;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--navbar-bg);
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  z-index: 10;
}

.account-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.account-back {
  color: var(--el-text-color-primary);
  padding: 0 8px;
  height: 36px;
}

.account-back-text {
  margin-left: 4px;
  font-size: 14px;
}

.account-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-header-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.account-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
  padding: 0;
  font-size: 18px;
  transition: background 0.2s, color 0.2s;

  &:hover {
    background: var(--el-fill-color-light);
    color: var(--el-color-primary);
  }
}

.account-avatar {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 0 4px;
  border-radius: 6px;
  transition: background 0.2s;

  &:hover {
    background: var(--el-fill-color-light);
  }
}

.avatar-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 50px;
}

.user-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
}

.user-nickname {
  font-size: 14px;
  color: var(--el-text-color-primary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.avatar-caret {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.account-main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

@media screen and (max-width: 768px) {
  .account-header {
    padding: 0 8px;
  }

  .account-back-text {
    display: none;
  }

  .user-nickname {
    display: none;
  }

  .avatar-caret {
    display: none;
  }
}
</style>

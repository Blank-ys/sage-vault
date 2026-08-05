import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import { isHttp, isPathMatch } from '@/utils/validate'
import { isRelogin } from '@/utils/request'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'
import usePermissionStore from '@/store/modules/permission'
import { useQaGuardStore } from '@/features/conversations'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login', '/register']

const isWhiteList = (path) => {
  return whiteList.some(pattern => isPathMatch(pattern, path))
}

// 管理后台深链接统一以 /admin 前缀作为路由边界
const isAdminRoute = (path) => path.startsWith('/admin')

router.beforeEach(async (to, from) => {
  NProgress.start()
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title)
    const isLock = useLockStore().isLock
    if (to.path === '/login') {
      NProgress.done()
      return { path: '/' }
    }
    if (isWhiteList(to.path)) {
      return true
    }
    if (isLock && to.path !== '/lock') {
      NProgress.done()
      return { path: '/lock' }
    }
    if (!isLock && to.path === '/lock') {
      NProgress.done()
      return { path: '/' }
    }
    if (useUserStore().roles.length === 0) {
      isRelogin.show = true
      try {
        // 拉取user_info信息
        await useUserStore().getInfo()
        isRelogin.show = false
        // 根据roles权限生成可访问的路由
        const accessRoutes = await usePermissionStore().generateRoutes()
        accessRoutes.forEach(route => {
          if (!isHttp(route.path)) {
            router.addRoute(route)
          }
        })
        // 重新导航到目标路由，确保动态路由已注册
        return { ...to, replace: true }
      } catch (err) {
        await useUserStore().logOut()
        ElMessage.error(err)
        return { path: '/' }
      }
    }
    // 用户信息已就绪：管理后台深链接需要至少一个后台动态菜单权限
    // 隐藏按钮不是授权机制，Java 响应和现有菜单权限仍是最终权威
    if (isAdminRoute(to.path) && !usePermissionStore().hasAdminAccess) {
      NProgress.done()
      return { path: '/sage/qa', query: { adminDenied: '1' } }
    }
    // 问答生成期间离开 /sage/qa 需要先显式停止：守卫只负责拦截并通知问答页，
    // 由问答页展示"停止生成并离开"确认框、调用现有停止接口并在停止成功后重新发起导航。
    // 路由守卫覆盖浏览器后退、URL 直跳等非 UI 触发的导航；UI 内的切换会话、新建会话和
    // 进入管理后台由问答页自身的 guardLeave 拦截，不会到达这里。
    const qaGuard = useQaGuardStore()
    if (from.path === '/sage/qa' && to.path !== '/sage/qa' && qaGuard.needsLeaveConfirm) {
      NProgress.done()
      qaGuard.requestLeave(to)
      return false
    }
    return true
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true
    }
    NProgress.done()
    return `/login?redirect=${to.fullPath}` // 否则全部重定向到登录页
  }
})

router.afterEach(() => {
  NProgress.done()
})

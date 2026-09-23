import { expect, test } from '../fixtures/test'
import type { Page, Route } from '@playwright/test'

type Role = 'ADMIN' | 'USER'

const TARGET_USER = {
  id: 72,
  email: 'target@example.test',
  displayName: '验收目标账户',
  role: 'USER',
  enabled: true,
  createdAt: '2026-09-01T09:00:00',
  lastLoginAt: '2026-09-22T09:00:00',
  authProviders: ['EMAIL'],
  permissions: [],
  quota: { dailyRequestLimit: 100, monthlyTokenLimit: 10000 },
  group: null,
}

async function stubSessionAndAdminApi(page: Page, role: Role, mutations: unknown[] = []) {
  const userId = role === 'ADMIN' ? 9 : 72
  await page.addInitScript(({ id }) => {
    window.sessionStorage.setItem(`jarvis-workspace-session:${id}`, JSON.stringify({
      activeTab: id === 9 ? '管理后台' : '行情',
      workspaceTabs: [id === 9 ? '管理后台' : '行情'],
    }))
  }, { id: userId })

  await page.route((url: URL) => url.pathname.startsWith('/api/'), async (route: Route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    let data: unknown = {}

    if (pathname === '/api/auth/me') {
      data = {
        id: userId,
        email: role === 'ADMIN' ? 'admin@example.test' : TARGET_USER.email,
        displayName: role === 'ADMIN' ? '验收管理员' : TARGET_USER.displayName,
        role,
      }
    } else if (pathname === '/api/auth/csrf') {
      data = { token: 'admin-workspace-csrf' }
    } else if (pathname === '/api/admin/users' && request.method() === 'GET') {
      data = { items: [TARGET_USER], count: 1, total: 1 }
    } else if (pathname === '/api/admin/users/72' && request.method() === 'GET') {
      data = TARGET_USER
    } else if (pathname === '/api/admin/users/72/audit' && request.method() === 'GET') {
      data = []
    } else if (pathname === '/api/admin/users/72/sessions/revoke' && request.method() === 'POST') {
      mutations.push({ pathname, method: request.method(), body: request.postDataJSON() })
      data = { userId: 72, sessionsRevoked: true }
    } else if (pathname === '/api/admin/groups' && request.method() === 'GET') {
      data = { items: [], count: 0 }
    } else if (pathname === '/api/notifications/unread-count') {
      data = { unread: 0 }
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data }),
    })
  })
}

test.describe('管理员工作区 UI', () => {
  test('管理员可查询账户并通过必填原因撤销用户会话', async ({ page }) => {
    const mutations: unknown[] = []
    await stubSessionAndAdminApi(page, 'ADMIN', mutations)
    page.on('dialog', dialog => dialog.accept())

    await page.goto('/')
    await expect(page.getByRole('heading', { name: '访问控制' })).toBeVisible()
    await expect(page.getByRole('option', { name: /验收目标账户/ })).toBeVisible()
    await page.getByRole('option', { name: /验收目标账户/ }).click()

    const reason = page.getByLabel('账户操作原因')
    await expect(reason).toBeVisible()
    await expect(page.getByRole('button', { name: '重置登录状态' })).toBeDisabled()
    await reason.fill('管理员安全验收')
    await page.getByRole('button', { name: '重置登录状态' }).click()

    await expect(page.getByRole('status')).toContainText('现有登录令牌已撤销')
    expect(mutations).toEqual([{
      pathname: '/api/admin/users/72/sessions/revoke',
      method: 'POST',
      body: { reason: '管理员安全验收' },
    }])
  })

  test('普通用户的会话导航恢复不会暴露管理员入口', async ({ page }) => {
    await stubSessionAndAdminApi(page, 'USER')

    await page.goto('/')
    await expect(page.getByRole('navigation', { name: '当前研究对象视图' })).toBeVisible()
    const systemMenu = page.locator('.workspace-function-menu')
      .filter({ has: page.locator('summary', { hasText: /^系统/ }) })
    await systemMenu.locator('summary').click()
    await expect(systemMenu.locator('.function-menu-popover').getByRole('button', { name: /^管理后台/ }))
      .toHaveCount(0)
  })
})

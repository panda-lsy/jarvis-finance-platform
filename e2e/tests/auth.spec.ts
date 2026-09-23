import { type APIRequestContext } from '@playwright/test'
import { expect, test } from '../fixtures/test'
import { API_URL, CREDENTIALS, hasCredentials } from '../utils/env'

/**
 * 后端可用性探测。
 *
 * 需要真实接口的用例必须先过这一关：Java 未起时，前端会把网络异常也渲染成
 * `[role="alert"]`，导致「错误凭据」用例**假通过**——那验证不了任何东西。
 */
async function backendReady(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${API_URL}/api/health/ready`, { timeout: 3_000 })
    return res.ok()
  } catch {
    return false
  }
}

/**
 * 登录与权限流程（对应 SRS V1.1「覆盖登录权限流程」）。
 *
 * 需要真实账号的用例在未配置环境变量时**跳过而非失败**，保证 CI 无账号也能为绿；
 * 需要凭据时应通过 CI secret 注入：
 *   E2E_USER_EMAIL / E2E_USER_PASSWORD
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD
 */
test.describe('登录与权限', () => {
  test('未登录时工作台外壳不应渲染', async ({ workspacePage }) => {
    // 纯前端判定，不依赖后端：即使 `/me` 请求失败，未登录也不该进入工作台。
    await workspacePage.goto()
    await workspacePage.expectShellHidden()
  })

  test('错误凭据应给出错误提示', async ({ loginPage, request }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)

    await loginPage.openLogin()
    await loginPage.login('e2e-nonexistent@example.com', 'not-a-real-password-1234')
    await loginPage.expectError()
  })

  test('普通用户登录后看不到管理员工作区', async ({ loginPage, workspacePage, request, page }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)
    test.skip(!hasCredentials('user'), '未配置 E2E_USER_EMAIL / E2E_USER_PASSWORD，跳过真实登录用例')

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.user.email, CREDENTIALS.user.password)
    await workspacePage.expectShellVisible()

    await page.locator('.workspace-function-menu')
      .filter({ has: page.locator('summary', { hasText: /^系统/ }) })
      .locator('summary')
      .click()
    await expect(page.getByRole('button', { name: /^管理后台/ })).toHaveCount(0)
  })

  test('管理员登录后可查看账户与审计工作区', async ({ loginPage, workspacePage, request, page }) => {
    test.skip(!(await backendReady(request)), `后端未就绪（${API_URL}），跳过需要真实接口的用例`)
    test.skip(!hasCredentials('admin'), '未配置 E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD，跳过管理员用例')

    await loginPage.openLogin()
    await loginPage.login(CREDENTIALS.admin.email, CREDENTIALS.admin.password)
    await workspacePage.expectShellVisible()

    await page.locator('.workspace-function-menu')
      .filter({ has: page.locator('summary', { hasText: /^系统/ }) })
      .locator('summary')
      .click()
    const adminModule = page.getByRole('button', { name: /^管理后台/ })
    await expect(adminModule, 'ADMIN 应能看到管理后台入口').toBeVisible()

    const usersResponsePromise = page.waitForResponse(response =>
      response.url().includes('/api/admin/users') && response.request().method() === 'GET')
    await adminModule.click()
    const usersResponse = await usersResponsePromise
    expect(usersResponse.ok(), '管理员用户目录接口应返回成功').toBeTruthy()
    await expect(page.getByRole('heading', { name: '访问控制' })).toBeVisible()
    await expect(page.getByText('最近审计事件', { exact: false })).toBeVisible()

    const firstUser = page.getByRole('option').first()
    await expect(firstUser, '管理员工作区应显示可查询的账户目录').toBeVisible()
    const auditResponsePromise = page.waitForResponse(response =>
      /\/api\/admin\/users\/\d+\/audit/.test(response.url()) && response.request().method() === 'GET')
    await firstUser.click()
    const auditResponse = await auditResponsePromise
    expect(auditResponse.ok(), '管理员应可查询目标账户的审计事件').toBeTruthy()
    await expect(page.getByLabel('账户操作原因')).toBeVisible()
    await expect(page.getByRole('button', { name: '重置登录状态' })).toBeVisible()
  })
})

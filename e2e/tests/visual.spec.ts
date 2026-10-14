import { expect, test } from '../fixtures/test'

const MODULES = [
  { label: '行情', snapshot: 'market.png' },
  { label: '研究', snapshot: 'research.png' },
  { label: '产业链', snapshot: 'industry-chain.png' },
  { label: '策略', snapshot: 'strategy.png' },
  { label: '交易', snapshot: 'execution.png' },
] as const

test.describe('视觉回归 · Financial Research OS', () => {
  test.setTimeout(75_000)
  test('核心工作区视觉基线', async ({ visualWorkspacePage, page }) => {
    await visualWorkspacePage.openStablePreview()
    for (const module of MODULES) {
      if (module.label !== '行情') await visualWorkspacePage.openWorkspaceModule(module.label)
      if (module.label === '行情' && page.viewportSize()?.width === 768) {
        const addButton = page.getByRole('button', { name: '添加', exact: true })
        await expect(addButton, '窄屏下添加按钮应保持单行且可读').toHaveCSS('white-space', 'nowrap')
        await expect(addButton, '窄屏下添加按钮应有足够点击宽度').toHaveCSS('min-width', '56px')
      }
      await visualWorkspacePage.expectWorkspaceScreenshot(module.snapshot)
    }
    await visualWorkspacePage.expectCommandPaletteWorkflow()
  })
})

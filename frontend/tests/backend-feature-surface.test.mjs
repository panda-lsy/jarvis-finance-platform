import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')
const read = relative => fs.readFileSync(path.join(src, relative), 'utf8')

test('backend scheduled-task capabilities have a visible frontend workspace', () => {
  const client = read('api/client.js')
  const page = read('pages/ScheduledTasksPage.vue')
  const modules = read('analysis-os/data/modules.js')
  const tabs = read('composables/useWorkspaceTabs.js')

  assert.match(client, /scheduledTasks:/)
  assert.match(client, /scheduledTaskTypes:/)
  assert.match(client, /createScheduledTask:/)
  assert.match(client, /updateScheduledTask:/)
  assert.match(client, /pauseScheduledTask:/)
  assert.match(client, /resumeScheduledTask:/)
  assert.match(client, /runScheduledTask:/)
  assert.match(client, /deleteScheduledTask:/)
  assert.match(client, /scheduledTaskRuns:/)

  assert.match(modules, /labelZh: '定时任务'/)
  assert.match(tabs, /'定时任务'/)
  assert.match(page, /新建任务/)
  assert.match(page, /立即执行/)
  assert.match(page, /暂停/)
  assert.match(page, /恢复/)
  assert.match(page, /执行历史/)
  assert.match(page, /执行器未就绪/)
  assert.match(page, /DAILY_DIGEST/)
  assert.match(page, /analyzeNews/)
})

test('scheduled-task write actions are gated by the TASK_MANAGE capability', () => {
  const client = read('api/client.js')
  const page = read('pages/ScheduledTasksPage.vue')

  // 后端只拦"会消耗资源"的动作，前端要在点下去之前就把它们置灰 ——
  // 只靠 403 的话，用户填完整个表单才被告知没权限。
  assert.match(client, /scheduledTaskCapabilities:/)
  assert.match(client, /\/api\/scheduled-tasks\/capabilities/)
  assert.match(page, /const canManage = ref\(true\)/)
  // 默认放行：探测失败不能把本来能用的用户误挡在门外，门禁仍在服务端。
  assert.match(page, /canManage\.value = capabilityResponse\?\.data\?\.can_manage !== false/)

  // 四个消耗资源的动作按能力置灰……
  assert.match(page, /:disabled="!canManage" @click="openCreate"/)
  assert.match(page, /:disabled="!canManage" @click="openEdit\(task\)"/)
  assert.match(page, /:disabled="!canManage \|\| actionId === task\.id" @click="taskAction\(task, 'run'\)"/)
  assert.match(page, /:disabled="!canManage \|\| actionId === task\.id" @click="taskAction\(task, 'resume'\)"/)

  // ……而暂停与删除刻意不加：被收回权限的账号仍必须能关掉自己还在跑的任务。
  assert.match(page, /:disabled="actionId === task\.id" @click="taskAction\(task, 'pause'\)"/)
  assert.match(page, /:disabled="actionId === task\.id" @click="taskAction\(task, 'delete'\)"/)
})

test('admin backend is surfaced as an ADMIN-only SYSTEM workspace instead of a legacy tab', () => {
  const app = read('App.vue')
  const modules = read('analysis-os/data/modules.js')
  const tabs = read('composables/useWorkspaceTabs.js')
  const shell = read('analysis-os/components/ArchiveWorkspaceShell.vue')

  assert.match(modules, /ADMIN_WORKSPACE_MODULE/)
  assert.match(modules, /labelZh: '管理后台'/)
  assert.match(modules, /category: 'SYSTEM'/)
  assert.match(modules, /adminOnly: true/)
  assert.match(app, /user\.value\?\.role === 'ADMIN'/)
  assert.match(app, /\[\.\.\.JARVIS_MODULES, ADMIN_WORKSPACE_MODULE\]/)
  assert.match(app, /workspaceRenderRoute === '管理后台'/)
  assert.match(tabs, /const ADMIN_TAB = '管理后台'/)
  assert.doesNotMatch(shell, /旧版后台/)
})

test('admin workspace exposes persisted group quota and permission management', () => {
  const client = read('api/client.js')
  const admin = read('components/AdminView.vue')
  const backend = fs.readFileSync(path.resolve(here, '../../java-backend/src/main/java/com/jarvis/research/admin/AdminController.java'), 'utf8')

  assert.match(client, /adminGroups:/)
  assert.match(client, /adminCreateGroup:/)
  assert.match(client, /adminRevokeSessions:/)
  assert.match(client, /method: 'DELETE', body: JSON\.stringify\(\{ reason \}\)/)
  assert.match(client, /adminUpdateGroupMembers:/)
  assert.match(client, /adminUpdateGroupQuota:/)
  assert.match(client, /adminUpdateGroupPermissions:/)
  assert.match(admin, /用户组策略/)
  assert.match(admin, /重置登录状态/)
  assert.match(admin, /reason: accountActionReason\.value\.trim\(\)/)
  assert.match(backend, /@PostMapping\("\/users\/\{userId\}\/sessions\/revoke"\)/)
  assert.match(backend, /@Valid @RequestBody ReasonRequest body/)
  assert.match(admin, /保存成员/)
  assert.match(admin, /保存组配额/)
  assert.match(admin, /保存组权限/)
})

test('RSS information center exposes persisted subscriptions and admin source management', () => {
  const client = read('api/client.js')
  const app = read('App.vue')
  const modules = read('analysis-os/data/modules.js')
  const page = read('pages/NewsCenterPage.vue')
  const admin = read('components/NewsSourceAdminPanel.vue')

  assert.match(client, /newsSources:/)
  assert.match(client, /newsSubscriptions:/)
  assert.match(client, /saveNewsSubscriptions:/)
  assert.match(client, /adminNewsSources:/)
  assert.match(client, /adminUpdateNewsSource:/)
  assert.match(client, /newsAnalyze:/)
  assert.match(app, /workspaceRenderRoute === 'RSS资讯'/)
  assert.match(modules, /labelZh: 'RSS资讯'/)
  assert.match(page, /保存订阅/)
  assert.match(page, /全部来源/)
  assert.match(page, /AI分析当前资讯/)
  assert.match(page, /aiAnalysis/)
  assert.match(page, /ai_analysis/)
  assert.match(page, /navigate-module/)
  assert.match(admin, /RSS 来源管理/)
  assert.match(admin, /停用来源/)
})

test('test agent turns PRD into cases and sanitized browser defect reports', () => {
  const agent = fs.readFileSync(path.resolve(here, '../../tools/test-agent/test-agent.mjs'), 'utf8')
  const guide = fs.readFileSync(path.resolve(here, '../../tools/test-agent/README.md'), 'utf8')
  assert.match(agent, /extractRequirements/)
  assert.match(agent, /defectsFromRun/)
  assert.match(agent, /test-cases\.json/)
  assert.match(agent, /REDACTED/)
  assert.match(guide, /--run/)
})

test('backend notifications are surfaced in the workspace header', () => {
  const client = read('api/client.js')
  const center = read('components/common/NotificationCenter.vue')
  const shell = read('analysis-os/components/ArchiveWorkspaceShell.vue')

  assert.match(client, /notifications:/)
  assert.match(client, /notificationUnreadCount:/)
  assert.match(client, /markNotificationRead:/)
  assert.match(client, /markAllNotificationsRead:/)
  assert.match(client, /deleteNotification:/)
  assert.match(center, /window\.setInterval\(loadUnread, 15000\)/)
  assert.match(center, /item\?\.link\?\.kind === 'SCHEDULED_TASK'/)
  assert.match(shell, /<NotificationCenter v-if="props\.user"/)
})

test('user audit events are surfaced in Ops instead of remaining API-only', () => {
  const client = read('api/client.js')
  const ops = read('components/OpsView.vue')

  assert.match(client, /auditRecent:/)
  assert.match(ops, /api\.auditRecent\(20\)/)
  assert.match(ops, /最近审计事件/)
  assert.match(ops, /event\.action/)
})

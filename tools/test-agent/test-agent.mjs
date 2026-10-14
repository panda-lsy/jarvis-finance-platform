#!/usr/bin/env node

/**
 * JARVIS 测试智能体（首版）。
 *
 * 目标不是替代 Playwright，而是把“读 PRD → 生成验收用例 → 运行已有浏览器用例
 * → 形成缺陷报告”串成一个可重复的命令。它只读需求文档和测试结果，不会修改账号、
 * 交易或生产数据；浏览器凭据仍只从 E2E_* 环境变量读取。
 */
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import process from 'node:process'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')

function usage() {
  console.log(`用法：
  node tools/test-agent/test-agent.mjs --prd <PRD.md> [--output <目录>]
      [--run] [--project smoke] [--base-url <URL>] [--max-cases 80]

默认只生成需求用例与报告；加 --run 才会执行已有 Playwright 用例。--project 支持
smoke、auth、visual、financial-import 以及 e2e/playwright.config.ts 中定义的其他项目。
`)
}

function parseArgs(argv) {
  const args = { prd: 'specs/financial-agent-workflow/requirements.md', output: '.test-agent/latest', run: false, project: 'smoke', maxCases: 80, baseUrl: '' }
  for (let i = 2; i < argv.length; i += 1) {
    const value = argv[i]
    if (value === '--help' || value === '-h') { usage(); process.exit(0) }
    if (value === '--run') { args.run = true; continue }
    if (value === '--prd') { args.prd = argv[++i]; continue }
    if (value === '--output') { args.output = argv[++i]; continue }
    if (value === '--project') { args.project = argv[++i]; continue }
    if (value === '--max-cases') { args.maxCases = Math.max(1, Number(argv[++i]) || 80); continue }
    if (value === '--base-url') { args.baseUrl = argv[++i]; continue }
    throw new Error(`未知参数：${value}`)
  }
  return args
}

function absolute(value) {
  return path.isAbsolute(value) ? value : path.resolve(ROOT, value)
}

function priorityOf(text) {
  const match = String(text).match(/\b(P[0-2])\b/i)
  return match ? match[1].toUpperCase() : 'P1'
}

function cleanTitle(value) {
  return String(value)
    .replace(/`[^`]*`/g, '')
    .replace(/https?:\/\/\S+/g, '')
    .replace(/\s+/g, ' ')
    .replace(/[：:；;，,。！？!?]+$/g, '')
    .trim()
}

function extractRequirements(markdown, maxCases) {
  const lines = markdown.split(/\r?\n/)
  const cases = []
  const seen = new Set()
  const add = (title, line, source) => {
    const clean = cleanTitle(title)
    if (clean.length < 8 || clean.length > 180 || seen.has(clean)) return
    const priority = priorityOf(title)
    const id = `REQ-${String(cases.length + 1).padStart(3, '0')}`
    const normalized = clean.replace(/^\[[^\]]+\]\s*/, '')
    seen.add(clean)
    cases.push({
      id,
      priority,
      title: normalized,
      source: `${source}:${line}`,
      steps: ['打开目标环境', `验证“${normalized}”`, '记录结果与可复现证据'],
      expected: `满足需求“${normalized}”，且失败时能定位到页面、接口或数据链路。`,
      status: 'NOT_RUN',
      mappedProjects: projectHints(normalized),
    })
  }

  lines.forEach((raw, index) => {
    const line = index + 1
    const value = raw.trim()
    if (!value || value.startsWith('```') || value.startsWith('|---')) return
    const heading = value.match(/^#{2,6}\s+(.+)$/)
    const item = value.match(/^(?:[-*+]\s+|\d+[.)]\s+)(.+)$/)
    const candidate = heading?.[1] || item?.[1]
    if (!candidate) return
    const relevant = /P[0-2]|必须|支持|能够|应当|验收|登录|行情|资讯|财报|交易|Agent|智能体|定时|风险|测试/i.test(candidate)
    if (relevant) add(candidate, line, 'PRD')
  })

  return cases.slice(0, maxCases)
}

function projectHints(title) {
  const value = String(title)
  const hints = []
  if (/登录|注册|OAuth|Github|GitHub|邮箱|权限|账号|配额|管理员|审计/.test(value)) hints.push('auth')
  if (/财报|文件|PDF|DOCX|Markdown|图片|识别|原文|上传|导入/.test(value)) hints.push('financial-import')
  if (/Agent|智能体|AI|工具调用|Tool Calling|Trace|执行过程|研究|分析|资讯|新闻|风险|舆情/.test(value)) hints.push('agent')
  if (/定时|调度|任务|运行历史|断线|恢复|通知|日报|提醒/.test(value)) hints.push('tasks')
  if (/定时任务|任务权限|权限置灰/.test(value)) hints.push('permissions')
  if (/首页|工作台|行情|交易|模拟盘|页面|主题|夜间|响应式|图表|K线|UI/.test(value)) hints.push('visual')
  if (!hints.length) hints.push('smoke')
  return [...new Set(hints)]
}

function runPlaywright(project, baseUrl) {
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  const env = { ...process.env }
  if (baseUrl) env.E2E_BASE_URL = baseUrl
  const scriptedProjects = new Set(['smoke', 'auth', 'visual', 'financial-import'])
  const script = scriptedProjects.has(project) ? `test:${project}` : 'test'
  const projectArgs = scriptedProjects.has(project) ? [] : [`--project=${project}`]
  const result = spawnSync(npm, ['--prefix', 'e2e', 'run', script, '--', ...projectArgs, '--reporter=json'], {
    cwd: ROOT,
    env,
    encoding: 'utf8',
    maxBuffer: 12 * 1024 * 1024,
    shell: process.platform === 'win32',
  })
  const output = `${result.stdout || ''}\n${result.stderr || ''}${result.error ? `\n${result.error.message}` : ''}`
  const json = parseJsonReport(output)
  return { exitCode: result.status ?? 1, json, output: redactOutput(output) }
}

function parseJsonReport(output) {
  const starts = []
  for (let index = output.indexOf('{'); index >= 0; index = output.indexOf('{', index + 1)) starts.push(index)
  for (const index of starts.reverse()) {
    try {
      const value = JSON.parse(output.slice(index))
      if (value && Array.isArray(value.suites)) return value
    } catch (_) {}
  }
  return null
}

function redactOutput(value) {
  return String(value)
    .replace(/(password|token|authorization|cookie|api[_-]?key)\s*[:=]\s*[^\s,;]+/gi, '$1=[REDACTED]')
    .replace(/Bearer\s+[A-Za-z0-9._-]+/gi, 'Bearer [REDACTED]')
}

function flattenResults(suites, output = []) {
  for (const suite of suites || []) {
    for (const spec of suite.specs || []) {
      for (const test of spec.tests || []) {
        const result = test.results?.[test.results.length - 1] || {}
        output.push({
          title: [suite.title, spec.title].filter(Boolean).join(' › '),
          file: spec.file,
          status: result.status || test.status || 'unknown',
          error: result.error?.message || result.errors?.[0]?.message || '',
        })
      }
    }
    flattenResults(suite.suites, output)
  }
  return output
}

function updateCases(cases, run) {
  if (!run?.json) return cases
  const results = flattenResults(run.json.suites)
  return cases.map(item => {
    const matches = results.filter(result => item.mappedProjects.some(project => result.file?.includes(`${project}.spec.ts`)))
    if (!matches.length) return item
    const failed = matches.find(result => ['failed', 'timedOut', 'interrupted'].includes(result.status))
    return { ...item, status: failed ? 'FAILED' : 'PASSED', evidence: matches.map(result => ({ title: result.title, status: result.status, file: result.file, error: result.error })) }
  })
}

function defectsFromRun(run) {
  if (!run?.json) return []
  return flattenResults(run.json.suites)
    .filter(item => ['failed', 'timedOut', 'interrupted'].includes(item.status))
    .map((item, index) => ({
      id: `DEF-${String(index + 1).padStart(3, '0')}`,
      severity: /auth|login|登录/i.test(item.title) ? 'P0' : 'P1',
      title: item.title,
      file: item.file || 'unknown',
      error: item.error || '测试失败但没有返回错误摘要',
      nextAction: '在目标环境复现，保留 Playwright trace/screenshot 后修复并重跑该用例。',
    }))
}

function renderReport(args, cases, run, defects) {
  const counts = cases.reduce((result, item) => {
    result[item.status] = (result[item.status] || 0) + 1
    return result
  }, {})
  const lines = [
    '# JARVIS 测试智能体报告',
    '',
    `- 需求文档：\`${path.relative(ROOT, absolute(args.prd))}\``,
    `- 生成时间：${new Date().toISOString()}`,
    `- 执行模式：${args.run ? `Playwright ${args.project}` : '仅生成需求用例（未执行浏览器）'}`,
    `- 用例统计：${Object.entries(counts).map(([key, value]) => `${key}=${value}`).join('，') || '无'}`,
    '',
    '## 可执行结论',
    '',
    defects.length ? `发现 ${defects.length} 个浏览器测试失败，详见下方缺陷清单。` : (args.run ? '本次已运行的浏览器用例没有发现失败。' : '本次未执行浏览器；请在真实环境加 --run 进行验收。'),
    '',
    '## 缺陷清单',
    '',
  ]
  if (!defects.length) lines.push('无。')
  for (const defect of defects) {
    lines.push(`### ${defect.id} [${defect.severity}] ${defect.title}`, '', `- 文件：\`${defect.file}\``, `- 错误：${defect.error.replace(/\r?\n/g, ' ')}`, `- 下一步：${defect.nextAction}`, '')
  }
  lines.push('## 需求用例', '', '| 编号 | 优先级 | 状态 | 需求 | 来源 |', '|---|---|---|---|---|')
  for (const item of cases) lines.push(`| ${item.id} | ${item.priority} | ${item.status} | ${item.title.replaceAll('|', '\\|')} | ${item.source} |`)
  if (run?.output) lines.push('', '## 运行摘要（已脱敏）', '', '```text', run.output.slice(-4000), '```')
  return `${lines.join('\n')}\n`
}

function main() {
  const args = parseArgs(process.argv)
  const prdPath = absolute(args.prd)
  if (!fs.existsSync(prdPath)) throw new Error(`需求文档不存在：${prdPath}`)
  const cases = extractRequirements(fs.readFileSync(prdPath, 'utf8'), args.maxCases)
  const run = args.run ? runPlaywright(args.project, args.baseUrl) : null
  const updatedCases = updateCases(cases, run)
  const defects = defectsFromRun(run)
  const outputDir = absolute(args.output)
  fs.mkdirSync(outputDir, { recursive: true })
  fs.writeFileSync(path.join(outputDir, 'test-cases.json'), `${JSON.stringify({ generatedAt: new Date().toISOString(), source: path.relative(ROOT, prdPath), cases: updatedCases }, null, 2)}\n`)
  fs.writeFileSync(path.join(outputDir, 'defect-report.md'), renderReport(args, updatedCases, run, defects))
  console.log(`测试智能体已生成：${path.relative(ROOT, path.join(outputDir, 'test-cases.json'))}`)
  console.log(`缺陷报告：${path.relative(ROOT, path.join(outputDir, 'defect-report.md'))}`)
  if (run) console.log(`Playwright exit=${run.exitCode}，失败数=${defects.length}`)
  if (run?.exitCode && run.exitCode !== 0) process.exitCode = 2
}

try {
  main()
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
}

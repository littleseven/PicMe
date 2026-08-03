export const meta = {
  name: 'virtual-team',
  description: '虚拟产品技术团队:PM→架构→设计→(Dev→Review→QA)→验收,把一个应用需求自主开发出来',
  whenToUse: '当用户要虚拟团队自主开发一个应用/功能时调用,args 传应用需求',
  phases: [
    { title: '产品', detail: 'PM 产出 PRD(可机器判定的验收标准)' },
    { title: '架构', detail: '架构师拆任务清单(带改动范围+验收命令)' },
    { title: '设计', detail: '设计师产 DESIGN.md' },
    { title: '实现', detail: 'pipeline: Dev→Reviewer(打回循环)→QA' },
    { title: '验收', detail: 'PM 汇总验收' },
  ],
}

// ===== 参数 =====
// args: 字符串 = 应用需求;或 { requirement, maxReviewRounds, budget }
const requirement = (typeof args === 'string')
  ? args
  : (args && args.requirement) || '未指定需求'
const MAX_REVIEW_ROUNDS = (args && typeof args === 'object' && args.maxReviewRounds) || 2

// ===== Schema =====
const TASKS_SCHEMA = {
  type: 'object',
  properties: {
    tasks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          description: { type: 'string', description: '具体到文件改动的任务描述' },
          scope: { type: 'string', description: '允许改动的文件/目录范围' },
          accept_cmd: { type: 'string', description: '可自动跑的验收命令' },
        },
        required: ['id', 'description', 'accept_cmd'],
      },
    },
  },
  required: ['tasks'],
}
const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['pass', 'reject'] },
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          line: { type: 'number' },
          problem: { type: 'string' },
          suggestion: { type: 'string' },
        },
      },
    },
  },
  required: ['verdict'],
}
const QA_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['pass', 'bug'] },
    tests_run: { type: 'array', items: { type: 'string' } },
    failures: { type: 'array', items: { type: 'string' } },
    env_only_failures: { type: 'array', items: { type: 'string' } },
  },
  required: ['verdict'],
}

// ===== Phase 1: PM 产 PRD =====
phase('产品')
const prd = await agent(
  `把以下需求转成 PRD(用户故事 + 可机器判定的验收标准 + 非目标)。\n\n需求:\n${requirement}`,
  { label: 'PM', phase: '产品', agentType: 'vt-pm' }
)
log('PRD 产出完成')

// ===== Phase 2: 架构师产方案 + 结构化任务清单 =====
phase('架构')
const archResult = await agent(
  `基于以下 PRD 产出技术方案(ARCHITECTURE.md)。\n\nPRD:\n${prd}`,
  { label: 'Architect', phase: '架构', agentType: 'vt-architect' }
)
const taskList = await agent(
  `基于以下架构产出,输出结构化任务清单。每任务含 id/description(具体到文件)/scope(改动范围)/accept_cmd(验收命令)。\n\n架构产出:\n${archResult}`,
  { label: 'TaskParse', phase: '架构', schema: TASKS_SCHEMA, agentType: 'vt-architect' }
)
if (!taskList.tasks || !taskList.tasks.length) {
  log('⚠️ 架构师未产出任务,终止')
  return { prd, archResult, error: 'no tasks' }
}
log(`任务清单解析完成,共 ${taskList.tasks.length} 个任务`)

// ===== Phase 3: 设计师产设计规范 =====
phase('设计')
const design = await agent(
  `基于以下 PRD 产出界面/交互/组件设计规范(DESIGN.md)。\n\nPRD:\n${prd}`,
  { label: 'Designer', phase: '设计', agentType: 'vt-designer' }
)
log('设计规范产出完成')

// ===== Phase 4: 任务流水线 Dev → Review(打回循环) → QA =====
phase('实现')
const results = await pipeline(
  taskList.tasks,
  // Stage 1: Dev 实现
  (task) => agent(
    `实现任务 ${task.id}:${task.description}\n改动范围:${task.scope || '按需'}\n验收命令:${task.accept_cmd}\n\n遵循架构:\n${archResult}\n\n设计规范:\n${design}`,
    { label: `Dev:${task.id}`, phase: '实现', agentType: 'vt-dev' }
  ).then((devOut) => ({ task, devOut })),

  // Stage 2: Reviewer(含打回重做循环,最多 MAX_REVIEW_ROUNDS 轮)
  ({ task, devOut }) => {
    return (async () => {
      let currentDev = devOut
      let review = { verdict: 'reject', issues: [] }
      for (let round = 1; round <= MAX_REVIEW_ROUNDS; round++) {
        review = await agent(
          `审查以下开发产出是否守项目硬规则(无FQN/无wildcard import/lambda显式命名/log tag/缩进/i18N三语/模块边界/隐私红线/范围)。输出 JSON。\n任务 ${task.id}:${task.description}\n改动范围:${task.scope}\n开发产出:\n${currentDev}`,
          { label: `Review:${task.id}#r${round}`, phase: '实现', schema: REVIEW_SCHEMA, agentType: 'vt-reviewer' }
        )
        if (review.verdict === 'pass') {
          return { task, devOut: currentDev, review, reviewRounds: round }
        }
        log(`任务 ${task.id} 第 ${round}/${MAX_REVIEW_ROUNDS} 轮被 reviewer 打回,重做`)
        currentDev = await agent(
          `针对 review 指出的问题修复后重新实现任务 ${task.id}:${task.description}\n改动范围:${task.scope}\n验收命令:${task.accept_cmd}\n\nReview 问题:\n${JSON.stringify(review.issues)}`,
          { label: `Dev-fix:${task.id}#r${round}`, phase: '实现', agentType: 'vt-dev' }
        )
      }
      // 超限:最后一轮 review 状态即为最终
      return { task, devOut: currentDev, review, reviewRounds: MAX_REVIEW_ROUNDS, reviewExhausted: true }
    })()
  },

  // Stage 3: QA(reject 跳过;pass 才测)
  ({ task, devOut, review }) => {
    if (review.verdict !== 'pass') {
      log(`任务 ${task.id} review 未通过(${review.verdict}),跳过 QA`)
      return { task, devOut, review, qa: { verdict: 'skipped', reason: 'review not passed' } }
    }
    return agent(
      `为任务 ${task.id} 写自动化测试并跑验收命令,输出 JSON。\n任务:${task.description}\n验收命令:${task.accept_cmd}\n开发产出:\n${devOut}`,
      { label: `QA:${task.id}`, phase: '实现', schema: QA_SCHEMA, agentType: 'vt-qa' }
    ).then((qa) => ({ task, devOut, review, qa }))
  }
)

const passed = results.filter((r) => r.qa && r.qa.verdict === 'pass')
const failed = results.filter((r) => !(r.qa && r.qa.verdict === 'pass'))
log(`实现阶段完成:QA 通过 ${passed.length}/${results.length}`)

// ===== Phase 5: PM 验收 =====
phase('验收')
const summary = results.map((r) => ({
  task: r.task.id,
  review: r.review.verdict,
  qa: r.qa.verdict,
  reviewRounds: r.reviewRounds,
}))
const acceptReport = await agent(
  `作为 PM 做最终验收:对照 PRD 验收标准,判断是否达成,给出结论与遗留项。\n\nPRD:\n${prd}\n\n任务结果:\n${JSON.stringify(summary, null, 2)}\n\n未通过明细:\n${JSON.stringify(failed.map((r) => ({ task: r.task.id, qa: r.qa })), null, 2)}`,
  { label: 'Accept', phase: '验收', agentType: 'vt-pm' }
)

return {
  prd,
  archResult,
  design,
  taskCount: results.length,
  passed: passed.length,
  failed: failed.length,
  failedTasks: failed.map((r) => r.task.id),
  acceptReport,
}

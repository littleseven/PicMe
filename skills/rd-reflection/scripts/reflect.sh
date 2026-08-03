#!/bin/bash
# reflect.sh — 任务复盘脚本：引导 5 个问题，自动更新 CHECKLIST 和 EXPERIENCE_LOG

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DATE=$(date +%Y-%m-%d)

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔍 任务复盘 — 请回答以下 5 个问题"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 读取 TASK_LOG 内容（如果存在）
if [ -f "$SKILL_DIR/TASK_LOG.md" ]; then
    echo "📄 发现 TASK_LOG，内容摘要:"
    tail -20 "$SKILL_DIR/TASK_LOG.md"
    echo ""
fi

echo "📋 复盘模板（请复制到 EXPERIENCE_LOG.md 中并填写）:"
echo ""

cat << 'EOF'
## [YYYY-MM-DD] 任务标题

**任务描述**: 
**关联技能**: 
**预估耗时**: 
**实际耗时**: 
**时间偏差**: 

### 陷阱清单

| # | 陷阱描述 | 级别 | 已有 skill 覆盖 | 时间浪费 | 根因类别 |
|---|----------|------|-----------------|----------|----------|
| 1 | | | | | |

### 根因详解

### 措施落地

| 措施 | 目标资产 | 状态 |
|------|----------|------|
| | | |

### 检查清单更新

- [ ] 

### 一句话总结

> 
EOF

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📝 复盘完成后，执行以下操作:"
echo ""
echo "1. 将复盘内容追加到 EXPERIENCE_LOG.md"
echo "2. 更新 CHECKLIST.md（新增或升级陷阱级别）"
echo "3. 如有必要，联动更新相关 skill"
echo ""
echo "自动化提示:"
echo "- 如果陷阱被重复踩到，自动在 CHECKLIST.md 中标记 🔴 高频"
echo "- 如果经验与 skill 文档冲突，标记 ⚠️ 文档漂移"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

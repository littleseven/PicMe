#!/bin/bash
# new_task.sh — 启动新任务，读取动态检查清单并初始化 TASK_LOG

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TASK_NAME="${1:-未命名任务}"
DATE=$(date +%Y-%m-%d)

# 1. 显示当前检查清单
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 新任务启动: $TASK_NAME"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📋 动态检查清单（高频陷阱）:"
echo ""
grep -A 20 "🔴 高频陷阱" "$SKILL_DIR/CHECKLIST.md" | head -30
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "💡 提示: 编码前务必完成清单中的 [ ] 项"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 2. 初始化 TASK_LOG
cat > "$SKILL_DIR/TASK_LOG.md" << EOF
# [$DATE] $TASK_NAME — 实时任务日志

> 任务执行过程中实时记录遇到的陷阱、异常耗时点、与文档不一致的发现。
> 任务完成后，本文件内容将归档到 EXPERIENCE_LOG.md。

## 开始时间: $(date +%H:%M)

## 实时记录

- [$(date +%H:%M)] 任务启动

EOF

echo "✅ TASK_LOG 已初始化: $SKILL_DIR/TASK_LOG.md"

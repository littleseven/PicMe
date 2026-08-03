#!/bin/bash
# evolution_report.sh — 生成月度/周度进化报告

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_FILE="$SKILL_DIR/EXPERIENCE_LOG.md"
REPORT_FILE="$SKILL_DIR/EVOLUTION_REPORT.md"
PERIOD="${1:-30}"  # 默认分析最近 30 天

if [ ! -f "$LOG_FILE" ]; then
    echo "❌ EXPERIENCE_LOG.md 不存在"
    exit 1
fi

# 统计陷阱级别（匹配表格中的级别列，如 "| P0 |" 或 "| P1 |"）
P0_COUNT=$(grep -E "\|\s*P0\s*\|" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')
P1_COUNT=$(grep -E "\|\s*P1\s*\|" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')
P2_COUNT=$(grep -E "\|\s*P2\s*\|" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')

# 统计根因类别
KNOWLEDGE_GAP=$(grep "知识盲区" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')
PROCESS_GAP=$(grep "流程缺失" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')
TOOL_GAP=$(grep "工具不熟" "$LOG_FILE" 2>/dev/null | wc -l | tr -d ' ')

# 生成报告
cat > "$REPORT_FILE" << EOF
# RD 进化报告（$(date +%Y-%m-%d)）

> 统计周期: 最近 ${PERIOD} 天
> 数据来源: EXPERIENCE_LOG.md

---

## 📊 整体统计

| 指标 | 数值 |
|------|------|
| 总任务数 | $(grep -c "## \[" "$LOG_FILE") |
| P0 陷阱数 | $P0_COUNT |
| P1 陷阱数 | $P1_COUNT |
| P2 陷阱数 | $P2_COUNT |

## 🔍 根因分布

| 根因类别 | 次数 | 占比 |
|----------|------|------|
| 知识盲区 | $KNOWLEDGE_GAP | $(echo "scale=1; $KNOWLEDGE_GAP * 100 / ($KNOWLEDGE_GAP + $PROCESS_GAP + $TOOL_GAP)" | bc 2>/dev/null || echo "N/A")% |
| 流程缺失 | $PROCESS_GAP | $(echo "scale=1; $PROCESS_GAP * 100 / ($KNOWLEDGE_GAP + $PROCESS_GAP + $TOOL_GAP)" | bc 2>/dev/null || echo "N/A")% |
| 工具不熟 | $TOOL_GAP | $(echo "scale=1; $TOOL_GAP * 100 / ($KNOWLEDGE_GAP + $PROCESS_GAP + $TOOL_GAP)" | bc 2>/dev/null || echo "N/A")% |

## 🚨 高频陷阱 TOP N

$(grep -B 2 "🔴 P0\|🟠 P1" "$LOG_FILE" | grep "陷阱描述" | sort | uniq -c | sort -rn | head -5 | sed 's/^/| /; s/$/ |/')

## 📈 趋势分析

- 知识盲区类陷阱占比最高 → 建议加强前置调研和 skill 阅读
- 流程缺失类陷阱 → 建议完善 CHECKLIST.md 并强制执行

## 🎯 下阶段重点

1. 强化编码前检查清单执行率
2. 将高频陷阱自动化检测（如编译时检查 when 分支完整性）
3. 定期 review skill 文档，消除 ⚠️ 文档漂移

---

*报告生成时间: $(date)*
EOF

echo "✅ 进化报告已生成: $REPORT_FILE"

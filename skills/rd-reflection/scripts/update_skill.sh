#!/bin/bash
# update_skill.sh — 将经验同步到相关 skill 的"常见陷阱"章节

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_SKILL="${1:-}"

if [ -z "$TARGET_SKILL" ]; then
    echo "用法: $0 <skill-name>"
    echo "示例: $0 adb-bot"
    exit 1
fi

TARGET_FILE="$SKILL_DIR/../../$TARGET_SKILL/SKILL.md"

if [ ! -f "$TARGET_FILE" ]; then
    echo "❌ 目标 skill 不存在: $TARGET_FILE"
    echo "可用 skills:"
    ls "$SKILL_DIR/../" | grep -v README
    exit 1
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🔄 将经验同步到 skill: $TARGET_SKILL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "目标文件: $TARGET_FILE"
echo ""
echo "💡 请从 EXPERIENCE_LOG.md 中提取相关经验，"
echo "   追加到 $TARGET_SKILL/SKILL.md 的\"常见陷阱\"章节。"
echo ""
echo "建议追加格式:"
echo ""
cat << 'EOF'
### [新增] 陷阱标题

**场景**: xxx
**错误做法**: xxx
**正确做法**: xxx
**关联经验**: [YYYY-MM-DD] 任务名 — 浪费 X 分钟
EOF

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

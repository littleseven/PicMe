#!/bin/bash
#
# check-skill-sync.sh - Skills / Commands 漂移校验
# 用途：检查 .claude/commands/ 与 skills/（SSOT）之间的命名与内容一致性
# 调用：./scripts/check-skill-sync.sh
#
# 说明：
#   - skills/ 是唯一事实来源（SSOT），OpenCode / Kimi 通过软链共享
#   - .claude/commands/ 是 Claude Code 专用镜像（格式不同：无 frontmatter）
#   - 本脚本只报告漂移，不自动覆盖，避免丢失任一侧独有内容
#   - 退出码：0=无漂移；1=发现漂移
#

set -eu

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILLS_DIR="$PROJECT_ROOT/skills"
COMMANDS_DIR="$PROJECT_ROOT/.claude/commands"

# 颜色
RED='\033[0;31m'
YELLOW='\033[0;33m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

DRIFT_COUNT=0

echo "========================================"
echo " Skills / Commands 漂移校验"
echo "========================================"
echo " SSOT : $SKILLS_DIR"
echo " 镜像 : $COMMANDS_DIR"
echo ""

if [ ! -d "$SKILLS_DIR" ]; then
  echo -e "${RED}[FATAL]${NC} Skills SSOT 目录不存在: $SKILLS_DIR"
  exit 1
fi

if [ ! -d "$COMMANDS_DIR" ]; then
  echo -e "${RED}[FATAL]${NC} Claude commands 目录不存在: $COMMANDS_DIR"
  exit 1
fi

# 收集两侧的名称（去掉路径与扩展名）
# Skills: 每个子目录是一个 skill，目录名即名称
# Commands: 每个文件是一个 command，文件名（去 .md）即名称
SKILL_NAMES=$(ls -1 "$SKILLS_DIR" 2>/dev/null | grep -v '^TEMPLATE' | sort)
COMMAND_NAMES=$(ls -1 "$COMMANDS_DIR" 2>/dev/null | sed 's/\.md$//' | sort)

# 1. 检查 SSOT 有但镜像缺失的 skill
echo "--- 1) SSOT 有但 Claude commands 缺失 ---"
ONLY_SKILLS=$(comm -23 <(echo "$SKILL_NAMES") <(echo "$COMMAND_NAMES"))
if [ -n "$ONLY_SKILLS" ]; then
  echo -e "${YELLOW}[DRIFT]${NC} 以下 Skill 在 SSOT 中存在，但 .claude/commands/ 缺少镜像："
  echo "$ONLY_SKILLS" | sed 's/^/    - /'
  echo "  → 需从 skills/<name>/SKILL.md 提取正文（去 frontmatter）创建 .claude/commands/<name>.md"
  echo ""
  DRIFT_COUNT=$((DRIFT_COUNT + 1))
else
  echo -e "${GREEN}[OK]${NC} SSOT 中所有 Skill 均有 Claude command 镜像"
  echo ""
fi

# 2. 检查镜像有但 SSOT 缺失的 command（孤立镜像）
echo "--- 2) Claude commands 有但 SSOT 缺失（孤立镜像）---"
ONLY_COMMANDS=$(comm -13 <(echo "$SKILL_NAMES") <(echo "$COMMAND_NAMES"))
if [ -n "$ONLY_COMMANDS" ]; then
  echo -e "${YELLOW}[DRIFT]${NC} 以下 Claude command 在 SSOT 中无对应 Skill："
  echo "$ONLY_COMMANDS" | sed 's/^/    - /'
  echo "  → 需确认是否应迁移到 skills/ 作为 SSOT，或删除孤立镜像"
  echo ""
  DRIFT_COUNT=$((DRIFT_COUNT + 1))
else
  echo -e "${GREEN}[OK]${NC} 无孤立 Claude command"
  echo ""
fi

# 3. 检查内容一致性（行数差异 + 修改时间滞后）
echo "--- 3) 内容一致性（命名对应的 skill ↔ command）---"
COMMON=$(comm -12 <(echo "$SKILL_NAMES") <(echo "$COMMAND_NAMES"))
CONTENT_DRIFT=0
if [ -n "$COMMON" ]; then
  while IFS= read -r name; do
    skill_file="$SKILLS_DIR/$name/SKILL.md"
    command_file="$COMMANDS_DIR/$name.md"
    skill_lines=$(wc -l < "$skill_file" 2>/dev/null || echo 0)
    command_lines=$(wc -l < "$command_file" 2>/dev/null || echo 0)
    # 内容本就允许不同（Claude 无 frontmatter），这里只看行数差异 > 30% 的情况作为提示
    if [ "$skill_lines" -gt 0 ]; then
      diff_pct=$(( (skill_lines > command_lines ? skill_lines - command_lines : command_lines - skill_lines) * 100 / skill_lines ))
      if [ "$diff_pct" -gt 30 ]; then
        echo -e "${YELLOW}[HINT]${NC} $name: 行数差异较大 (skill=${skill_lines}, command=${command_lines}, diff=${diff_pct}%) — 建议人工核对"
        CONTENT_DRIFT=1
      fi
    fi
  done <<< "$COMMON"
  if [ "$CONTENT_DRIFT" -eq 0 ]; then
    echo -e "${GREEN}[OK]${NC} 命名对应的 skill ↔ command 行数差异均在 30% 以内"
  fi
  echo ""
fi

# 4. 统计
echo "========================================"
SKILL_TOTAL=$(echo "$SKILL_NAMES" | grep -c '.' || true)
COMMAND_TOTAL=$(echo "$COMMAND_NAMES" | grep -c '.' || true)
echo " SSOT Skills      : $SKILL_TOTAL"
echo " Claude Commands  : $COMMAND_TOTAL"
if [ "$DRIFT_COUNT" -eq 0 ] && [ "$CONTENT_DRIFT" -eq 0 ]; then
  echo -e " ${GREEN}结果：无漂移${NC}"
  exit 0
else
  echo -e " ${YELLOW}结果：发现漂移，请人工同步${NC}"
  echo " 提示：修改 SSOT（skills/）后，手动同步 .claude/commands/"
  exit 1
fi

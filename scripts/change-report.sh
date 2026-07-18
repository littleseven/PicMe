#!/bin/bash
#
# Change Report - PoLang 变更影响报告自动生成
# 用途: 统计代码变更范围、影响模块、测试覆盖、风险项，生成结构化报告
# 调用: ./scripts/change-report.sh [options]
#
# Options:
#   --since <commit>  从某次 commit 开始统计（默认最近1次）
#   --range <a>..<b>  统计指定 commit 范围
#   --output <file>   输出报告文件（默认输出到终端）
#   --json            JSON 格式输出
#
# 示例:
#   ./scripts/change-report.sh                          # 最近一次 commit 的报告
#   ./scripts/change-report.sh --since HEAD~3           # 最近3次 commit
#   ./scripts/change-report.sh --range HEAD~5..HEAD     # 指定范围
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

SINCE_COMMIT="HEAD~1"
RANGE=""
OUTPUT_FILE=""
JSON_MODE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --since) SINCE_COMMIT="$2"; shift 2 ;;
        --range) RANGE="$2"; shift 2 ;;
        --output) OUTPUT_FILE="$2"; shift 2 ;;
        --json) JSON_MODE=true; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 获取变更统计
get_change_stats() {
    local since="$1"
    local stats=""
    
    # 获取变更文件列表
    local files=$(git diff --name-only "$since" HEAD 2>/dev/null || true)
    
    # 统计
    local total_files=$(echo "$files" | grep -v '^$' | wc -l | tr -d ' ')
    local added_lines=$(git diff --stat "$since" HEAD 2>/dev/null | tail -1 | grep -oE '[0-9]+ insertion' | grep -oE '[0-9]+' | head -1); added_lines=${added_lines:-0}
    local deleted_lines=$(git diff --stat "$since" HEAD 2>/dev/null | tail -1 | grep -oE '[0-9]+ deletion' | grep -oE '[0-9]+' | head -1); deleted_lines=${deleted_lines:-0}
    local modified_files=$(echo "$files" | grep -v '^$' | wc -l | tr -d ' ')
    
    # 按模块统计（grep -c 无匹配时退出码1，不用 || 避免双重输出）
    local camera_files=$(echo "$files" | grep -c "features/camera/" 2>/dev/null); camera_files=${camera_files:-0}
    local gallery_files=$(echo "$files" | grep -c "features/gallery/" 2>/dev/null); gallery_files=${gallery_files:-0}
    local editor_files=$(echo "$files" | grep -c "features/editor/" 2>/dev/null); editor_files=${editor_files:-0}
    local beauty_files=$(echo "$files" | grep -c "beauty-engine/" 2>/dev/null); beauty_files=${beauty_files:-0}
    local data_files=$(echo "$files" | grep -c "data/" 2>/dev/null); data_files=${data_files:-0}
    local test_files=$(echo "$files" | grep -cE "Test\.kt$|test/|androidTest/" 2>/dev/null); test_files=${test_files:-0}
    local doc_files=$(echo "$files" | grep -cE "\.md$" 2>/dev/null); doc_files=${doc_files:-0}
    local script_files=$(echo "$files" | grep -cE "scripts/|\.sh$|\.py$" 2>/dev/null); script_files=${script_files:-0}
    
    # 按类型统计
    local kt_files=$(echo "$files" | grep -c "\.kt$" 2>/dev/null); kt_files=${kt_files:-0}
    local xml_files=$(echo "$files" | grep -c "\.xml$" 2>/dev/null); xml_files=${xml_files:-0}
    local gradle_files=$(echo "$files" | grep -c "\.gradle" 2>/dev/null); gradle_files=${gradle_files:-0}
    
    # 提取 commit 信息
    local commit_count=$(git rev-list --count "$since"..HEAD 2>/dev/null | head -1 || echo 0)
    commit_count=${commit_count:-0}
    local commit_messages=$(git log --oneline "$since"..HEAD 2>/dev/null || true)
    
    echo "${total_files}|${added_lines}|${deleted_lines}|${modified_files}|${camera_files}|${gallery_files}|${editor_files}|${beauty_files}|${data_files}|${test_files}|${doc_files}|${script_files}|${kt_files}|${xml_files}|${gradle_files}|${commit_count}"
}

# 风险分析
analyze_risks() {
    local since="$1"
    local risks=""
    
    # 检查跨模块修改
    local module_count=$(git diff --name-only "$since" HEAD 2>/dev/null | grep -E "^app/|^beauty-engine/" | sed 's|/src/.*||' | sort -u | wc -l | tr -d ' ')
    if [ "$module_count" -gt 2 ]; then
        risks="${risks}- 跨模块影响: 涉及 $module_count 个模块，需仔细验证集成\n"
    fi
    
    # 检查公共 API 变更
    local api_changes=$(git diff "$since" HEAD -- beauty-engine/src/main/java/com/picme/beauty/api/ 2>/dev/null | grep -E "^[-+].*(fun |val |var |class )" | head -10 || true)
    if [ -n "$api_changes" ]; then
        risks="${risks}- API 变更: beauty-engine 公开 API 发生变更\n"
    fi
    
    # 检查资源变更
    local resource_changes=$(git diff --name-only "$since" HEAD 2>/dev/null | grep -c "res/"); resource_changes=${resource_changes:-0}
    if [ "$resource_changes" -gt 0 ]; then
        risks="${risks}- 资源变更: 涉及 $resource_changes 个资源文件\n"
    fi
    
    # 检查依赖变更
    local dep_changes=$(git diff --name-only "$since" HEAD 2>/dev/null | grep -cE "build\.gradle|libs\.versions\.toml"); dep_changes=${dep_changes:-0}
    if [ "$dep_changes" -gt 0 ]; then
        risks="${risks}- 依赖变更: Gradle 配置发生变更\n"
    fi
    
    # 检查测试覆盖
    local code_changes=$(git diff --name-only "$since" HEAD 2>/dev/null | grep -cE "\.kt$|\.java$"); code_changes=${code_changes:-0}
    local test_changes=$(git diff --name-only "$since" HEAD 2>/dev/null | grep -cE "Test\.kt$"); test_changes=${test_changes:-0}
    if [ "$code_changes" -gt 0 ] && [ "$test_changes" -eq 0 ]; then
        risks="${risks}- 测试缺口: 代码变更未伴随测试更新\n"
    fi
    
    echo -e "$risks"
}

# 生成 Markdown 报告
generate_md_report() {
    local since="$1"
    local stats_raw=$(get_change_stats "$since")
    
    IFS='|' read -r total_files added_lines deleted_lines modified_files camera_files gallery_files editor_files beauty_files data_files test_files doc_files script_files kt_files xml_files gradle_files commit_count <<< "$stats_raw"
    
    local risks=$(analyze_risks "$since")
    
    {
        echo "# PoLang Change Impact Report"
        echo ""
        echo "**时间范围**: $since .. HEAD"
        echo "**生成时间**: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "**Commit 数量**: $commit_count"
        echo ""
        
        echo "## 📊 变更统计"
        echo ""
        echo "| 指标 | 数值 |"
        echo "|------|------|"
        echo "| 变更文件 | $total_files |"
        echo "| 新增行数 | +$added_lines |"
        echo "| 删除行数 | -$deleted_lines |"
        echo "| Kotlin 文件 | $kt_files |"
        echo "| XML 文件 | $xml_files |"
        echo "| Gradle 文件 | $gradle_files |"
        echo "| 测试文件 | $test_files |"
        echo "| 文档文件 | $doc_files |"
        echo ""
        
        echo "## 🏗️ 模块影响"
        echo ""
        echo "| 模块 | 变更文件数 |"
        echo "|------|-----------|"
        [ "$camera_files" -gt 0 ] && echo "| 相机 (camera) | $camera_files |"
        [ "$gallery_files" -gt 0 ] && echo "| 相册 (gallery) | $gallery_files |"
        [ "$editor_files" -gt 0 ] && echo "| 编辑 (editor) | $editor_files |"
        [ "$beauty_files" -gt 0 ] && echo "| 美颜引擎 (beauty-engine) | $beauty_files |"
        [ "$data_files" -gt 0 ] && echo "| 数据层 (data) | $data_files |"
        [ "$script_files" -gt 0 ] && echo "| 脚本工具 (scripts) | $script_files |"
        echo ""
        
        if [ -n "$risks" ]; then
            echo "## ⚠️ 风险项"
            echo ""
            echo "$risks"
            echo ""
        else
            echo "## ✅ 风险项"
            echo ""
            echo "未检测到明显风险"
            echo ""
        fi
        
        echo "## 📝 Commit 记录"
        echo ""
        echo "\`\`\`"
        git log --oneline "$since"..HEAD 2>/dev/null || true
        echo "\`\`\`"
        echo ""
        
        echo "## 🧪 验证建议"
        echo ""
        echo "\`\`\`bash"
        echo "# 快速编译检查"
        echo "./scripts/quick-compile.sh --all"
        echo ""
        echo "# 代码质量检查"
        echo "./scripts/ai-gate.sh"
        echo ""
        echo "# 设备端验证"
        echo "./scripts/auto-dev-loop.sh --quick"
        echo ""
        if [ "${test_files:-0}" != "0" ] && [ "$test_files" -gt 0 ]; then
            echo "# 运行新增测试"
            echo "./gradlew testDebugUnitTest"
        fi
        echo "\`\`\`"
        echo ""
    }
}

# 生成 JSON 报告
generate_json_report() {
    local since="$1"
    local stats_raw=$(get_change_stats "$since")
    
    IFS='|' read -r total_files added_lines deleted_lines modified_files camera_files gallery_files editor_files beauty_files data_files test_files doc_files script_files kt_files xml_files gradle_files commit_count commit_messages <<< "$stats_raw"
    
    python3 << EOF
import json

report = {
    "since": "$since",
    "timestamp": "$(date -Iseconds)",
    "commit_count": $commit_count,
    "stats": {
        "total_files": $total_files,
        "added_lines": $added_lines,
        "deleted_lines": $deleted_lines,
        "kotlin_files": $kt_files,
        "xml_files": $xml_files,
        "gradle_files": $gradle_files,
        "test_files": $test_files,
        "doc_files": $doc_files,
    },
    "modules": {
        "camera": $camera_files,
        "gallery": $gallery_files,
        "editor": $editor_files,
        "beauty_engine": $beauty_files,
        "data": $data_files,
        "scripts": $script_files,
    },
    "commits": """$commit_messages""".strip().split("\n"),
}

print(json.dumps(report, indent=2))
EOF
}

# 主流程
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  📊 Change Report - 变更影响报告                          ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# 确定统计范围
if [ -n "$RANGE" ]; then
    SINCE_COMMIT=$(echo "$RANGE" | cut -d. -f1)
fi

# 检查范围是否有效
if ! git rev-parse "$SINCE_COMMIT" > /dev/null 2>&1; then
    echo -e "${RED}❌ 无效的 commit 范围: $SINCE_COMMIT${NC}"
    exit 1
fi

if $JSON_MODE; then
    if [ -n "$OUTPUT_FILE" ]; then
        generate_json_report "$SINCE_COMMIT" > "$OUTPUT_FILE"
        echo -e "${GREEN}✅ JSON 报告已保存: $OUTPUT_FILE${NC}"
    else
        generate_json_report "$SINCE_COMMIT"
    fi
else
    if [ -n "$OUTPUT_FILE" ]; then
        generate_md_report "$SINCE_COMMIT" > "$OUTPUT_FILE"
        echo -e "${GREEN}✅ Markdown 报告已保存: $OUTPUT_FILE${NC}"
    else
        generate_md_report "$SINCE_COMMIT"
    fi
fi

echo ""
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

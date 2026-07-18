#!/bin/bash
#
# Impact Analyzer - PoLang 代码变更影响分析脚本
# 用途: 分析代码变更的影响范围，输出模块依赖、API兼容性风险、需更新文档、测试缺口
# 调用: ./scripts/impact-analyzer.sh [options] [files...]
#
# Options:
#   --git-diff      分析当前 git diff（默认）
#   --last-commit   分析最后一次 commit 的变更
#   --file <path>   分析指定文件
#   --output <fmt>  输出格式: text|json|markdown (默认: text)
#
# 示例:
#   ./scripts/impact-analyzer.sh                    # 分析当前工作区的变更
#   ./scripts/impact-analyzer.sh --last-commit      # 分析上次提交
#   ./scripts/impact-analyzer.sh --file app/src/.../CameraViewModel.kt
#   ./scripts/impact-analyzer.sh --output markdown  # Markdown 格式输出
#

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

MODE="git-diff"
TARGET_FILES=""
OUTPUT_FORMAT="text"

while [[ $# -gt 0 ]]; do
    case $1 in
        --git-diff) MODE="git-diff"; shift ;;
        --last-commit) MODE="last-commit"; shift ;;
        --file) MODE="file"; TARGET_FILES="$2"; shift 2 ;;
        --output) OUTPUT_FORMAT="$2"; shift 2 ;;
        *)
            if [ -z "$TARGET_FILES" ]; then
                TARGET_FILES="$1"
            else
                TARGET_FILES="$TARGET_FILES $1"
            fi
            shift
            ;;
    esac
done

# 获取变更文件列表
get_changed_files() {
    case $MODE in
        git-diff)
            # 包含已暂存和未暂存的变更
            (git diff --name-only HEAD 2>/dev/null; git diff --name-only --staged 2>/dev/null) | sort -u | grep -v '^$' || true
            ;;
        last-commit)
            git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || true
            ;;
        file)
            echo "$TARGET_FILES" | tr ' ' '\n'
            ;;
    esac
}

# 分析文件属于哪个模块
get_module_for_file() {
    local file="$1"
    case "$file" in
        app/*) echo ":app" ;;
        beauty-engine/*) echo ":beauty-engine" ;;
        buildSrc/*) echo "buildSrc" ;;
        gradle/*|build.gradle*|settings.gradle*) echo "build-system" ;;
        docs/*) echo "docs" ;;
        scripts/*) echo "scripts" ;;
        .kimi/*|.lingma/*|.openclaw/*) echo "ai-tools" ;;
        AGENTS.md|PRODUCT.md|FEATURES.md|README.md) echo "project-docs" ;;
        *) echo "unknown" ;;
    esac
}

# 分析文件类型和潜在影响
analyze_file_impact() {
    local file="$1"
    local impacts=""
    
    # API/接口文件
    if echo "$file" | grep -qE "Api\.kt$|Service\.kt$|Repository\.kt$|DataSource\.kt$|Contract\.kt$|Interface"; then
        impacts="${impacts}${impacts:+, }api-change"
    fi
    
    # 数据模型
    if echo "$file" | grep -qE "Model\.kt$|Entity\.kt$|Data\.kt$|State\.kt$|Event\.kt$|UiState"; then
        impacts="${impacts}${impacts:+, }data-model"
    fi
    
    # UI 层
    if echo "$file" | grep -qE "Screen\.kt$|View\.kt$|Composable|Activity\.kt$|Fragment\.kt$|Pager"; then
        impacts="${impacts}${impacts:+, }ui-change"
    fi
    
    # ViewModel
    if echo "$file" | grep -qE "ViewModel\.kt$"; then
        impacts="${impacts}${impacts:+, }viewmodel-change"
    fi
    
    # 资源文件
    if echo "$file" | grep -qE "res/|assets/|values/|strings\.xml|colors\.xml"; then
        impacts="${impacts}${impacts:+, }resource-change"
    fi
    
    # Shader/GLSL
    if echo "$file" | grep -qiE "\.glsl$|\.frag$|\.vert$|shader"; then
        impacts="${impacts}${impacts:+, }shader-change"
    fi
    
    # 测试文件
    if echo "$file" | grep -qE "Test\.kt$|test/|androidTest/"; then
        impacts="${impacts}${impacts:+, }test-change"
    fi
    
    # 依赖配置
    if echo "$file" | grep -qE "build\.gradle|libs\.versions\.toml"; then
        impacts="${impacts}${impacts:+, }dependency-change"
    fi
    
    # 文档
    if echo "$file" | grep -qE "\.md$|\.txt$"; then
        impacts="${impacts}${impacts:+, }doc-change"
    fi
    
    echo "${impacts:-general}"
}

# 根据模块映射需要检查的文档
doc_mapping() {
    local module="$1"
    local impact="$2"
    local docs=""
    
    case "$module" in
        :app)
            docs="PRODUCT.md FEATURES.md"
            case "$impact" in
                *ui-change*|*viewmodel-change*) docs="$docs app AGENTS.md FEATURES.md §2" ;;
                *api-change*) docs="$docs app AGENTS.md AGENTS.md" ;;
                *resource-change*) docs="$docs FEATURES.md (I18N check)" ;;
            esac
            ;;
        :beauty-engine)
            docs="PRODUCT.md docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md"
            case "$impact" in
                *shader-change*) docs="$docs docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md (Shader section)" ;;
                *api-change*) docs="$docs beauty-engine/AGENTS.md AGENTS.md" ;;
            esac
            ;;
        build-system|buildSrc)
            docs="DEVELOPMENT.md"
            ;;
        docs|project-docs)
            docs="AGENTS.md (文档治理规则)"
            ;;
        ai-tools)
            docs="AI_TOOLS.md"
            ;;
    esac
    
    echo "$docs"
}

# 根据影响类型检查全局红线
redline_check() {
    local impact="$1"
    local file="$2"
    local redlines=""
    
    # PRIVACY 检查
    if echo "$file" | grep -qiE "face|detect|ocr|recogni|classif|upload|network|cloud|api.*key"; then
        redlines="${redlines}${redlines:+, }[PRIVACY]"
    fi
    
    # PERF 检查
    if echo "$impact" | grep -qE "shader|gpu|render|loop|algorithm|process"; then
        redlines="${redlines}${redlines:+, }[PERF]"
    fi
    
    # I18N 检查
    if echo "$impact" | grep -q "resource-change" || echo "$file" | grep -q "strings\.xml"; then
        redlines="${redlines}${redlines:+, }[I18N]"
    fi
    
    echo "$redlines"
}

# 生成文本格式报告
generate_text_report() {
    local files="$1"
    
    echo ""
    echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║           📊 PoLang Impact Analyzer - 影响分析报告        ║${NC}"
    echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${BLUE}分析模式:${NC} $MODE"
    echo -e "${BLUE}变更文件数:${NC} $(echo "$files" | grep -c '^' || echo 0)"
    echo ""
    
    # 模块统计
    echo -e "${CYAN}━━ 影响模块 ━━${NC}"
    local modules=$(echo "$files" | while read -r f; do get_module_for_file "$f"; done | sort | uniq -c | sort -rn)
    echo "$modules"
    echo ""
    
    # 文件级分析
    echo -e "${CYAN}━━ 文件影响分析 ━━${NC}"
    echo ""
    
    local cross_module=false
    local module_count=$(echo "$modules" | wc -l | tr -d ' ')
    if [ "$module_count" -gt 1 ]; then
        cross_module=true
    fi
    
    local all_redlines=""
    local all_docs=""
    
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        
        local module=$(get_module_for_file "$file")
        local impact=$(analyze_file_impact "$file")
        local redlines=$(redline_check "$impact" "$file")
        local docs=$(doc_mapping "$module" "$impact")
        
        echo -e "  ${GREEN}$(basename "$file")${NC} (${module})"
        echo -e "     影响类型: ${YELLOW}$impact${NC}"
        [ -n "$redlines" ] && echo -e "     红线检查: ${RED}$redlines${NC}"
        [ -n "$docs" ] && echo -e "     相关文档: ${BLUE}$docs${NC}"
        echo ""
        
        all_redlines="$all_redlines $redlines"
        all_docs="$all_docs $docs"
    done <<< "$files"
    
    # 汇总
    echo -e "${CYAN}━━ 汇总建议 ━━${NC}"
    echo ""
    
    if [ "$cross_module" = true ]; then
        echo -e "  ${YELLOW}⚠️ 跨模块影响 detected${NC}"
        echo -e "     建议: 使用保守执行模式，人工确认架构影响"
        echo ""
    fi
    
    local unique_redlines=$(echo "$all_redlines" | tr ',' '\n' | sed 's/^ *//' | sort -u | grep -v '^$' | tr '\n' ',' | sed 's/,$//')
    if [ -n "$unique_redlines" ]; then
        echo -e "  ${RED}🚨 涉及全局红线: $unique_redlines${NC}"
        echo -e "     请确认是否满足对应规范要求"
        echo ""
    fi
    
    echo -e "  ${BLUE}📋 需同步的文档:${NC}"
    echo "$all_docs" | tr ' ' '\n' | sed 's/^ *//' | sort -u | grep -v '^$' | while read -r doc; do
        echo -e "     - $doc"
    done
    echo ""
    
    # 测试建议
    echo -e "  ${BLUE}🧪 测试建议:${NC}"
    echo -e "     - ./scripts/quick-compile.sh --all"
    if echo "$files" | grep -qE "Test\.kt$"; then
        echo -e "     - ./gradlew testDebugUnitTest"
    fi
    if [ "$cross_module" = true ] || echo "$files" | grep -qE "beauty-engine/|camera/|gallery/"; then
        echo -e "     - ./scripts/regression-test.sh"
    fi
    echo ""
}

# 生成 Markdown 格式报告
generate_md_report() {
    local files="$1"
    
    cat << EOF
# PoLang Impact Analysis Report

**分析模式**: $MODE  
**变更文件数**: $(echo "$files" | grep -c '^' || echo 0)  
**生成时间**: $(date '+%Y-%m-%d %H:%M:%S')

## 影响模块

EOF

    echo "$files" | while read -r f; do get_module_for_file "$f"; done | sort | uniq -c | sort -rn | while read -r count module; do
        echo "- **$module**: $count 个文件"
    done

    echo ""
    echo "## 文件级分析"
    echo ""

    while IFS= read -r file; do
        [ -z "$file" ] && continue
        local module=$(get_module_for_file "$file")
        local impact=$(analyze_file_impact "$file")
        local redlines=$(redline_check "$impact" "$file")
        local docs=$(doc_mapping "$module" "$impact")
        
        echo "### $(basename "$file")"
        echo "- **路径**: \`$file\`"
        echo "- **模块**: $module"
        echo "- **影响类型**: $impact"
        [ -n "$redlines" ] && echo "- **红线检查**: $redlines"
        [ -n "$docs" ] && echo "- **相关文档**: $docs"
        echo ""
    done <<< "$files"

    cat << EOF
## 验证建议

\`\`\`bash
# 1. 快速编译验证
./scripts/quick-compile.sh --all

# 2. 代码质量检查
./scripts/ai-gate.sh

# 3. 设备端验证（如需要）
./scripts/auto-dev-loop.sh --quick
\`\`\`
EOF
}

# 生成 JSON 格式报告
generate_json_report() {
    local files="$1"
    
    echo "{"
    echo "  \"mode\": \"$MODE\","
    echo "  \"timestamp\": \"$(date -Iseconds)\","
    echo "  \"files\": ["
    
    local first=true
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        local module=$(get_module_for_file "$file")
        local impact=$(analyze_file_impact "$file")
        local redlines=$(redline_check "$impact" "$file")
        
        if [ "$first" = true ]; then
            first=false
        else
            echo ","
        fi
        
        echo -n "    {"
        echo -n "\"path\": \"$file\", "
        echo -n "\"module\": \"$module\", "
        echo -n "\"impact\": \"$impact\", "
        echo -n "\"redlines\": \"$redlines\""
        echo -n "}"
    done <<< "$files"
    
    echo ""
    echo "  ]"
    echo "}"
}

# 主流程
CHANGED_FILES=$(get_changed_files)

if [ -z "$CHANGED_FILES" ]; then
    echo "未检测到变更文件"
    exit 0
fi

case "$OUTPUT_FORMAT" in
    text)
        generate_text_report "$CHANGED_FILES"
        ;;
    markdown|md)
        generate_md_report "$CHANGED_FILES"
        ;;
    json)
        generate_json_report "$CHANGED_FILES"
        ;;
    *)
        echo "未知输出格式: $OUTPUT_FORMAT"
        exit 1
        ;;
esac

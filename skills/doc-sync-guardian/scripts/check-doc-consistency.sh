#!/bin/bash
# 文档一致性快速检查脚本
# 用法: ./check-doc-consistency.sh [--module ModuleName]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"

echo "🔍 开始文档一致性检查..."
echo "📁 项目根目录: $PROJECT_ROOT"
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 统计变量
PASS_COUNT=0
WARN_COUNT=0
ERROR_COUNT=0

# 结果数组
declare -a PASS_ITEMS=()
declare -a WARN_ITEMS=()
declare -a ERROR_ITEMS=()

###############################################################################
# 检查 1: PRODUCT.md -> FEATURES.md 引用链
###############################################################################
echo -e "${YELLOW}[1/5] 检查 PRODUCT.md -> FEATURES.md 引用链...${NC}"

# 提取 PRODUCT.md 中的关键指标
PRODUCT_PERF_METRICS=$(grep -E "\[PERF\].*< \d+ms|\[PERF\].*>\s\d+%" "$PROJECT_ROOT/PRODUCT.md" || true)
PRODUCT_PRIVACY_REQS=$(grep -E "\[PRIVACY\]" "$PROJECT_ROOT/PRODUCT.md" || true)
PRODUCT_I18N_REQS=$(grep -E "\[I18N\]" "$PROJECT_ROOT/PRODUCT.md" || true)

if [ -z "$PRODUCT_PERF_METRICS" ]; then
    WARN_ITEMS+=("PRODUCT.md 未找到明确的性能指标")
    ((WARN_COUNT++))
else
    PASS_ITEMS+=("PRODUCT.md 包含性能指标定义")
    ((PASS_COUNT++))
fi

# 检查 FEATURES.md 是否承接了交互要求
if grep -q "交互\|反馈\|动效" "$PROJECT_ROOT/docs/01-PRODUCT/FEATURES.md"; then
    PASS_ITEMS+=("FEATURES.md 包含交互流程说明")
    ((PASS_COUNT++))
else
    ERROR_ITEMS+=("FEATURES.md 缺少交互流程描述")
    ((ERROR_COUNT++))
fi

###############################################################################
# 检查 2: 模块 AGENTS.md 第 5 章完整性
###############################################################################
echo -e "${YELLOW}[2/5] 检查模块 AGENTS.md Product Alignment 章节...${NC}"

MODULES_WITHOUT_CHAPTER5=()

while IFS= read -r agents_file; do
    if [ -f "$agents_file" ]; then
        if ! grep -q "## 5. 与产品文档对照" "$agents_file"; then
            MODULES_WITHOUT_CHAPTER5+=("$agents_file")
        fi
    fi
done < <(find "$PROJECT_ROOT/app/src" -name "AGENTS.md" 2>/dev/null || true)

if [ ${#MODULES_WITHOUT_CHAPTER5[@]} -eq 0 ]; then
    PASS_ITEMS+=("所有模块 AGENTS.md 均包含第 5 章")
    ((PASS_COUNT++))
else
    for module in "${MODULES_WITHOUT_CHAPTER5[@]}"; do
        WARN_ITEMS+=("缺少第 5 章: $module")
        ((WARN_COUNT++))
    done
fi

###############################################################################
# 检查 3: I18N 三语资源同步
###############################################################################
echo -e "${YELLOW}[3/5] 检查国际化资源同步...${NC}"

VALUES_DIRS=("values" "values-zh-rCN" "values-zh-rTW")
MISSING_STRINGS=()

for dir in "${VALUES_DIRS[@]}"; do
    strings_file="$PROJECT_ROOT/app/src/main/res/$dir/strings.xml"
    if [ ! -f "$strings_file" ]; then
        MISSING_STRINGS+=("$dir/strings.xml")
    fi
done

if [ ${#MISSING_STRINGS[@]} -eq 0 ]; then
    PASS_ITEMS+=("三语资源文件齐全 (values, values-zh-rCN, values-zh-rTW)")
    ((PASS_COUNT++))
else
    for missing in "${MISSING_STRINGS[@]}"; do
        ERROR_ITEMS+=("缺少国际化资源: $missing")
        ((ERROR_COUNT++))
    done
fi

###############################################################################
# 检查 4: 技术专项文档引用完整性
###############################################################################
echo -e "${YELLOW}[4/5] 检查技术专项文档引用...${NC}"

# 查找所有 markdown 文件中的链接
BROKEN_LINKS=()

while IFS= read -r md_file; do
    # 提取 markdown 链接
    links=$(grep -oP '\[([^\]]+)\]\(([^)]+)\)' "$md_file" 2>/dev/null || true)
    
    while IFS= read -r link; do
        if [[ -n "$link" ]]; then
            # 提取文件路径
            file_path=$(echo "$link" | grep -oP '\([^)]+\)' | tr -d '()')
            
            # 跳过外部链接和锚点
            if [[ "$file_path" == http* ]] || [[ "$file_path" == "#"* ]]; then
                continue
            fi
            
            # 解析相对路径
            if [[ "$file_path" != /* ]]; then
                file_dir=$(dirname "$md_file")
                full_path="$file_dir/$file_path"
            else
                full_path="$PROJECT_ROOT$file_path"
            fi
            
            # 检查文件是否存在
            if [ ! -f "$full_path" ]; then
                BROKEN_LINKS+=("$md_file -> $file_path")
            fi
        fi
    done <<< "$links"
done < <(find "$PROJECT_ROOT" -name "*.md" -not -path "*/build/*" -not -path "*/.git/*" 2>/dev/null | head -20)

if [ ${#BROKEN_LINKS[@]} -eq 0 ]; then
    PASS_ITEMS+=("未发现悬空文档引用")
    ((PASS_COUNT++))
else
    for link in "${BROKEN_LINKS[@]:0:5}"; do  # 只显示前 5 个
        WARN_ITEMS+=("悬空引用: $link")
        ((WARN_COUNT++))
    done
fi

###############################################################################
# 检查 5: 顶层 AGENTS.md 内容边界
###############################################################################
echo -e "${YELLOW}[5/5] 检查顶层 AGENTS.md 内容边界...${NC}"

TOP_AGENTS_LINES=$(wc -l < "$PROJECT_ROOT/AGENTS.md")

if [ "$TOP_AGENTS_LINES" -gt 500 ]; then
    WARN_ITEMS+=("顶层 AGENTS.md 过长 ($TOP_AGENTS_LINES 行)，建议瘦身")
    ((WARN_COUNT++))
else
    PASS_ITEMS+=("顶层 AGENTS.md 长度合理 ($TOP_AGENTS_LINES 行)")
    ((PASS_COUNT++))
fi

# 检查是否包含代码示例（应该下沉到模块文档）
if grep -q '```kotlin\|```java' "$PROJECT_ROOT/AGENTS.md"; then
    WARN_ITEMS+=("顶层 AGENTS.md 包含代码示例，应移至模块文档")
    ((WARN_COUNT++))
else
    PASS_ITEMS+=("顶层 AGENTS.md 未包含实现细节")
    ((PASS_COUNT++))
fi

###############################################################################
# 生成审计报告
###############################################################################
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "📊 文档一致性审计报告"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "审计时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "审计范围: 全量三层文档"
echo ""

# 通过项
if [ ${#PASS_ITEMS[@]} -gt 0 ]; then
    echo -e "${GREEN}✅ 通过项 (${#PASS_ITEMS[@]})${NC}"
    for item in "${PASS_ITEMS[@]}"; do
        echo "   • $item"
    done
    echo ""
fi

# 警告项
if [ ${#WARN_ITEMS[@]} -gt 0 ]; then
    echo -e "${YELLOW}⚠️  警告项 (${#WARN_ITEMS[@]})${NC}"
    for item in "${WARN_ITEMS[@]}"; do
        echo "   • $item"
    done
    echo ""
fi

# 错误项
if [ ${#ERROR_ITEMS[@]} -gt 0 ]; then
    echo -e "${RED}❌ 错误项 (${#ERROR_ITEMS[@]})${NC}"
    for item in "${ERROR_ITEMS[@]}"; do
        echo "   • $item"
    done
    echo ""
fi

# 总结
echo "═══════════════════════════════════════════════════════════"
echo "总计: ✅ $PASS_COUNT 通过 | ⚠️  $WARN_COUNT 警告 | ❌ $ERROR_COUNT 错误"
echo "═══════════════════════════════════════════════════════════"

# 保存报告
REPORT_FILE="$PROJECT_ROOT/docs/05-DEVELOPMENT/audit_report_$(date +%Y%m%d_%H%M%S).md"

cat > "$REPORT_FILE" << EOF
# 📊 文档一致性审计报告

**审计时间**: $(date '+%Y-%m-%d %H:%M:%S')  
**审计范围**: 全量三层文档  

## ✅ 通过项 ($PASS_COUNT)
$(printf '%s\n' "${PASS_ITEMS[@]}" | sed 's/^/- /')

## ⚠️ 警告项 ($WARN_COUNT)
$(printf '%s\n' "${WARN_ITEMS[@]}" | sed 's/^/- /')

## ❌ 错误项 ($ERROR_COUNT)
$(printf '%s\n' "${ERROR_ITEMS[@]}" | sed 's/^/- /')

## 📝 修复建议
EOF

if [ ${#WARN_ITEMS[@]} -gt 0 ] || [ ${#ERROR_ITEMS[@]} -gt 0 ]; then
    echo "" >> "$REPORT_FILE"
    echo "请根据上述警告和错误项进行修复。" >> "$REPORT_FILE"
else
    echo "" >> "$REPORT_FILE"
    echo "所有检查项均通过，文档一致性良好！" >> "$REPORT_FILE"
fi

echo ""
echo -e "${GREEN}📄 详细报告已保存到: $REPORT_FILE${NC}"

# 退出码
if [ $ERROR_COUNT -gt 0 ]; then
    exit 1
else
    exit 0
fi

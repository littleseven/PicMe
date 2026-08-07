#!/bin/bash
# 检测代码中未标注坐标系的左右描述
# 用法: ./scripts/check-coordinate-annotation.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "🔍 检查未标注坐标系的注释..."
echo ""

# 搜索常见的模糊描述
FUZZY_PATTERNS=(
    "左眼"
    "右眼"
    "左眉"
    "右眉"
    "左侧脸"
    "右侧脸"
)

ERRORS=0

for pattern in "${FUZZY_PATTERNS[@]}"; do
    echo "搜索: $pattern"
    RESULTS=$(grep -rn "$pattern" "$PROJECT_ROOT/androidApp/src/" --include="*.kt" 2>/dev/null | \
        grep -v "\[图像坐标系\]" | \
        grep -v "\[人脸坐标系\]" | \
        grep -v "imageLeft" | \
        grep -v "imageRight" | \
        grep -v "userLeft" | \
        grep -v "userRight" || true)
    
    if [ ! -z "$RESULTS" ]; then
        echo -e "\033[31m❌ 发现未标注坐标系的注释:\033[0m"
        echo "$RESULTS"
        ERRORS=$((ERRORS + 1))
    fi
done

echo ""
if [ $ERRORS -gt 0 ]; then
    echo -e "\033[31m❌ 发现 $ERRORS 处问题，请参考 docs/07-STANDARDS/COORDINATE_SYSTEM.md 修复\033[0m"
    exit 1
else
    echo -e "\033[32m✅ 坐标系标注检查通过\033[0m"
    exit 0
fi

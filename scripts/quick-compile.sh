#!/bin/bash
#
# Quick Compile - PoLang 分层快速编译脚本
# 用途: 编码过程中快速验证编译状态，分层递进，失败即停，最大化修复效率
# 调用: ./scripts/quick-compile.sh [options] [module]
#
# Options:
#   --all           全量编译（ktlint + compile + assemble），用于最终验证
#   --lint-only     仅代码格式检查（最快，~2s）
#   --compile-only  编译到 class 文件（不打包 APK，~5-30s）
#   --incremental   仅编译有变更的模块（默认）
#   --clean         先执行 clean（清除缓存，冷编译）
#   --watch         监听文件变更自动编译（开发模式）
#
# 示例:
#   ./scripts/quick-compile.sh                    # 默认：增量编译修改的模块
#   ./scripts/quick-compile.sh --lint-only        # 快速格式检查
#   ./scripts/quick-compile.sh :engines:beauty-engine     # 仅编译 beauty-engine 模块
#   ./scripts/quick-compile.sh --all              # 完整编译验证
#   ./scripts/quick-compile.sh --watch            # 监听模式
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

# 默认配置
MODE="incremental"
TARGET_MODULE=""
CLEAN=false
WATCH=false
GRADLE_OPTS="--quiet"

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --all) MODE="all"; shift ;;
        --lint-only) MODE="lint"; shift ;;
        --compile-only) MODE="compile"; shift ;;
        --incremental) MODE="incremental"; shift ;;
        --clean) CLEAN=true; shift ;;
        --watch) WATCH=true; shift ;;
        :*) TARGET_MODULE="$1"; shift ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 计时辅助（兼容 macOS）
timer_start() { python3 -c "import time; print(int(time.time()*1000))"; }
timer_end() {
    local start=$1
    local end=$(python3 -c "import time; print(int(time.time()*1000))")
    local elapsed=$((end - start))
    if [ $elapsed -lt 1000 ]; then
        echo "${elapsed}ms"
    else
        echo "$((elapsed / 1000)).$((elapsed % 1000 / 100))s"
    fi
}

print_header() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_ok() { echo -e "${GREEN}✅${NC} $1"; }
print_fail() { echo -e "${RED}❌${NC} $1"; }
print_info() { echo -e "${BLUE}ℹ️${NC} $1"; }
print_warn() { echo -e "${YELLOW}⚠️${NC} $1"; }

# 检测修改的模块
detect_changed_modules() {
    local changed_files=$(git diff --name-only HEAD 2>/dev/null || echo "")
    if [ -z "$changed_files" ]; then
        # 没有 git diff，尝试检查未暂存变更
        changed_files=$(git diff --name-only 2>/dev/null || echo "")
    fi
    
    local modules=""
    if echo "$changed_files" | grep -q "^androidApp/"; then
        modules=":androidApp"
    fi
    if echo "$changed_files" | grep -q "^engines/beauty-engine/"; then
        modules="${modules}${modules:+, }:engines:beauty-engine"
    fi
    if echo "$changed_files" | grep -q "^buildSrc/"; then
        # buildSrc 变更影响所有模块
        modules=":androidApp :engines:beauty-engine"
    fi
    
    echo "${modules:-:androidApp}"
}

# 分层编译主流程
run_compile() {
    local start_all=$(timer_start)
    local total_stages=0
    local passed_stages=0
    
    # Optional: clean
    if [ "$CLEAN" = true ]; then
        print_info "执行 gradle clean..."
        ./gradlew clean $GRADLE_OPTS
    fi
    
    # Stage 1: ktlint（最快，~2s）
    if [ "$MODE" = "lint" ] || [ "$MODE" = "incremental" ] || [ "$MODE" = "all" ]; then
        print_header "Stage 1/4: Ktlint 格式检查 (~2s)"
        local start=$(timer_start)
        if ./gradlew ${TARGET_MODULE:-}ktlintCheck $GRADLE_OPTS; then
            print_ok "Ktlint 通过 (${TARGET_MODULE:-all})"
            passed_stages=$((passed_stages + 1))
        else
            print_fail "Ktlint 失败"
            echo ""
            print_info "尝试自动修复: ./gradlew ${TARGET_MODULE:-}ktlintFormat"
            ./gradlew ${TARGET_MODULE:-}ktlintFormat $GRADLE_OPTS || true
            # 再次检查
            if ./gradlew ${TARGET_MODULE:-}ktlintCheck $GRADLE_OPTS; then
                print_ok "Ktlint 自动修复后通过"
                passed_stages=$((passed_stages + 1))
            else
                print_fail "Ktlint 自动修复后仍失败，请手动检查"
                show_fail_summary "$start_all" "$passed_stages" 1
                return 1
            fi
        fi
        total_stages=$((total_stages + 1))
        echo "   耗时: $(timer_end $start)"
        
        # lint-only 模式到此结束
        if [ "$MODE" = "lint" ]; then
            show_success_summary "$start_all" "$passed_stages" "$total_stages"
            return 0
        fi
    fi
    
    # Stage 2: 快速编译到 class（~5-30s）
    if [ "$MODE" = "compile" ] || [ "$MODE" = "incremental" ] || [ "$MODE" = "all" ]; then
        print_header "Stage 2/4: Kotlin 快速编译 (~5-30s)"
        local start=$(timer_start)
        local modules_to_compile="${TARGET_MODULE:-}"
        
        if [ -z "$TARGET_MODULE" ] && [ "$MODE" = "incremental" ]; then
            modules_to_compile=$(detect_changed_modules)
            print_info "检测到变更模块: $modules_to_compile"
        fi
        
        # 编译 Kotlin 到 class 文件（不打包）
        local compile_cmd=""
        for mod in $modules_to_compile; do
            compile_cmd="$compile_cmd ${mod}:compileDebugKotlin"
        done
        
        # 如果没有指定模块且未检测到变更，默认编译 app
        if [ -z "$compile_cmd" ]; then
            compile_cmd=":androidApp:compileDebugKotlin"
            print_info "未指定模块且未检测到变更，默认编译 :androidApp"
        fi
        
        if ./gradlew $compile_cmd $GRADLE_OPTS; then
            print_ok "Kotlin 编译通过"
            passed_stages=$((passed_stages + 1))
        else
            print_fail "Kotlin 编译失败"
            echo ""
            print_info "正在分类编译错误..."
            classify_compile_errors
            show_fail_summary "$start_all" "$passed_stages" 2
            return 1
        fi
        total_stages=$((total_stages + 1))
        echo "   耗时: $(timer_end $start)"
        
        # compile-only 模式到此结束
        if [ "$MODE" = "compile" ]; then
            show_success_summary "$start_all" "$passed_stages" "$total_stages"
            return 0
        fi
    fi
    
    # Stage 3: 资源编译与 dex（~10-60s）
    if [ "$MODE" = "incremental" ] || [ "$MODE" = "all" ]; then
        print_header "Stage 3/4: 资源与 Dex 编译 (~10-60s)"
        local start=$(timer_start)
        local modules_to_build="${TARGET_MODULE:-:androidApp}"
        
        if [ -z "$TARGET_MODULE" ] && [ "$MODE" = "incremental" ]; then
            modules_to_build=$(detect_changed_modules)
        fi
        
        # 执行到 mergeDex/debug 阶段（不打包 APK）
        local build_cmd=""
        for mod in $modules_to_build; do
            # 只编译到 dex 阶段
            build_cmd="$build_cmd ${mod}:mergeDexDebug"
        done
        
        # 如果 build_cmd 为空，默认构建 app
        if [ -z "$build_cmd" ]; then
            build_cmd=":androidApp:mergeDexDebug"
        fi
        
        if ./gradlew $build_cmd $GRADLE_OPTS 2>/dev/null || \
           ./gradlew ${TARGET_MODULE:-:androidApp}:assembleDebug $GRADLE_OPTS; then
            print_ok "资源与 Dex 编译通过"
            passed_stages=$((passed_stages + 1))
        else
            print_fail "资源或 Dex 编译失败"
            classify_compile_errors
            show_fail_summary "$start_all" "$passed_stages" 3
            return 1
        fi
        total_stages=$((total_stages + 1))
        echo "   耗时: $(timer_end $start)"
    fi
    
    # Stage 4: 完整 APK 打包（~30-120s）
    if [ "$MODE" = "all" ]; then
        print_header "Stage 4/4: 完整 APK 打包 (~30-120s)"
        local start=$(timer_start)
        
        if ./gradlew ${TARGET_MODULE:-:androidApp}:assembleDebug $GRADLE_OPTS; then
            print_ok "APK 打包成功"
            local apk=$(find androidApp/build/outputs/apk/debug -name "*.apk" | head -1)
            local size=$(du -h "$apk" 2>/dev/null | cut -f1 || echo "unknown")
            print_info "APK: $(basename "$apk") ($size)"
            passed_stages=$((passed_stages + 1))
        else
            print_fail "APK 打包失败"
            show_fail_summary "$start_all" "$passed_stages" 4
            return 1
        fi
        total_stages=$((total_stages + 1))
        echo "   耗时: $(timer_end $start)"
    fi
    
    show_success_summary "$start_all" "$passed_stages" "$total_stages"
    return 0
}

# 编译错误分类
classify_compile_errors() {
    # 获取最近的编译错误日志
    local error_log=""
    if [ -f "androidApp/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt" ]; then
        error_log=$(cat androidApp/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt 2>/dev/null | head -20)
    fi
    
    # 尝试从 gradle 输出中解析
    local gradle_log=$(./gradlew ${TARGET_MODULE:-:androidApp}:compileDebugKotlin 2>&1 | tail -50 || true)
    
    echo ""
    echo -e "${YELLOW}━━ 编译错误分析 ━━${NC}"
    
    # 分类 1: 语法错误
    if echo "$gradle_log" | grep -q "expecting\|unexpected\|syntax\|Expecting"; then
        echo -e "   ${RED}类型: 语法错误${NC}"
        echo -e "   ${YELLOW}建议: 检查括号匹配、关键字拼写、语句结束符${NC}"
    fi
    
    # 分类 2: 类型不匹配
    if echo "$gradle_log" | grep -q "Type mismatch\|cannot be applied to\|required:\|found:"; then
        echo -e "   ${RED}类型: 类型不匹配${NC}"
        echo -e "   ${YELLOW}建议: 检查函数参数类型、返回值类型、泛型参数${NC}"
    fi
    
    # 分类 3: 未解析的引用
    if echo "$gradle_log" | grep -q "Unresolved reference\|cannot find declaration\|not found"; then
        echo -e "   ${RED}类型: 未解析的引用${NC}"
        echo -e "   ${YELLOW}建议: 检查导入声明、变量/函数名拼写、依赖模块是否正确引入${NC}"
    fi
    
    # 分类 4: 可见性错误
    if echo "$gradle_log" | grep -q "Cannot access\|is private\|is invisible\|visibility"; then
        echo -e "   ${RED}类型: 可见性错误${NC}"
        echo -e "   ${YELLOW}建议: 检查修饰符（private/internal/public）、模块依赖关系${NC}"
    fi
    
    # 分类 5: 空安全
    if echo "$gradle_log" | grep -q "Null can not be\|Smart cast to\|Only safe\|non-null"; then
        echo -e "   ${RED}类型: 空安全错误${NC}"
        echo -e "   ${YELLOW}建议: 添加 ?. ?: !! 操作符，或使用 let/run 作用域函数${NC}"
    fi
    
    # 分类 6: 导入问题
    if echo "$gradle_log" | grep -q "unused import\|Wildcard import\|should be removed"; then
        echo -e "   ${RED}类型: 导入问题${NC}"
        echo -e "   ${YELLOW}建议: 删除未使用的导入，替换通配符导入为显式导入${NC}"
    fi
    
    # 分类 7: Shader/GLSL 错误（PoLang 特定）
    if echo "$gradle_log" | grep -qi "shader\|glsl\|vertex\|fragment"; then
        echo -e "   ${RED}类型: Shader 编译错误${NC}"
        echo -e "   ${YELLOW}建议: 检查 GLSL 语法、varying/uniform 一致性、精度修饰符${NC}"
    fi
    
    # 如果无法分类
    if echo "$gradle_log" | grep -q "BUILD FAILED"; then
        local first_error=$(echo "$gradle_log" | grep -E "e:\s+" | head -1 || echo "")
        if [ -n "$first_error" ]; then
            echo -e "   ${RED}首个错误: $first_error${NC}"
        fi
    fi
    
    echo ""
    print_info "完整错误日志: 执行 './gradlew ${TARGET_MODULE:-:androidApp}:compileDebugKotlin' 查看"
}

show_success_summary() {
    local start_all=$1
    local passed=$2
    local total=$3
    echo ""
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}  ✅ 快速编译全部通过 ($passed/$total 阶段)${NC}"
    echo -e "${GREEN}  总耗时: $(timer_end $start_all)${NC}"
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

show_fail_summary() {
    local start_all=$1
    local passed=$2
    local failed_stage=$3
    echo ""
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${RED}  ❌ 快速编译失败 (阶段 $failed_stage)${NC}"
    echo -e "${RED}  已通过: $passed 阶段 | 总耗时: $(timer_end $start_all)${NC}"
    echo -e "${RED}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# Watch 模式
run_watch() {
    print_header "Watch 模式: 监听 Kotlin 文件变更自动编译"
    print_info "按 Ctrl+C 停止监听"
    
    local last_hash=""
    while true; do
        # 计算所有 Kotlin 文件的哈希
        local current_hash=$(find androidApp/src engines/beauty-engine/src -name "*.kt" -type f -exec md5 -q {} + 2>/dev/null | md5 -q)
        
        if [ "$current_hash" != "$last_hash" ] && [ -n "$last_hash" ]; then
            echo ""
            print_info "检测到文件变更，自动编译..."
            echo "────────────────────────────────────────"
            run_compile || true
            echo "────────────────────────────────────────"
            print_info "继续监听..."
        fi
        
        last_hash="$current_hash"
        sleep 2
    done
}

# 主流程
print_header "🚀 PoLang Quick Compile - 分层快速编译"
echo ""
echo "模式: ${MODE}${TARGET_MODULE:+ | 目标模块: $TARGET_MODULE}${CLEAN:+ | clean}"
echo ""

if [ "$WATCH" = true ]; then
    run_watch
else
    run_compile
fi

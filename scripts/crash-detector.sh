#!/bin/bash
#
# Crash Detector - PoLang Crash 自动检测工具
# 用途: 扫描 logcat 中的崩溃、异常、ANR，生成崩溃报告
# 调用: ./scripts/crash-detector.sh [options]
#
# Options:
#   --live            实时监听 logcat 中的崩溃（Ctrl+C 停止）
#   --analyze <file>  分析指定的 logcat 文件
#   --since <time>    分析从指定时间开始的日志（如 "10 minutes ago"）
#   --output <file>   输出报告文件
#
# 示例:
#   ./scripts/crash-detector.sh --live                    # 实时监控
#   ./scripts/crash-detector.sh --since "10 minutes ago"  # 分析最近10分钟
#   ./scripts/crash-detector.sh --analyze /tmp/logcat.txt # 分析已有日志
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

MODE="since"
SINCE_TIME="10 minutes ago"
LOGCAT_FILE=""
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --live) MODE="live"; shift ;;
        --analyze) MODE="analyze"; LOGCAT_FILE="$2"; shift 2 ;;
        --since) MODE="since"; SINCE_TIME="$2"; shift 2 ;;
        --output) OUTPUT_FILE="$2"; shift 2 ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# 崩溃模式定义
declare -a CRASH_PATTERNS=(
    "FATAL EXCEPTION|FATAL|严重异常|致命错误"
    "AndroidRuntime.*Exception"
    "Native crash|signal .*|SIGSEGV|SIGABRT"
    "ANR|Application Not Responding"
    "java\.lang\..*Exception"
    "java\.lang\..*Error"
    "kotlin\..*Exception"
    "kotlin\.UninitializedPropertyAccessException"
    "Caused by:"
    "OpenGL|GL_ERROR|glError|Shader compile error"
    "EGL.*error|eglMakeCurrent failed"
    "OutOfMemoryError|OOM"
    "DeadObjectException|TransactionTooLargeException"
)

# 从 logcat 提取崩溃信息
extract_crashes() {
    local input_source="$1"
    local temp_file=$(mktemp)
    
    # 构建 grep 模式
    local pattern=$(IFS="|"; echo "${CRASH_PATTERNS[*]}")
    
    # 提取匹配行及其上下文
    grep -n -E "$pattern" "$input_source" 2>/dev/null | while IFS= read -r line; do
        local lineno=$(echo "$line" | cut -d: -f1)
        local content=$(echo "$line" | cut -d: -f2-)
        
        # 输出当前行
        echo "LINE:$lineno|$content"
        
        # 输出后续 5 行（堆栈跟踪）
        tail -n +$((lineno + 1)) "$input_source" | head -5 | while IFS= read -r ctx; do
            if echo "$ctx" | grep -qE "^\s+at\s+|^\s*Caused by:|^\s*\.\.\.\s+[0-9]+ more"; then
                echo "CTX:$lineno|$ctx"
            else
                break
            fi
        done
    done > "$temp_file"
    
    echo "$temp_file"
}

# 分类崩溃
categorize_crash() {
    local line="$1"
    
    if echo "$line" | grep -qi "FATAL EXCEPTION\|AndroidRuntime"; then
        echo "FATAL_EXCEPTION"
    elif echo "$line" | grep -qi "Native crash\|SIGSEGV\|SIGABRT\|signal"; then
        echo "NATIVE_CRASH"
    elif echo "$line" | grep -qi "ANR\|Not Responding"; then
        echo "ANR"
    elif echo "$line" | grep -qi "OpenGL\|GL_ERROR\|eglMakeCurrent\|Shader compile"; then
        echo "GL_CRASH"
    elif echo "$line" | grep -qi "OutOfMemory\|OOM"; then
        echo "OOM"
    elif echo "$line" | grep -qi "DeadObject\|TransactionTooLarge"; then
        echo "SYSTEM_CRASH"
    elif echo "$line" | grep -qi "UninitializedPropertyAccess"; then
        echo "KOTLIN_LATEINIT"
    elif echo "$line" | grep -qi "NullPointer"; then
        echo "NULL_POINTER"
    else
        echo "UNKNOWN"
    fi
}

# 生成报告
generate_report() {
    local crash_file="$1"
    local report_file="${OUTPUT_FILE:-$(mktemp)}"
    local crash_count=0
    
    # 先统计崩溃数量（在子 shell 外）
    crash_count=$(grep -c "^LINE:" "$crash_file" 2>/dev/null | head -1 || echo 0)
    
    {
        echo "# PoLang Crash Detection Report"
        echo ""
        echo "**生成时间**: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "**分析模式**: $MODE"
        if [ "$MODE" = "since" ]; then
            echo "**时间范围**: $SINCE_TIME"
        fi
        echo ""
        
        if [ ! -s "$crash_file" ] || [ "$crash_count" -eq 0 ]; then
            echo -e "${GREEN}✅ 未检测到崩溃或异常${NC}"
            echo ""
            echo "未发现匹配以下模式的日志条目:"
            for p in "${CRASH_PATTERNS[@]}"; do
                echo "  - $p"
            done
        else
            echo "## 检测到的崩溃/异常"
            echo ""
            
            local idx=0
            while IFS= read -r line; do
                local type=$(echo "$line" | cut -d: -f1)
                local content=$(echo "$line" | cut -d: -f2-)
                
                if [ "$type" = "LINE" ]; then
                    # 新崩溃开始
                    idx=$((idx + 1))
                    local category=$(categorize_crash "$content")
                    local severity="HIGH"
                    case "$category" in
                        FATAL_EXCEPTION|NATIVE_CRASH|OOM) severity="CRITICAL" ;;
                        ANR|GL_CRASH) severity="HIGH" ;;
                        SYSTEM_CRASH|KOTLIN_LATEINIT|NULL_POINTER) severity="MEDIUM" ;;
                        *) severity="LOW" ;;
                    esac
                    
                    echo ""
                    echo "### 崩溃 #$idx"
                    echo "- **严重程度**: $severity"
                    echo "- **类别**: $category"
                    echo "- **日志**:"
                    echo "  \`\`\`"
                    echo "  $content"
                    echo "  \`\`\`"
                    
                elif [ "$type" = "CTX" ]; then
                    echo "  $content"
                fi
            done < "$crash_file"
            
            echo ""
            echo "**总计**: $crash_count 个崩溃/异常"
        fi
    } | tee "$report_file"
    
    echo ""
    if [ -n "$OUTPUT_FILE" ]; then
        echo "📄 报告已保存: $OUTPUT_FILE"
    fi
    
    if [ "$crash_count" = "0" ]; then
        return 0
    else
        return 1
    fi
}

# 实时监控模式
live_mode() {
    echo -e "${CYAN}🔴 Live Crash Detection 启动 (按 Ctrl+C 停止)${NC}"
    echo -e "${YELLOW}监听以下崩溃模式:${NC}"
    for p in "${CRASH_PATTERNS[@]}"; do
        echo "  - $p"
    done
    echo ""
    
    local pattern=$(IFS="|"; echo "${CRASH_PATTERNS[*]}")
    adb logcat -v threadtime | grep --color=always -iE "($pattern)"
}

# 主流程
case "$MODE" in
    live)
        live_mode
        ;;
    
    analyze)
        if [ ! -f "$LOGCAT_FILE" ]; then
            echo "❌ 文件不存在: $LOGCAT_FILE"
            exit 1
        fi
        echo "🔍 分析日志文件: $LOGCAT_FILE"
        crash_file=$(extract_crashes "$LOGCAT_FILE")
        generate_report "$crash_file"
        rm -f "$crash_file"
        ;;
    
    since)
        echo "🔍 分析最近日志 ($SINCE_TIME)..."
        
        # 获取 logcat
        temp_log=$(mktemp)
        adb logcat -d -v threadtime -T "$(echo "$SINCE_TIME" | sed 's/minutes/minute/; s/hours/hour/')" > "$temp_log" 2>/dev/null || \
            adb logcat -d -v threadtime > "$temp_log" 2>/dev/null
        
        crash_file=$(extract_crashes "$temp_log")
        generate_report "$crash_file"
        local ret=$?
        
        rm -f "$temp_log" "$crash_file"
        exit $ret
        ;;
esac

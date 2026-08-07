#!/usr/bin/env bash
#
# run-local.sh - PoLang Server 本地开发启动脚本
# 用途: 本地启动 Ktor 后端用于测试，自动配好测试用 DB/端口，等就绪后打印 curl 示例。
# 调用: ./server/run-local.sh [命令]
#
# 命令:
#   start     后台启动 server，等就绪，打印测试命令（默认）
#   stop      停止 server
#   restart   重启（stop + start）
#   run       前台启动（日志直接输出，Ctrl+C 停，适合看实时日志）
#   status    查看是否在跑
#   logs      实时查看日志（tail -f，Ctrl+C 退出不影响 server）
#
# 默认配置（可用环境变量覆盖）:
#   HOST=127.0.0.1   PORT=8080   DB_PATH=<server>/build/picme.db
#
# 示例:
#   ./server/run-local.sh                  # 后台启动并打印测试命令
#   ./server/run-local.sh run              # 前台启动看日志
#   ./server/run-local.sh stop             # 停止
#   PORT=9090 ./server/run-local.sh        # 换端口启动
#
set -euo pipefail
export LC_ALL=C   # 纯 ASCII 变量名语义：避免 $var 紧邻中文标点时，bash 把高位字节吞进变量名（echo 中文不受影响）

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # .../polang/server
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
GRADLEW="$REPO_ROOT/gradlew"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
DB_PATH="${DB_PATH:-$SCRIPT_DIR/build/picme.db}"
LOG_FILE="$SCRIPT_DIR/build/server.log"
PID_FILE="$SCRIPT_DIR/build/server.pid"
BASE_URL="http://$HOST:$PORT"

mkdir -p "$SCRIPT_DIR/build"

# ---- 辅助 ----
# 返回监听本端口的 PID（无则空）
port_pid() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true; }
is_running() { [[ -n "$(port_pid)" ]]; }

# 轮询 healthz 直到就绪，最长 ~50s
wait_ready() {
    for _ in {1..100}; do
        if curl -fsS "$BASE_URL/healthz" >/dev/null 2>&1; then return 0; fi
        sleep 0.5
    done
    return 1
}

print_hints() {
    echo
    echo "测试命令:"
    echo "  curl $BASE_URL/healthz"
    echo "  curl -X POST $BASE_URL/recommend -H 'Content-Type: application/json' -d '{\"scene\":\"night\",\"locale\":\"zh\"}'"
    echo "  curl -X POST $BASE_URL/telemetry -H 'Content-Type: application/json' -d '{\"events\":[{\"type\":\"test\"}]}'"
    echo
    echo "停止: $0 stop    日志: $0 logs    前台: $0 run"
}

# ---- 命令 ----
cmd_start() {
    if is_running; then
        echo "已在运行（端口 $PORT, PID $(port_pid)）。重启用 '$0 restart'。"
        exit 0
    fi
    echo ">> 启动 server（$BASE_URL，DB=$DB_PATH）"
    nohup env HOST="$HOST" PORT="$PORT" DB_PATH="$DB_PATH" \
        "$GRADLEW" -p "$SCRIPT_DIR" run --no-daemon > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    echo ">> 等就绪（日志: $LOG_FILE）"
    if wait_ready; then
        echo "✅ 就绪"
    else
        echo "❌ 50s 内未就绪，日志末尾："
        tail -20 "$LOG_FILE" 2>/dev/null || true
        exit 1
    fi
    print_hints
}

cmd_stop() {
    local pid
    pid="$(port_pid)"
    if [[ -z "$pid" ]]; then
        echo "未在运行（端口 $PORT 无监听）。"
        rm -f "$PID_FILE"
        return
    fi
    echo ">> 停止 server（监听 PID: $pid）"
    kill "$pid" 2>/dev/null || true
    for _ in {1..30}; do
        [[ -z "$(port_pid)" ]] && break
        sleep 0.3
    done
    pid="$(port_pid)"
    if [[ -n "$pid" ]]; then
        echo ">> SIGKILL 强杀 PID $pid"
        kill -9 "$pid" 2>/dev/null || true
    fi
    # 清理可能残留的 gradle 父进程
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        kill "$(cat "$PID_FILE")" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    echo "已停止。"
}

cmd_run() {
    if is_running; then
        echo "端口 $PORT 已被占用（PID $(port_pid)），先 '$0 stop'。" >&2
        exit 1
    fi
    echo ">> 前台启动（$BASE_URL，Ctrl+C 停）"
    exec env HOST="$HOST" PORT="$PORT" DB_PATH="$DB_PATH" \
        "$GRADLEW" -p "$SCRIPT_DIR" run --no-daemon
}

cmd_status() {
    if is_running; then
        echo "✅ 运行中（端口 $PORT, PID $(port_pid), $BASE_URL）"
    else
        echo "❌ 未运行"
        exit 1
    fi
}

cmd_logs() {
    [[ -f "$LOG_FILE" ]] || { echo "无日志文件: $LOG_FILE"; exit 1; }
    echo ">> tail -f $LOG_FILE（Ctrl+C 退出，不影响 server）"
    tail -f "$LOG_FILE"
}

case "${1:-start}" in
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    restart) cmd_stop; cmd_start ;;
    run)     cmd_run ;;
    status)  cmd_status ;;
    logs)    cmd_logs ;;
    *) echo "用法: $0 [start|stop|restart|run|status|logs]"; exit 1 ;;
esac

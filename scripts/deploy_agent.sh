#!/usr/bin/env bash
# =============================================================================
# TARS Agent 部署脚本（Termux / Linux）
# 自动完成：环境检查 -> 建/复用 venv -> 装依赖 -> 校验云端配置 -> 启动 agent.server
# 用法：
#   ./scripts/deploy_agent.sh              前台启动（Ctrl+C 停止）
#   ./scripts/deploy_agent.sh --background 后台启动（nohup，PID 写到 .agent.pid）
#   ./scripts/deploy_agent.sh --mock       协议联调模式（无需云端 config/cloud.yaml）
#   ./scripts/deploy_agent.sh --port 8081  指定监听端口
#   ./scripts/deploy_agent.sh --stop       停止后台运行的 Agent
#   ./scripts/deploy_agent.sh --help       显示本帮助
# =============================================================================
set -uo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$APP_DIR/.venv"
VENV_BIN="$VENV/bin"
PY="$VENV_BIN/python"
PIP="$VENV_BIN/pip"
REQ="$APP_DIR/requirements.txt"
CONFIG="$APP_DIR/config/cloud.yaml"
EXAMPLE="$APP_DIR/config/cloud.yaml.example"
LOG="$APP_DIR/tars-agent.log"
PIDFILE="$APP_DIR/.agent.pid"
PORT=8080

color() { if [ -t 1 ]; then echo -e "$1"; else echo -e "$2"; fi; }
info() { color "\033[1;34m[INFO]\033[0m $*" "[INFO] $*"; }
ok()   { color "\033[1;32m[ OK ]\033[0m $*" "[ OK ] $*"; }
warn() { color "\033[1;33m[WARN]\033[0m $*" "[WARN] $*"; }
err()  { color "\033[1;31m[ERR ]\033[0m $*" "[ERR ] $*"; }
die()  { err "$*"; exit 1; }

usage() { cat <<EOF
用法： ./scripts/deploy_agent.sh [选项]
  --background   后台启动（nohup，PID 记录到 .agent.pid）
  --mock         协议联调模式（不需真实云端 config/cloud.yaml）
  --port PORT    监听端口（1-65535，默认：8080）
  --stop         停止后台运行的服务
  --help         显示本帮助
EOF
}

MODE="foreground"
BACKEND="cloud"
while [ $# -gt 0 ]; do
  case "$1" in
    --background) MODE="background" ;;
    --mock)       BACKEND="mock" ;;
    --port)
      [ $# -ge 2 ] || die "--port 需要一个端口号"
      PORT="$2"
      case "$PORT" in
        ''|*[!0-9]*) die "端口必须是 1 到 65535 的整数：$PORT" ;;
      esac
      [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "端口必须在 1 到 65535 之间：$PORT"
      shift
      ;;
    --stop)       MODE="stop" ;;
    -h|--help)    usage; exit 0 ;;
    *) die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

command -v python3 >/dev/null 2>&1 || die "未找到 python3。请在 Termux 执行：pkg install python"
[ -r "$REQ" ] || die "缺少 $REQ，请确认在项目根目录下的 scripts 里运行"
[ "$BACKEND" = "mock" ] || command -v git >/dev/null 2>&1 || warn "未找到 git（仅源码克隆时用，不影响本机运行）"

if [ ! -d "$VENV" ]; then
  info "创建虚拟环境：$VENV"
  python3 -m venv "$VENV" || die "创建虚拟环境失败（python3 -m venv）。请确认 python3 带 venv；Termux 可 pkg install python"
  ok "虚拟环境已创建"
else
  info "复用已有虚拟环境：$VENV"
fi

info "安装/校验依赖（requirements.txt）…"
"$PIP" install --upgrade pip >/dev/null 2>&1 || warn "pip 升级失败（可忽略，继续装依赖）"
if ! "$PIP" install -q -r "$REQ"; then
  die "依赖安装失败。请检查能否访问 PyPI（必要时配置国内源），或是否有编译依赖缺失。本仓库依赖均为纯 Python。"
fi
ok "依赖就绪"

if [ "$BACKEND" = "mock" ]; then
  warn "mock 模式：不需要真实 config/cloud.yaml（仅协议联调用）"
else
  if [ ! -f "$CONFIG" ]; then
    if [ -f "$EXAMPLE" ]; then
      warn "未找到 $CONFIG，已从示例复制。请编辑填入 base_url / model / api_key！"
      cp "$EXAMPLE" "$CONFIG" || die "复制配置示例失败"
    else
      die "缺少 $CONFIG 且无示例 $EXAMPLE。请在 config/cloud.yaml 填写云端模型配置（或在 config 目录建一个）"
    fi
  fi
  if grep -qiE "replace-with|REPLACE_|api_key:[[:space:]]*$" "$CONFIG"; then
    warn "config/cloud.yaml 的 api_key 仍未填写（还是占位符）！云端调用会失败，请先编辑。"
  fi
fi

if command -v ss >/dev/null 2>&1; then
  if ss -ltn 2>/dev/null | grep -q ":$PORT"; then
    warn "端口 $PORT 已被占用，可能已有 Agent 在运行。"
    warn "如需重启：先用 ./scripts/deploy_agent.sh --stop，或手动 pkill -f agent.server"
  fi
fi

stop_agent() {
  if [ -f "$PIDFILE" ]; then
    PID="$(cat "$PIDFILE")"
    if kill -0 "$PID" 2>/dev/null; then
      kill "$PID" && ok "已停止 Agent（PID $PID）"
    else
      warn "PID $PID 已不存在，清理残留 PID 文件"
    fi
    rm -f "$PIDFILE"
  else
    warn "未找到 PID 文件（非后台启动，或 PID 已清理）"
  fi
  exit 0
}

CMD_ARGS=()
[ "$BACKEND" = "mock" ] && CMD_ARGS+=("--mock")

[ "$MODE" = "stop" ] && stop_agent

cd "$APP_DIR"

if [ "$MODE" = "background" ]; then
  info "后台启动 Agent（监听 0.0.0.0:$PORT，日志：$LOG，PID 写入：$PIDFILE）"
  nohup "$PY" -m agent.server "${CMD_ARGS[@]}" --port "$PORT" --log-file "$LOG" >/dev/null 2>&1 &
  PID=$!
  echo "$PID" > "$PIDFILE"
  sleep 2
  if kill -0 "$PID" 2>/dev/null; then
    ok "Agent 已启动（PID $PID）。日志：$LOG"
    if command -v curl >/dev/null 2>&1; then
      sleep 1
      RES="$(curl -s "http://127.0.0.1:$PORT/health" || true)"
      [ -n "$RES" ] && ok "健康检查：$RES" || warn "健康检查未就绪，稍后可用 curl http://127.0.0.1:$PORT/health 自检"
    fi
  else
    die "Agent 启动失败，请查看日志：$LOG"
  fi
else
  info "前台启动 Agent（监听 0.0.0.0:$PORT，Ctrl+C 停止）… 日志同时写入 $LOG"
  exec "$PY" -m agent.server "${CMD_ARGS[@]}" --port "$PORT" --log-file "$LOG"
fi

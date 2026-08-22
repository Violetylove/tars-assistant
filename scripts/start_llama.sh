#!/usr/bin/env bash
# 启动 llama-server（OpenAI 兼容端点 /v1/chat/completions）。
#
# 生命周期 D5：可由 agent server 按需拉起（LlamaManager），或用空闲退出。
# 本脚本只负责"拉起服务并常驻"，退出控制交由调用方（server 层）。
#
# 用法：
#   bash scripts/start_llama.sh            # 用 config 内默认模型/端口
#   LLAMA_PORT=11434 MODEL=models/...gguf bash scripts/start_llama.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 从 config.yaml 取默认（若可用 yq/jq）。这里用环境变量兜底 + 默认值。
LLAMA_BIN="${LLAMA_BIN:-llama-server}"          # llama-server 可执行文件（Windows 用 llama-server.exe 或 .exe 路径）
LLAMA_PORT="${LLAMA_PORT:-11434}"
MODEL="${MODEL:-$ROOT_DIR/models/qwen2.5-3b-instruct-q4_k_m.gguf}"
CTX="${CTX:-8192}"                              # 上下文（3B 默认 8K）

if [[ ! -f "$MODEL" ]]; then
  echo "错误：模型文件不存在：$MODEL" >&2
  echo "请先运行：bash scripts/download_model.sh 3b" >&2
  exit 1
fi

# llama-server 需存在。找不到时给出指引。
if ! command -v "$LLAMA_BIN" >/dev/null 2>&1; then
  echo "错误：找不到 llama-server（$LLAMA_BIN）" >&2
  echo "提示：Windows 可下载 llama.cpp 对应二进制并置于 PATH；Termux 用 pkg install llama.cpp 或自行编译。" >&2
  exit 1
fi

echo ">> 启动 llama-server（model=$MODEL, port=$LLAMA_PORT, ctx=$CTX）"
exec "$LLAMA_BIN" \
  -m "$MODEL" \
  --host 127.0.0.1 \
  --port "$LLAMA_PORT" \
  -c "$CTX" \
  --parallel 1

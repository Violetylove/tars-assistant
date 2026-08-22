#!/usr/bin/env bash
# 从 ModelScope 下载 Qwen2.5 GGUF 模型（国内直连，无需代理）。
# 用法：bash scripts/download_model.sh [3b|7b]
#
# 决策 D3：默认 3B；预留 7B 档位。
# 源：ModelScope（国内直连快稳，见阶段 3 选定）。
# 若需切换源（HF/镜像），改 MODEL_REPO 与 FILE 即可。

set -euo pipefail

# 默认档位
TIER="${1:-3b}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/models"
mkdir -p "$OUT_DIR"

# 各档位在 ModelScope 的仓库与实际 GGUF 文件名（需按实际上传者确认）
# 此处给出通用结构化；真实 URL 以 unSloth/Qwen 官方 repo 的 Q4_K_M 文件为准
case "$TIER" in
  3b)
    # ModelScope 官方 GGUF 仓库（经 /api/.../repo/files 核实的 Q4_K_M 文件）
    MODEL_REPO="Qwen/Qwen2.5-3B-Instruct-GGUF"
    FILE="qwen2.5-3b-instruct-q4_k_m.gguf"   # 约 2.1GB（Design §3 默认档）
    ;;
  7b)
    MODEL_REPO="Qwen/Qwen2.5-7B-Instruct-GGUF"
    FILE="qwen2.5-7b-instruct-q4_k_m.gguf"
    ;;
  *)
    echo "未知档位: $TIER（可选 3b / 7b）" >&2
    exit 2
    ;;
esac

OUT_FILE="$OUT_DIR/$FILE"
if [[ -f "$OUT_FILE" ]]; then
  echo "模型已存在：$OUT_FILE（跳过下载）"
  exit 0
fi

echo ">> 从 ModelScope 下载：$MODEL_REPO / $FILE"
echo ">> 目标：$OUT_FILE"

# 优先用 modelscope cli；若无则用 curl 直链（ModelScope 文件直链格式见下）。
if command -v modelscope >/dev/null 2>&1; then
  modelscope download "$MODEL_REPO" "$FILE" --local_dir "$OUT_DIR"
else
  # ModelScope 文件直链（示例；实际路径以模型页 Resolve 为准）
  BASE="https://www.modelscope.cn/models/$MODEL_REPO/resolve/master/$FILE"
  echo ">> 未安装 modelscope cli，尝试 curl 直链。若失败，请 pip install modelscope 后重试。"
  curl -L --fail --retry 3 -o "$OUT_FILE" "$BASE"
fi

echo "<< 完成：$OUT_FILE（$(du -h "$OUT_FILE" 2>/dev/null | cut -f1)）"

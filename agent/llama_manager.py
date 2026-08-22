"""llama_manager — llama-server 按需拉起 / 空闲退出生命周期（决策 D5）。

对齐 docs/DESIGN.md §8.1：
- ensure_up：若 llama-server 未运行，则 spawn 并等待健康就绪
- idle_shutdown：任务结束 + 空闲后退出释放内存

设计要点：
- 用 /health 探测 llama-server 是否就绪（不是只看进程存在）。
- 幂等：已在运行则直接返回。
- 冷启动将拉起子进程；由 start_llama.sh 封装具体启动参数（阶段 3 的 scripts/）。
- 平台差异（Windows/Termux）经启动命令拼装处理；此处只负责进程与生命周期。
"""

from __future__ import annotations

import logging
import os
import subprocess
import time
from pathlib import Path

logger = logging.getLogger("tars.llama_manager")

# 健康探测地址（默认 OpenAI 兼容端口）
DEFAULT_BASE_URL = "http://127.0.0.1:11434"


def _http_health(base_url: str) -> bool:
    """返回 llama-server 是否健康。用标准库，避免额外依赖（仅探测基础端点）。"""
    import urllib.request

    for prefix in ("", "/health"):
        url = f"{base_url}{prefix}"
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            continue
    return False


class LlamaManager:
    """按需拉起 / 空闲退出的进程管理器。

    正确语义（D5 / DESIGN.md §8.1）：
    - 任务发起时 ensure_up()：拉起并等待就绪；已在运行则仅刷新 activity。
    - 任务期间（多轮决策）复用同一实例——不逐请求退出。
    - 每次请求 touch() 刷新 activity；后台/轮询 check_idle() 在超过
      idle_shutdown_seconds 未活动时退出。caller 侧（server）在每次请求之末
      调用 touch()，并在定时或请求间隙调用 check_idle()。
    """

    def __init__(self, base_url: str = DEFAULT_BASE_URL,
                 start_cmd: list[str] | None = None,
                 idle_shutdown_seconds: int = 60,
                 startup_timeout_seconds: int = 30):
        self.base_url = base_url
        # 启动命令；若未指定则用 scripts/start_llama.sh 封装（存在时）
        self.start_cmd = start_cmd or self._default_start_cmd()
        self.idle_shutdown_seconds = idle_shutdown_seconds
        self.startup_timeout_seconds = startup_timeout_seconds
        self._proc: subprocess.Popen | None = None
        self._last_active = time.time()

    @staticmethod
    def _default_start_cmd() -> list[str]:
        """默认启动命令：优先 scripts/start_llama.sh，否则提示需自定义。"""
        script = Path(__file__).resolve().parent.parent / "scripts" / "start_llama.sh"
        if script.exists():
            return ["bash", str(script)]
        return ["echo", "未找到 scripts/start_llama.sh，请配置 llama_manager.start_cmd"]

    # --- 生命周期钩子（挂到 server.llm_lifecycle，阶段 3） ---

    def ensure_up(self) -> bool:
        """确保 llama-server 运行并就绪。返回就绪与否。"""
        if self._http_ready():
            self._last_active = time.time()
            return True
        if self._proc is None or self._proc.poll() is not None:
            # 冷启动
            logger.info("llama-server 未运行，拉起…")
            self._proc = subprocess.Popen(
                self.start_cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        # 等待就绪（轮询 /health）
        deadline = time.time() + self.startup_timeout_seconds
        while time.time() < deadline:
            if self._http_ready():
                self._last_active = time.time()
                return True
            time.sleep(0.5)
        logger.warning("llama-server 启动超时（%ss）", self.startup_timeout_seconds)
        return False

    def touch(self):
        """任务请求之末调用：刷新 activity 时间戳（不退出）。"""
        self._last_active = time.time()

    def check_idle(self) -> bool:
        """若空闲超过阈值则退出。返回是否已退出（供 caller 判忙闲）。"""
        if self._proc is None or self._proc.poll() is not None:
            return True
        if time.time() - self._last_active > self.idle_shutdown_seconds:
            logger.info("llama-server 空闲 %ss，退出释放内存", self.idle_shutdown_seconds)
            self._terminate()
            return True
        return False

    def shutdown(self):
        """进程级关闭（应用终止时）。"""
        if self._proc is not None and self._proc.poll() is None:
            self._terminate()

    def _terminate(self):
        try:
            self._proc.terminate()
            self._proc.wait(timeout=5)
        except (subprocess.TimeoutExpired, OSError):
            try:
                self._proc.kill()
            except OSError:
                pass
        self._proc = None

    def _http_ready(self) -> bool:
        return _http_health(self.base_url)

    # 供 server 挂接的统一入口（D5：ensure_up / touch / check_idle / shutdown）
    def lifecycle(self, phase: str):
        if phase == "ensure_up":
            self.ensure_up()
        elif phase == "touch":
            self.touch()
        elif phase == "check_idle":
            self.check_idle()
        elif phase == "shutdown":
            self.shutdown()

#!/usr/bin/env python3
"""Qwen3.5-2B 相册 Pass 3 打标 benchmark。

用法:
    python3 scripts/benchmark_qwen35_2b.py [--count 100] [--output benchmark_qwen35_2b_report.json]

前置条件:
    1. 设备已连接 adb
    2. PicMe 已安装并至少有 [count] 张未打标照片，或之前已完成 Pass 1/2
    3. Qwen3.5-2B-MNN 模型已下载到设备

本脚本通过启动 TagGenerationService.ACTION_SCAN_PASS_3_FULL 触发全量 Pass 3，
实时解析 logcat 中 TagScheduler / LocalLlmEngine 日志，收集每张图的：
    - mediaId
    - 总耗时（Stage 3 开始至写入 DB，ms）
    - 推理耗时（vision + decode，us 转 ms）
    - 输出 token 数（decodeLen）
    - JSON 是否成功解析
    - 场景与标签预览
    - 原始输出前 200 字符
"""

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Optional


PACKAGE = "com.mamba.picme"
SERVICE = "com.mamba.picme.service.tag.TagGenerationService"
ACTION_SCAN_PASS_3_FULL = "com.mamba.picme.tag.SCAN_PASS_3_FULL"

LOGCAT_TAG_SCHEDULER = "TagScheduler"
LOGCAT_TAG_LLM = "LocalLlmEngine"


@dataclass
class Sample:
    media_id: int
    duration_ms: int = 0
    vision_ms: float = 0.0
    decode_ms: float = 0.0
    decode_len: int = 0
    json_ok: bool = False
    scene: str = ""
    tags: list[str] = field(default_factory=list)
    raw_preview: str = ""
    error: Optional[str] = None


@dataclass
class Report:
    model: str = "qwen3_5_2b"
    count_target: int = 100
    count_actual: int = 0
    json_ok_count: int = 0
    json_fail_count: int = 0
    median_duration_ms: float = 0.0
    p90_duration_ms: float = 0.0
    avg_duration_ms: float = 0.0
    median_vision_ms: float = 0.0
    median_decode_ms: float = 0.0
    avg_decode_len: float = 0.0
    samples: list = field(default_factory=list)
    started_at: str = ""
    finished_at: str = ""


def percentile(sorted_values: list[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    return sorted_values[f] + (sorted_values[c] - sorted_values[f]) * (k - f)


def run_adb(args: list[str], check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["adb", *args]
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def check_device() -> bool:
    result = run_adb(["devices"], check=False)
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    devices = [line for line in lines[1:] if line.endswith("device")]
    if not devices:
        print("[-] 未检测到 adb 设备", file=sys.stderr)
        return False
    if len(devices) > 1:
        print(f"[!] 检测到多个设备，默认使用第一个: {devices[0].split()[0]}")
    return True


def start_logcat() -> subprocess.Popen:
    run_adb(["logcat", "-c"], check=False)
    return subprocess.Popen(
        ["adb", "logcat", "-s", f"{LOGCAT_TAG_SCHEDULER}:D", f"{LOGCAT_TAG_LLM}:D"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )


def trigger_pass3_full() -> None:
    # TagGenerationService 未导出，通过 AgentTestBroadcastReceiver 触发
    shell_cmd = (
        "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver "
        "-a com.mamba.picme.AGENT_TEST --es cmd scan_pass3_full"
    )
    run_adb(["shell", shell_cmd], check=False)


def cancel_scan() -> None:
    shell_cmd = (
        "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver "
        "-a com.mamba.picme.AGENT_TEST --es cmd cancel"
    )
    run_adb(["shell", shell_cmd], check=False)


def parse_logcat(proc: subprocess.Popen, count_target: int, timeout_sec: int) -> list[Sample]:
    samples: dict[int, Sample] = {}
    current_media_id: Optional[int] = None

    # [Benchmark] Pass 3 start: mediaId=123（部分设备 mediaId 为负数）
    start_re = re.compile(r"\[Benchmark\] Pass 3 start: mediaId=(-?\d+)")
    # [Benchmark] Pass 3 done: mediaId=123, durationMs=456, jsonOk=true,
    #             scene=公园, tags=[女, 婴儿, ...]
    done_re = re.compile(
        r"\[Benchmark\] Pass 3 done: mediaId=(-?\d+), durationMs=(\d+), "
        r"jsonOk=(true|false), scene=([^,]*), tags=(.+?)\]"
    )
    # LocalLlmEngine: [Vision] inference done: ..., promptLen=123, decodeLen=45,
    #                 vision=12345us, decode=67890us
    llm_re = re.compile(
        r"promptLen=(\d+), decodeLen=(\d+), vision=(\d+)us, decode=(\d+)us"
    )

    start_time = time.time()
    last_sample_time = time.time()

    while True:
        line = proc.stdout.readline()
        if not line:
            if time.time() - last_sample_time > 10:
                break
            time.sleep(0.05)
            continue

        last_sample_time = time.time()

        if LOGCAT_TAG_SCHEDULER in line:
            m = start_re.search(line)
            if m:
                current_media_id = int(m.group(1))
                continue

            m = done_re.search(line)
            if m:
                media_id = int(m.group(1))
                duration_ms = int(m.group(2))
                json_ok = m.group(3).lower() == "true"
                scene = m.group(4).strip()
                tags_str = m.group(5) + "]"
                try:
                    tags = json.loads(tags_str.replace("'", '"'))
                except json.JSONDecodeError:
                    tags = []

                samples[media_id] = Sample(
                    media_id=media_id,
                    duration_ms=duration_ms,
                    json_ok=json_ok,
                    scene=scene,
                    tags=tags,
                    raw_preview=line.strip()[-200:],
                )
                current_media_id = None
                continue

        if LOGCAT_TAG_LLM in line:
            m = llm_re.search(line)
            if m and current_media_id is not None and current_media_id in samples:
                sample = samples[current_media_id]
                sample.decode_len = int(m.group(2))
                sample.vision_ms = int(m.group(3)) / 1000.0
                sample.decode_ms = int(m.group(4)) / 1000.0
            continue

        if len(samples) >= count_target:
            break

        if time.time() - start_time > timeout_sec:
            print(f"[!] 达到超时 {timeout_sec}s，已收集 {len(samples)} 条", file=sys.stderr)
            break

    return list(samples.values())


def build_report(samples: list[Sample], count_target: int, started_at: str, model: str) -> Report:
    durations = sorted([s.duration_ms for s in samples])
    vision_times = sorted([s.vision_ms for s in samples])
    decode_times = sorted([s.decode_ms for s in samples])
    decode_lens = [s.decode_len for s in samples if s.decode_len > 0]

    return Report(
        model=model,
        count_target=count_target,
        count_actual=len(samples),
        json_ok_count=sum(1 for s in samples if s.json_ok),
        json_fail_count=sum(1 for s in samples if not s.json_ok),
        median_duration_ms=percentile(durations, 0.5),
        p90_duration_ms=percentile(durations, 0.9),
        avg_duration_ms=sum(durations) / len(durations) if durations else 0.0,
        median_vision_ms=percentile(vision_times, 0.5),
        median_decode_ms=percentile(decode_times, 0.5),
        avg_decode_len=sum(decode_lens) / len(decode_lens) if decode_lens else 0.0,
        samples=[
            {
                "media_id": s.media_id,
                "duration_ms": s.duration_ms,
                "vision_ms": round(s.vision_ms, 2),
                "decode_ms": round(s.decode_ms, 2),
                "decode_len": s.decode_len,
                "json_ok": s.json_ok,
                "scene": s.scene,
                "tags": s.tags,
                "error": s.error,
                "raw_preview": s.raw_preview,
            }
            for s in samples
        ],
        started_at=started_at,
        finished_at=datetime.now().isoformat(),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Benchmark Qwen tagging (default: qwen3_5_2b)")
    parser.add_argument("--count", type=int, default=100, help="目标样本数 (default: 100)")
    parser.add_argument("--output", type=str, default="benchmark_qwen35_report.json",
                        help="报告输出路径")
    parser.add_argument("--timeout", type=int, default=1800, help="整体超时秒数 (default: 1800)")
    parser.add_argument("--no-cancel", action="store_true", help="完成后不取消扫描")
    parser.add_argument("--model", type=str, default="qwen3_5_2b",
                        help="被测模型标识，仅写入报告 (default: qwen3_5_2b)")
    args = parser.parse_args()

    if not check_device():
        return 1

    print(f"[+] 启动 Qwen3.5-2B Pass 3 benchmark，目标 {args.count} 张")
    started_at = datetime.now().isoformat()

    proc = start_logcat()
    try:
        trigger_pass3_full()
        samples = parse_logcat(proc, args.count, args.timeout)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        if not args.no_cancel:
            cancel_scan()

    report = build_report(samples, args.count, started_at, args.model)

    output_path = Path(args.output)
    output_path.write_text(json.dumps(report.__dict__, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[+] 实际收集 {report.count_actual} 条")
    print(f"[+] JSON 解析成功: {report.json_ok_count} / 失败: {report.json_fail_count}")
    print(f"[+] 中位延迟: {report.median_duration_ms:.0f}ms, P90: {report.p90_duration_ms:.0f}ms")
    print(f"[+] 报告已保存: {output_path.absolute()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

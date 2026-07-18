#!/usr/bin/env python3
"""
渲染性能分析工具
用法: python3 profile-render-performance.py [adb_device_id]
"""

import subprocess
import sys
import re
from collections import defaultdict
from datetime import datetime


def parse_logcat(device_id: str = ""):
    """解析 logcat 输出，提取性能指标"""
    adb_cmd = ["adb"]
    if device_id:
        adb_cmd.extend(["-s", device_id])
    
    adb_cmd.extend(["logcat", "-s", "PoLang:BeautyRenderer:D", "PoLang:CameraPreviewRenderer:D"])
    
    print("📊 开始捕获渲染性能数据...")
    print("   提示: 请在应用中操作 10-20 秒，然后按 Ctrl+C 停止\n")
    
    fps_data = []
    render_time_data = []
    null_frame_count = 0
    total_frames = 0
    
    try:
        process = subprocess.Popen(
            adb_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        
        for line in process.stdout:
            # 提取 FPS
            fps_match = re.search(r'FPS:\s*(\d+)', line)
            if fps_match:
                fps = int(fps_match.group(1))
                fps_data.append(fps)
                total_frames += 1
            
            # 提取渲染耗时
            time_match = re.search(r'render_time=(\d+\.\d+)ms', line)
            if time_match:
                render_time = float(time_match.group(1))
                render_time_data.append(render_time)
            
            # 提取空帧计数
            null_match = re.search(r'null_frames=(\d+)', line)
            if null_match:
                null_frame_count = int(null_match.group(1))
            
            # 实时显示
            if fps_match or time_match:
                print(line.strip())
    
    except KeyboardInterrupt:
        print("\n\n✅ 捕获完成，正在生成报告...\n")
    finally:
        if 'process' in locals():
            process.terminate()
    
    return {
        'fps_data': fps_data,
        'render_time_data': render_time_data,
        'null_frame_count': null_frame_count,
        'total_frames': total_frames
    }


def calculate_stats(data: list) -> dict:
    """计算统计数据"""
    if not data:
        return {}
    
    sorted_data = sorted(data)
    n = len(sorted_data)
    
    return {
        'min': sorted_data[0],
        'max': sorted_data[-1],
        'avg': sum(sorted_data) / n,
        'p50': sorted_data[n // 2],
        'p90': sorted_data[int(n * 0.9)],
        'p95': sorted_data[int(n * 0.95)],
        'p99': sorted_data[int(n * 0.99)]
    }


def generate_report(stats: dict):
    """生成性能报告"""
    print("=" * 70)
    print("📊 PoLang 渲染性能分析报告")
    print("=" * 70)
    print(f"分析时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print()
    
    # FPS 统计
    if stats['fps_data']:
        fps_stats = calculate_stats(stats['fps_data'])
        print("🎯 FPS 统计:")
        print(f"  平均 FPS:  {fps_stats['avg']:.1f}")
        print(f"  最低 FPS:  {fps_stats['min']}")
        print(f"  最高 FPS:  {fps_stats['max']}")
        print(f"  P50:       {fps_stats['p50']}")
        print(f"  P90:       {fps_stats['p90']}")
        print(f"  P95:       {fps_stats['p95']}")
        print(f"  P99:       {fps_stats['p99']}")
        print()
        
        # 性能评级
        avg_fps = fps_stats['avg']
        if avg_fps >= 55:
            rating = "✅ 优秀"
            color = "\033[32m"
        elif avg_fps >= 45:
            rating = "⚠️  合格"
            color = "\033[33m"
        else:
            rating = "❌ 不合格"
            color = "\033[31m"
        
        print(f"  性能评级: {color}{rating}\033[0m")
        print()
    
    # 渲染耗时统计
    if stats['render_time_data']:
        time_stats = calculate_stats(stats['render_time_data'])
        print("⏱️  单帧渲染耗时统计:")
        print(f"  平均耗时:  {time_stats['avg']:.2f} ms")
        print(f"  最短耗时:  {time_stats['min']:.2f} ms")
        print(f"  最长耗时:  {time_stats['max']:.2f} ms")
        print(f"  P50:       {time_stats['p50']:.2f} ms")
        print(f"  P90:       {time_stats['p90']:.2f} ms")
        print(f"  P95:       {time_stats['p95']:.2f} ms")
        print(f"  P99:       {time_stats['p99']:.2f} ms")
        print()
        
        # 60fps 预算线
        budget_60fps = 16.67
        over_budget = sum(1 for t in stats['render_time_data'] if t > budget_60fps)
        over_pct = (over_budget / len(stats['render_time_data'])) * 100
        
        print(f"  超过 60fps 预算 ({budget_60fps}ms): {over_budget} 帧 ({over_pct:.1f}%)")
        print()
    
    # 空帧统计
    if stats['total_frames'] > 0:
        null_rate = (stats['null_frame_count'] / stats['total_frames']) * 100
        print(f"🖼️  空帧统计:")
        print(f"  总帧数:     {stats['total_frames']}")
        print(f"  空帧数:     {stats['null_frame_count']}")
        print(f"  空帧率:     {null_rate:.2f}%")
        print()
        
        if null_rate < 5:
            print(f"  评价: ✅ 正常范围 (<5%)")
        elif null_rate < 15:
            print(f"  评价: ⚠️  偏高，建议优化 CameraX 配置")
        else:
            print(f"  评价: ❌ 严重，需立即排查")
        print()
    
    # 优化建议
    print("=" * 70)
    print("💡 优化建议:")
    print("=" * 70)
    
    if stats['fps_data'] and calculate_stats(stats['fps_data'])['avg'] < 55:
        print("1. 🎯 FPS 偏低:")
        print("   - 检查 Shader 复杂度，减少不必要的计算")
        print("   - 启用 FBO 复用，避免每帧创建/销毁")
        print("   - 降低人脸检测频率（动态间隔）")
        print()
    
    if stats['render_time_data'] and calculate_stats(stats['render_time_data'])['avg'] > 16.67:
        print("2. ⏱️  渲染耗时超标:")
        print("   - 使用 RenderDoc 或 Snapdragon Profiler 分析 GPU 瓶颈")
        print("   - 减少多 Pass 渲染次数")
        print("   - 优化纹理上传（使用 PBO）")
        print()
    
    if stats['null_frame_count'] > 0:
        print("3. 🖼️  存在空帧:")
        print("   - 检查 ImageAnalysis 背压策略")
        print("   - 确保 ImageProxy 及时关闭")
        print("   - 调整 CameraX 分辨率配置")
        print()
    
    print("4. 🔧 通用优化:")
    print("   - 启用硬件加速（确认 GPU 驱动最新）")
    print("   - 减少日志输出（Release 构建禁用 Debug 日志）")
    print("   - 使用 TextureView 而非 SurfaceView（如需变换）")
    print()


def main():
    device_id = sys.argv[1] if len(sys.argv) > 1 else ""
    
    stats = parse_logcat(device_id)
    generate_report(stats)


if __name__ == "__main__":
    main()

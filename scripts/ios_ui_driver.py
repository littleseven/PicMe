#!/usr/bin/env python3
"""
iOS UI Driver - iOS 端 UI 自动化 + 双端截图对比

通过 Xcode 单元测试截图 + devicectl 拉取 + screenshot-diff 像素对比，
实现 Android/iOS UI 还原度自动验收。

核心流程：
1. xcodebuild test（单元测试 host 内 UIKit 截图 → Documents/screenshots/）
2. devicectl copy from（拉取截图到 Mac）
3. screenshot-diff.py（Android vs iOS 像素差异）

用法:
    # 截图（当前 App 状态）
    python3 scripts/ios_ui_driver.py screenshot --name settings

    # 拉取所有截图
    python3 scripts/ios_ui_driver.py pull

    # 双端对比
    python3 scripts/ios_ui_driver.py diff

    # 安装 + 启动
    python3 scripts/ios_ui_driver.py install --path "build/.../PoLang.app"
    python3 scripts/ios_ui_driver.py launch
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Optional

DEVICE_ID = "00008120-000105443AD2201E"
BUNDLE_ID = "com.mamba.picme"
PROJECT_ROOT = Path(__file__).parent.parent
IOS_APP_ROOT = PROJECT_ROOT / "iosApp"
IOS_REFERENCE_DIR = Path("/tmp/ios-ui-reference")
ANDROID_REFERENCE_DIR = Path("/tmp/android-ui-reference")
DIFF_RESULTS_DIR = Path("/tmp/ui-diff-results")


def run(cmd: str, check: bool = True, cwd: str = None) -> tuple:
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=cwd)
    if check and result.returncode != 0:
        print(f"❌ Command failed: {cmd}", file=sys.stderr)
        if result.stderr:
            print(result.stderr[:500], file=sys.stderr)
    return result.stdout.strip(), result.stderr.strip(), result.returncode


# ── 截图（通过单元测试 host）────────────────────────────────────────

def screenshot(name: str = "current") -> bool:
    """触发单元测试截图，然后拉取到 Mac。"""
    IOS_REFERENCE_DIR.mkdir(exist_ok=True)

    # Step 1: 跑截图测试
    test_name = f"PoLangTests/ScreenshotTest/testCaptureAll"
    cmd = (
        f"xcodebuild -workspace {IOS_APP_ROOT}/PoLang.xcworkspace "
        f"-scheme PoLang "
        f"-destination 'id={DEVICE_ID}' "
        f"-configuration Debug "
        f"-only-testing:{test_name} "
        f"-derivedDataPath {IOS_APP_ROOT}/build/derivedData "
        f"test 2>&1"
    )
    print(f"📸 Running screenshot test...")
    stdout, _, rc = run(cmd, check=False)
    if rc != 0 and "TEST SUCCEEDED" not in stdout:
        print(f"⚠️ Test may have issues, checking for screenshots anyway...")

    # Step 2: 拉取截图
    return pull_screenshots(target_name=name)


def pull_screenshots(target_name: str = None) -> bool:
    """从 App Documents/screenshots/ 拉取所有截图。"""
    IOS_REFERENCE_DIR.mkdir(exist_ok=True)

    # devicectl copy from appDataContainer
    cmd = (
        f"xcrun devicectl device copy from "
        f"--device {DEVICE_ID} "
        f"--source Documents/screenshots "
        f"--destination {IOS_REFERENCE_DIR} "
        f"--domain-type appDataContainer "
        f"--domain-identifier {BUNDLE_ID} 2>&1"
    )
    stdout, stderr, rc = run(cmd, check=False)

    if rc != 0:
        # 尝试列出目录看是否有截图
        print(f"⚠️ Copy failed, trying to list directory...")
        list_cmd = (
            f"xcrun devicectl device copy from "
            f"--device {DEVICE_ID} "
            f"--source Documents "
            f"--destination {IOS_REFERENCE_DIR} "
            f"--domain-type appDataContainer "
            f"--domain-identifier {BUNDLE_ID} 2>&1"
        )
        run(list_cmd, check=False)

    # 检查拉取的文件
    pulled = list(IOS_REFERENCE_DIR.glob("*.png"))
    if pulled:
        print(f"✅ Pulled {len(pulled)} screenshots:")
        for f in pulled:
            print(f"   {f}")
        return True
    else:
        print(f"❌ No screenshots found")
        return False


# ── 安装 / 启动 / 交互 ──────────────────────────────────────────

def install_app(app_path: str) -> bool:
    cmd = f"xcrun devicectl device install app --device {DEVICE_ID} \"{app_path}\""
    _, _, rc = run(cmd, check=False)
    if rc == 0:
        print(f"✅ Installed: {app_path}")
    return rc == 0


def launch_app(bundle: str = BUNDLE_ID) -> bool:
    cmd = f"xcrun devicectl device process launch --device {DEVICE_ID} {bundle}"
    _, _, rc = run(cmd, check=False)
    if rc == 0:
        print(f"✅ Launched: {bundle}")
    return rc == 0


def build_and_install() -> bool:
    """Build debug + install."""
    print("🔨 Building...")
    cmd = (
        f"xcodebuild -workspace {IOS_APP_ROOT}/PoLang.xcworkspace "
        f"-scheme PoLang "
        f"-destination 'id={DEVICE_ID}' "
        f"-configuration Debug "
        f"-derivedDataPath {IOS_APP_ROOT}/build/derivedData "
        f"build 2>&1 | tail -3"
    )
    stdout, _, rc = run(cmd, check=False)
    if rc != 0 and "BUILD SUCCEEDED" not in stdout:
        print(f"❌ Build failed")
        return False

    app_path = f"{IOS_APP_ROOT}/build/derivedData/Build/Products/Debug-iphoneos/PoLang.app"
    return install_app(app_path)


# ── 双端截图对比 ────────────────────────────────────────────────

def cross_platform_diff(android_dir: str = None, ios_dir: str = None,
                        output_dir: str = None, threshold: float = 0.80) -> list:
    """Android vs iOS 截图像素差异对比。"""
    android_path = Path(android_dir) if android_dir else ANDROID_REFERENCE_DIR
    ios_path = Path(ios_dir) if ios_dir else IOS_REFERENCE_DIR
    out_path = Path(output_dir) if output_dir else DIFF_RESULTS_DIR
    out_path.mkdir(exist_ok=True)

    diff_script = PROJECT_ROOT / "scripts" / "screenshot-diff.py"

    # 收集 Android 截图
    android_files = sorted(android_path.glob("*.png"))
    ios_files = sorted(ios_path.glob("*.png"))

    if not android_files:
        print(f"❌ No Android screenshots in {android_path}")
        return []
    if not ios_files:
        print(f"❌ No iOS screenshots in {ios_path}")
        return []

    print(f"\n{'='*60}")
    print(f"Cross-Platform UI Diff: {len(android_files)} Android vs {len(ios_files)} iOS")
    print(f"Threshold: {threshold:.0%}")
    print(f"{'='*60}\n")

    results = []
    for android_img in android_files:
        # 模糊匹配：找文件名包含相同关键词的 iOS 截图
        keywords = android_img.stem.lower().replace("-", " ").replace("_", " ").split()
        best_ios = None
        best_score = 0
        for ios_img in ios_files:
            ios_name = ios_img.stem.lower()
            score = sum(1 for kw in keywords if kw in ios_name)
            if score > best_score:
                best_score = score
                best_ios = ios_img

        if not best_ios or best_score == 0:
            # Skip if no match
            continue

        diff_output = str(out_path / f"diff_{android_img.stem}.png")

        cmd = (
            f"python3 \"{diff_script}\" "
            f"--baseline \"{android_img}\" "
            f"--current \"{best_ios}\" "
            f"--threshold {threshold} "
            f"--output \"{diff_output}\" "
            f"--report 2>&1"
        )
        stdout, _, _ = run(cmd, check=False)

        # Parse similarity
        similarity = 0.0
        try:
            import re
            m = re.search(r"similarity[:\s=]+([\d.]+)", stdout)
            if m:
                similarity = float(m.group(1))
                if similarity > 1:
                    similarity /= 100  # Percentage to ratio
        except:
            pass

        status = "✅ PASS" if similarity >= threshold else "❌ DIFF"
        results.append({
            "android": android_img.name,
            "ios": best_ios.name,
            "similarity": f"{similarity:.1%}",
            "status": status,
        })
        print(f"  {status} {android_img.stem}: {similarity:.1%}")

    passed = sum(1 for r in results if "PASS" in r["status"])
    print(f"\n{'='*60}")
    print(f"Results: {passed}/{len(results)} pages passed (≥{threshold:.0%})")
    print(f"{'='*60}")

    # Save report
    report_path = out_path / "report.json"
    with open(report_path, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"Report: {report_path}")

    return results


# ── CLI ─────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="iOS UI Driver — screenshot + diff + install",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Build + install
  %(prog)s build

  # Launch app
  %(prog)s launch

  # Take screenshot (runs unit test → pulls to /tmp/ios-ui-reference/)
  %(prog)s screenshot --name settings

  # Pull existing screenshots from device
  %(prog)s pull

  # Compare Android vs iOS
  %(prog)s diff

  # Full flow: build → launch → screenshot → diff
  %(prog)s full-check
        """)
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("build", help="Build + install to device")
    sub.add_parser("launch", help="Launch app")
    p_install = sub.add_parser("install", help="Install existing build")
    p_install.add_argument("--path", required=True)

    p_ss = sub.add_parser("screenshot", help="Screenshot via unit test + pull")
    p_ss.add_argument("--name", default="current")

    sub.add_parser("pull", help="Pull screenshots from device Documents")

    p_diff = sub.add_parser("diff", help="Compare Android vs iOS screenshots")
    p_diff.add_argument("--android-dir", default=None)
    p_diff.add_argument("--ios-dir", default=None)
    p_diff.add_argument("--threshold", type=float, default=0.80)

    sub.add_parser("full-check", help="Build → launch → screenshot → diff")

    args = parser.parse_args()

    if args.command == "build":
        build_and_install()

    elif args.command == "launch":
        launch_app()

    elif args.command == "install":
        install_app(args.path)

    elif args.command == "screenshot":
        screenshot(args.name)

    elif args.command == "pull":
        pull_screenshots()

    elif args.command == "diff":
        cross_platform_diff(
            android_dir=args.android_dir,
            ios_dir=args.ios_dir,
            threshold=args.threshold,
        )

    elif args.command == "full-check":
        if build_and_install():
            launch_app()
            time.sleep(3)
            screenshot("current")
            cross_platform_diff()

    else:
        parser.print_help()


if __name__ == "__main__":
    main()

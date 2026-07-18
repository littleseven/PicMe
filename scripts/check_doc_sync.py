#!/usr/bin/env python3
"""
Document Sync Guardian - 文档一致性自动检查器
检查 PoLang 三层文档体系中的不一致问题：
1. 对已删除文件的引用
2. "进行中" vs "已落地" 状态标记不一致
3. 无效的内部链接
"""

import os
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent

# 已删除但仍可能被引用的文档
DELETED_FILES = {
    "Analysis_Report.md",
    "docs/GPU_PHOTO_IMPLEMENTATION_GUIDE.md",
    "docs/GPU_PHOTO_MAJOR_CHANGES.md",
    "docs/audit_report_20260503.md",
    "docs/MEDIAPIPE_468_COMPLETE_REFERENCE.md",
}

# 状态标记正则
STATUS_PATTERNS = [
    (r"\(2026-0\d 进行中\)", "进行中"),
    (r"\[ROADMAP\].*进行中", "进行中"),
]

# 需要同步状态标记的文件
SYNC_FILES = [
    "PRODUCT.md",
    "docs/01-PRODUCT/FEATURES.md",
    "docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md",
    "beauty-engine/AGENTS.md",
    "README.md",
]


def check_deleted_file_references() -> list:
    """检查对已删除文件的引用"""
    issues = []
    md_files = list(PROJECT_ROOT.rglob("*.md"))

    for md_file in md_files:
        # 跳过 .git 和 temp/gpupixel
        rel_path = md_file.relative_to(PROJECT_ROOT)
        if ".git" in str(rel_path) or "temp/gpupixel" in str(rel_path):
            continue

        content = md_file.read_text(encoding="utf-8")
        for deleted in DELETED_FILES:
            if deleted in content:
                issues.append(
                    f"  [无效引用] {rel_path}: 引用了已删除的 '{deleted}'"
                )

    return issues


def check_status_inconsistency() -> list:
    """检查 '进行中' 状态标记是否存在于已落地的功能描述中"""
    issues = []
    keywords = ["拍照 GPU 化", "GPU 离屏渲染拍照", "PhotoProcessorImpl"]

    for filename in SYNC_FILES:
        filepath = PROJECT_ROOT / filename
        if not filepath.exists():
            continue

        content = filepath.read_text(encoding="utf-8")
        lines = content.split("\n")

        for i, line in enumerate(lines, 1):
            # 如果行包含关键词且包含"进行中"
            has_keyword = any(kw in line for kw in keywords)
            has_in_progress = "进行中" in line
            if has_keyword and has_in_progress:
                issues.append(
                    f"  [状态不一致] {filename}:{i}: '{line.strip()[:80]}...'"
                )

    return issues


def check_broken_links() -> list:
    """检查内部 Markdown 链接是否指向存在的文件"""
    issues = []
    md_files = [
        f for f in PROJECT_ROOT.rglob("*.md")
        if ".git" not in str(f)
        and "temp/gpupixel" not in str(f)
        and ".lingma/skills/" not in str(f)
        and ".kimi/skills/" not in str(f)
        and ".openclaw/skills/" not in str(f)
    ]

    link_pattern = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")

    for md_file in md_files:
        rel_path = md_file.relative_to(PROJECT_ROOT)
        content = md_file.read_text(encoding="utf-8")
        base_dir = md_file.parent

        for match in link_pattern.finditer(content):
            link_target = match.group(2)
            # 只检查相对路径的 .md 链接
            if link_target.startswith("http") or link_target.startswith("#"):
                continue
            if not link_target.endswith(".md"):
                continue

            target_path = base_dir / link_target
            if not target_path.exists():
                issues.append(
                    f"  [断裂链接] {rel_path}: '{link_target}' 不存在"
                )

    return issues


def main():
    print("🤖 Document Sync Guardian")
    print("=" * 50)

    all_issues = []

    print("\n🔍 检查 1: 对已删除文件的引用...")
    issues = check_deleted_file_references()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 无无效引用")

    print("\n🔍 检查 2: 状态标记一致性...")
    issues = check_status_inconsistency()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 状态标记一致")

    print("\n🔍 检查 3: 内部链接有效性...")
    issues = check_broken_links()
    if issues:
        all_issues.extend(issues)
        print(f"   ⚠️  发现 {len(issues)} 个问题")
        for issue in issues:
            print(issue)
    else:
        print("   ✅ 所有链接有效")

    print("\n" + "=" * 50)
    if all_issues:
        print(f"❌ 共发现 {len(all_issues)} 个文档一致性问题")
        sys.exit(1)
    else:
        print("🎉 文档一致性检查全部通过！")
        sys.exit(0)


if __name__ == "__main__":
    main()

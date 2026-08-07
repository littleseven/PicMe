#!/usr/bin/env python3
"""
批量生成 tag_translations.json 的辅助脚本。

用法（示例）：
    python scripts/generate_tag_translations.py \
        --input androidApp/src/main/assets/controlled_vocab.json \
        --output androidApp/src/main/assets/tag_translations.json

当前实现：
    读取 controlled_vocab.json 中的中文 canonical 词，
    合并 scripts/tag_translation_overrides.json 中的人工校对覆盖项，
    输出 zh_to_en / en_to_zh / en_synonyms 三份映射。

注意：
    本脚本仅做框架占位。完整实现需要接入离线/本地翻译能力或人工维护覆盖表，
    禁止把用户照片 TAG 发送到云端翻译 API（遵守 PRIVACY 红线）。
"""

import argparse
import json
import os
from pathlib import Path


def load_json(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path: Path, data: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def collect_chinese_vocab(controlled: dict) -> set:
    """从 controlled_vocab.json 的各分类数组中收集中文 canonical 词。"""
    keys = [
        "scene", "activity", "objects", "atmosphere", "people",
        "clothing", "animal", "food_drink", "architecture", "nature", "transport"
    ]
    words = set()
    for key in keys:
        for word in controlled.get(key, []):
            if isinstance(word, str) and word.strip():
                words.add(word.strip())
    # 同义词映射中的 key 也加入
    for synonym in controlled.get("synonyms", {}).keys():
        if isinstance(synonym, str) and synonym.strip():
            words.add(synonym.strip())
    return words


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate tag_translations.json")
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("androidApp/src/main/assets/controlled_vocab.json"),
        help="Path to controlled_vocab.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("androidApp/src/main/assets/tag_translations.json"),
        help="Output path for tag_translations.json",
    )
    parser.add_argument(
        "--overrides",
        type=Path,
        default=Path("scripts/tag_translation_overrides.json"),
        help="Path to manual translation overrides",
    )
    args = parser.parse_args()

    controlled = load_json(args.input)
    chinese_words = sorted(collect_chinese_vocab(controlled))

    overrides = {}
    if args.overrides.exists():
        overrides = load_json(args.overrides)

    zh_to_en = {}
    en_to_zh = {}
    en_synonyms = {}

    # TODO: 接入离线/本地翻译或读取完整覆盖表
    # 当前仅输出覆盖表中已存在的映射 + 保留空位便于后续补全
    for word in chinese_words:
        if word in overrides.get("zh_to_en", {}):
            en = overrides["zh_to_en"][word]
            zh_to_en[word] = en
            en_to_zh[en] = word

    for key, value in overrides.get("en_synonyms", {}).items():
        en_synonyms[key] = value

    result = {
        "zh_to_en": zh_to_en,
        "en_to_zh": en_to_zh,
        "en_synonyms": en_synonyms,
        "_meta": {
            "total_chinese_words": len(chinese_words),
            "translated_count": len(zh_to_en),
            "note": "This is a starter file. Extend overrides to improve coverage."
        }
    }

    save_json(args.output, result)
    print(f"Wrote {args.output} with {len(zh_to_en)} translations out of {len(chinese_words)} words.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
将自动生成的 ML Kit 标签中文翻译合并进 androidApp/src/main/assets/tag_translations.json。

用法：
    python scripts/merge_ml_kit_translations.py
"""

import json
from pathlib import Path


def load_json(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path: Path, data: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def main() -> None:
    assets_dir = Path("androidApp/src/main/assets")
    translations_path = assets_dir / "tag_translations.json"
    ml_kit_path = Path("scripts/ml_kit_labels_zh.json")

    translations = load_json(translations_path)
    ml_kit = load_json(ml_kit_path)

    zh_to_en = dict(translations.get("zh_to_en", {}))
    en_to_zh = dict(translations.get("en_to_zh", {}))
    en_synonyms = dict(translations.get("en_synonyms", {}))

    added_zh_to_en = 0
    added_en_to_zh = 0

    for en_label, zh_label in ml_kit.items():
        # 只添加尚未存在的中文->英文映射；已存在的保留原值（人工校对优先）
        if zh_label not in zh_to_en:
            zh_to_en[zh_label] = en_label
            added_zh_to_en += 1

        # 英文->中文：保留已有值，除非没有
        if en_label not in en_to_zh:
            en_to_zh[en_label] = zh_label
            added_en_to_zh += 1

    result = {
        "zh_to_en": dict(sorted(zh_to_en.items())),
        "en_to_zh": dict(sorted(en_to_zh.items())),
        "en_synonyms": dict(sorted(en_synonyms.items())),
        "_meta": {
            "note": "Merged with ML Kit Image Labeler default model labels (430 labels).",
            "ml_kit_added_zh_to_en": added_zh_to_en,
            "ml_kit_added_en_to_zh": added_en_to_zh,
            "total_zh_to_en": len(zh_to_en),
            "total_en_to_zh": len(en_to_zh),
        }
    }

    save_json(translations_path, result)
    print(f"Updated {translations_path}")
    print(f"  Added {added_zh_to_en} zh_to_en mappings from ML Kit")
    print(f"  Added {added_en_to_zh} en_to_zh mappings from ML Kit")
    print(f"  Total zh_to_en: {len(zh_to_en)}, en_to_zh: {len(en_to_zh)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
I18N 五语资源同步检查工具
检查 values、values-zh-rCN、values-zh-rTW、values-es、values-fr 中的字符串资源是否一致
"""

import xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict
import sys


def parse_strings_file(file_path: Path) -> dict[str, str]:
    """解析 strings.xml 文件，返回 {name: value} 字典"""
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()
        
        strings = {}
        for string_elem in root.findall('string'):
            name = string_elem.get('name')
            value = string_elem.text or ''
            if name:
                strings[name] = value
        
        return strings
    except Exception as e:
        print(f"❌ 解析失败 {file_path}: {e}")
        return {}


def check_i18n_sync(project_root: Path) -> dict:
    """检查五语资源同步情况"""
    
    # 定义五个语言的目录
    locales = {
        'en': project_root / 'androidApp/src/main/res/values/strings.xml',
        'zh-CN': project_root / 'androidApp/src/main/res/values-zh-rCN/strings.xml',
        'zh-TW': project_root / 'androidApp/src/main/res/values-zh-rTW/strings.xml',
        'es': project_root / 'androidApp/src/main/res/values-es/strings.xml',
        'fr': project_root / 'androidApp/src/main/res/values-fr/strings.xml',
    }
    
    # 解析所有语言文件
    locale_strings = {}
    for locale, file_path in locales.items():
        if file_path.exists():
            locale_strings[locale] = parse_strings_file(file_path)
            print(f"✅ 加载 {locale}: {len(locale_strings[locale])} 个字符串")
        else:
            print(f"❌ 文件不存在: {file_path}")
            locale_strings[locale] = {}
    
    # 收集所有唯一的字符串键
    all_keys = set()
    for strings in locale_strings.values():
        all_keys.update(strings.keys())
    
    # 分析差异
    missing_in_locale = defaultdict(list)  # {locale: [missing_keys]}
    inconsistent_values = []  # [(key, {locale: value})]
    
    for key in sorted(all_keys):
        present_locales = {}
        for locale, strings in locale_strings.items():
            if key in strings:
                present_locales[locale] = strings[key]
        
        # 检查缺失
        for locale in locales.keys():
            if locale not in present_locales:
                missing_in_locale[locale].append(key)
        
        # 检查各语言值完全相同（疑似未翻译；仅当所有语言都存在时）
        if len(present_locales) == len(locales):
            values = list(present_locales.values())
            if len(set(values)) == 1:
                inconsistent_values.append((key, present_locales))
    
    return {
        'total_keys': len(all_keys),
        'missing': dict(missing_in_locale),
        'inconsistent': inconsistent_values,
        'locale_counts': {
            locale: len(strings) 
            for locale, strings in locale_strings.items()
        }
    }


def generate_report(analysis: dict) -> str:
    """生成检查报告"""
    
    report = f"""# 🌍 I18N 五语资源同步检查报告

**检查时间**: {Path.cwd()}  
**总字符串数**: {analysis['total_keys']}  

## 📊 各语言字符串数量

"""
    
    for locale, count in analysis['locale_counts'].items():
        report += f"- **{locale}**: {count} 个\n"
    
    report += "\n"
    
    # 缺失项
    has_missing = any(keys for keys in analysis['missing'].values())
    if has_missing:
        report += "## ❌ 缺失的字符串\n\n"
        for locale, keys in analysis['missing'].items():
            if keys:
                report += f"### {locale} 缺少 {len(keys)} 个字符串:\n\n"
                for key in keys[:20]:  # 只显示前 20 个
                    report += f"- `{key}`\n"
                if len(keys) > 20:
                    report += f"\n... 还有 {len(keys) - 20} 个\n"
                report += "\n"
    else:
        report += "## ✅ 无缺失字符串\n\n"
    
    # 疑似未翻译项（各语言值完全相同）
    if analysis['inconsistent']:
        report += f"## ⚠️  各语言值完全相同的字符串 ({len(analysis['inconsistent'])} 个)\n\n"
        report += "> 注意：各语言值完全相同疑似未翻译的直接复制（品牌名/纯数字/纯符号等可豁免），仅用于审查\n\n"

        for key, values in analysis['inconsistent'][:10]:  # 只显示前 10 个
            report += f"### `{key}`\n\n"
            for locale, value in values.items():
                # 截断过长的值
                display_value = value[:50] + '...' if len(value) > 50 else value
                report += f"- **{locale}**: `{display_value}`\n"
            report += "\n"

        if len(analysis['inconsistent']) > 10:
            report += f"\n... 还有 {len(analysis['inconsistent']) - 10} 个疑似未翻译项\n"
    else:
        report += "## ✅ 无疑似未翻译（各语言值全同）问题\n\n"
    
    # 建议
    report += "## 📝 修复建议\n\n"
    
    if has_missing:
        report += "1. **补充缺失字符串**: 为每个缺失的键添加对应语言的翻译\n"
        report += "2. **使用占位符**: 如果某些语言不需要特定字符串，考虑使用通用文案\n\n"
    
    if analysis['inconsistent']:
        report += "3. **审查全同项**: 确认是否为漏翻的直接复制（品牌名/纯数字等可豁免）\n"
        report += "4. **统一术语**: 确保关键术语在各语言中表达一致\n\n"
    
    if not has_missing and not analysis['inconsistent']:
        report += "✅ 五语资源完全同步，无需修复！\n\n"
    
    report += "---\n\n"
    report += "**检查工具**: `skills/doc-sync-guardian/scripts/check-i18n-sync.py`\n"
    
    return report


def main():
    # 查找项目根目录
    script_dir = Path(__file__).parent
    project_root = script_dir.parent.parent.parent
    
    print("🌍 开始 I18N 五语资源同步检查...")
    print(f"📁 项目根目录: {project_root}")
    print()
    
    # 执行检查
    analysis = check_i18n_sync(project_root)
    
    # 生成报告
    report = generate_report(analysis)
    
    # 输出到控制台
    print(report)
    
    # 保存到文件
    from datetime import datetime
    report_file = project_root / 'docs' / f"i18n_sync_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.md"
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(report, encoding='utf-8')
    
    print(f"\n📄 详细报告已保存到: {report_file}")
    
    # 如果有问题，返回非零退出码
    has_issues = any(analysis['missing'].values()) or analysis['inconsistent']
    sys.exit(1 if has_issues else 0)


if __name__ == "__main__":
    main()

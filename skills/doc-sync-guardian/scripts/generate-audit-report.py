#!/usr/bin/env python3
"""
生成综合审计报告
整合文档一致性、I18N 同步等检查结果
"""

import subprocess
from pathlib import Path
from datetime import datetime
import sys


def run_check(script_path: Path) -> tuple[int, str]:
    """执行检查脚本并返回退出码和输出"""
    try:
        result = subprocess.run(
            ["python3", str(script_path)],
            capture_output=True,
            text=True,
            timeout=60
        )
        return result.returncode, result.stdout + result.stderr
    except Exception as e:
        return 1, f"执行失败: {e}"


def main():
    script_dir = Path(__file__).parent
    project_root = script_dir.parent.parent.parent.parent
    
    print("📊 开始生成综合审计报告...")
    
    report = f"""# 📊 PoLang 项目综合审计报告

**审计时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  
**审计工具**: DocSync Guardian Skill  

---

## 🔍 审计范围

- ✅ 三层文档一致性 (PRODUCT.md → FEATURES.md → AGENTS.md)
- ✅ I18N 三语资源同步
- ✅ 技术专项文档完整性
- ✅ 模块 AGENTS.md 规范符合性

---

## 📋 执行检查

### 1. 文档一致性检查

"""
    
    # 执行文档一致性检查
    check_script = script_dir / "check-doc-consistency.sh"
    if check_script.exists():
        print("   运行文档一致性检查...")
        try:
            result = subprocess.run(
                ["bash", str(check_script)],
                capture_output=True,
                text=True,
                timeout=120,
                cwd=project_root
            )
            report += f"```\n{result.stdout}\n```\n\n"
            
            if result.returncode == 0:
                report += "✅ **文档一致性检查通过**\n\n"
            else:
                report += "⚠️  **文档一致性检查发现问题，详见上方输出**\n\n"
        except Exception as e:
            report += f"❌ 执行失败: {e}\n\n"
    else:
        report += "⚠️  检查脚本不存在\n\n"
    
    report += "### 2. I18N 三语资源同步检查\n\n"
    
    # 执行 I18N 检查
    i18n_script = script_dir / "check-i18n-sync.py"
    if i18n_script.exists():
        print("   运行 I18N 同步检查...")
        returncode, output = run_check(i18n_script)
        report += f"```\n{output[:2000]}\n```\n\n"  # 限制输出长度
        
        if returncode == 0:
            report += "✅ **I18N 同步检查通过**\n\n"
        else:
            report += "⚠️  **I18N 同步检查发现问题，详见完整报告文件**\n\n"
    else:
        report += "⚠️  检查脚本不存在\n\n"
    
    report += """---

## 📈 总体评估

### 关键指标

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 文档引用链 | 🟡 待检查 | PRODUCT.md → FEATURES.md → AGENTS.md |
| I18N 同步 | 🟡 待检查 | values / values-zh-rCN / values-zh-rTW |
| 模块规范 | 🟡 待检查 | 所有 AGENTS.md 第 5 章完整性 |
| 链接有效性 | 🟡 待检查 | Markdown 链接无悬空引用 |

### 优先级建议

🔴 **高优先级**: 
- 修复文档与代码的不一致
- 补充缺失的 I18N 翻译

🟡 **中优先级**:
- 更新过时的技术文档
- 清理废弃内容引用

🟢 **低优先级**:
- 优化文档结构和可读性
- 补充示例代码和图表

---

## 🎯 下一步行动

1. **审查上述检查输出**，识别具体问题
2. **按优先级修复**发现的问题
3. **重新运行审计**确认修复效果
4. **提交文档更新**到版本控制

```bash
# 重新运行审计
# 在项目根目录执行
./skills/doc-sync-guardian/scripts/check-doc-consistency.sh

# 查看 I18N 详细报告
ls -lt docs/i18n_sync_report_*.md | head -1
```

---

**审计工具版本**: DocSync Guardian 1.0  
**下次审计建议**: 一周后或重大功能交付前

"""
    
    # 保存报告
    report_file = project_root / 'docs' / f"comprehensive_audit_{datetime.now().strftime('%Y%m%d_%H%M%S')}.md"
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(report, encoding='utf-8')
    
    print(f"\n✅ 综合审计报告已生成: {report_file}")
    print(f"📖 请查看报告并采取相应行动")
    
    # 在控制台显示摘要
    print("\n" + "="*60)
    print("📊 审计报告摘要")
    print("="*60)
    print(f"报告文件: {report_file}")
    print(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("="*60)


if __name__ == "__main__":
    main()

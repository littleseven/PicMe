#!/usr/bin/env python3
"""
文档同步更新模板生成器
用法: python3 sync-doc-template.py --commit-hash abc123 --module Camera
"""

import argparse
import subprocess
import sys
from pathlib import Path
from datetime import datetime


def get_changed_files(commit_hash: str) -> list[str]:
    """获取指定 commit 变更的文件列表"""
    try:
        result = subprocess.run(
            ["git", "diff", "--name-only", f"{commit_hash}~1", commit_hash],
            capture_output=True, text=True, check=True
        )
        return [f for f in result.stdout.strip().split('\n') if f]
    except subprocess.CalledProcessError as e:
        print(f"❌ Git 命令执行失败: {e}")
        sys.exit(1)


def determine_module(file_path: str) -> str | None:
    """根据文件路径判断所属模块"""
    module_mapping = {
        "features/camera": "Camera",
        "features/gallery": "Gallery",
        "features/editor": "Editor",
        "features/settings": "Settings",
        "beauty-engine": "BeautyEngine",
        "gpupixel": "GPUPixel",
        "data": "Data",
        "core": "Core",
    }
    
    for key, module in module_mapping.items():
        if key in file_path:
            return module
    
    return None


def analyze_changes(changed_files: list[str]) -> dict:
    """分析变更类型和影响范围"""
    analysis = {
        "modules": set(),
        "has_product_change": False,
        "has_features_change": False,
        "has_tech_spec_change": False,
        "change_types": set(),
    }
    
    for file in changed_files:
        # 判断模块
        module = determine_module(file)
        if module:
            analysis["modules"].add(module)
        
        # 判断文档变更
        if file.endswith("PRODUCT.md"):
            analysis["has_product_change"] = True
            analysis["change_types"].add("product")
        elif file.endswith("FEATURES.md"):
            analysis["has_features_change"] = True
            analysis["change_types"].add("features")
        elif "_TECH_SPEC.md" in file:
            analysis["has_tech_spec_change"] = True
            analysis["change_types"].add("tech_spec")
        
        # 判断代码变更类型
        if file.endswith(".kt") or file.endswith(".java"):
            if "shader" in file.lower() or "glsl" in file.lower():
                analysis["change_types"].add("shader")
            elif "repository" in file.lower():
                analysis["change_types"].add("data_layer")
            elif "viewmodel" in file.lower():
                analysis["change_types"].add("ui_logic")
            elif "ui" in file.lower() or "screen" in file.lower():
                analysis["change_types"].add("ui_component")
    
    return analysis


def generate_update_draft(analysis: dict, commit_hash: str) -> str:
    """生成文档更新草案"""
    draft = f"""# 📝 文档同步更新草案

**生成时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  
**Commit Hash**: `{commit_hash}`  
**变更文件数**: {len(analysis['modules'])} 个模块  

---

## 📊 变更分析

### 影响的模块
{chr(10).join(f"- {module}" for module in sorted(analysis['modules'])) if analysis['modules'] else '- 无'}

### 变更类型
{chr(10).join(f"- {change_type}" for change_type in sorted(analysis['change_types'])) if analysis['change_types'] else '- 无'}

---

## 🔄 需要更新的文档

"""
    
    # 为每个受影响的模块生成更新建议
    for module in sorted(analysis['modules']):
        draft += f"### {module} 模块\n\n"
        
        # 根据变更类型提供不同的更新建议
        if "shader" in analysis['change_types']:
            draft += f"""**Section 2: 技术实现规范**
- [ ] 补充 Shader 实现细节和参数说明
- [ ] 添加 GLSL 代码示例（带注释）
- [ ] 更新性能指标（渲染耗时、内存占用）

**Section 4: 检查清单**
- [ ] Shader 编译错误是否有降级处理？
- [ ] 纹理坐标映射是否正确？
- [ ] 多 Pass 渲染顺序是否符合预期？

"""
        elif "data_layer" in analysis['change_types']:
            draft += f"""**Section 2: 技术实现规范**
- [ ] 更新 Repository 层职责说明
- [ ] 补充 Room/DataStore 使用示例
- [ ] 说明数据流和状态管理

**Section 4: 检查清单**
- [ ] 数据库迁移是否正确处理版本升级？
- [ ] Flow 订阅是否在适当位置取消？
- [ ] 后台线程执行是否避免阻塞 UI？

"""
        elif "ui_component" in analysis['change_types']:
            draft += f"""**Section 2: 技术实现规范**
- [ ] 补充 Composable 组件结构说明
- [ ] 更新状态管理方案（remember/StateFlow）
- [ ] 说明动画和过渡效果实现

**Section 4: 检查清单**
- [ ] 可访问性 (contentDescription) 是否完整？
- [ ] 深色/浅色模式适配是否正确？
- [ ] 多语言文案是否提取到 strings.xml？

"""
        else:
            draft += f"""**Section 2: 技术实现规范**
- [ ] 补充新功能的技术实现细节
- [ ] 添加关键代码示例
- [ ] 说明与现有模块的集成方式

**Section 4: 检查清单**
- [ ] 新功能是否有完整的异常处理？
- [ ] 性能指标是否满足 PRODUCT.md 要求？
- [ ] I18N 三语资源是否同步更新？

"""
        
        draft += f"**Section 5: 产品对照**\n"
        draft += f"- [ ] 更新产品指标对应关系\n"
        draft += f"- [ ] 补充技术决策记录\n\n"
        draft += "---\n\n"
    
    # 如果有产品文档变更
    if analysis['has_product_change']:
        draft += """### PRODUCT.md
- [ ] 确认新增功能的验收指标已定义
- [ ] 检查性能红线是否明确（如 < 500ms）
- [ ] 验证隐私和国际化要求是否标注

"""
    
    # 如果有特性文档变更
    if analysis['has_features_change']:
        draft += """### docs/01-PRODUCT/FEATURES.md
- [ ] 交互流程描述是否完整？
- [ ] 用户体验目标是否量化？
- [ ] 视觉风格规范是否与 Design System 一致？

"""
    
    # 如果有技术专项文档变更
    if analysis['has_tech_spec_change']:
        draft += """### 技术专项文档
- [ ] 架构设计图是否更新？
- [ ] 技术方案对比是否充分？
- [ ] 风险评估和缓解措施是否完整？

"""
    
    # 添加通用检查项
    draft += """## ✅ 通用检查清单

- [ ] 三层文档术语保持一致
- [ ] 所有 markdown 链接有效
- [ ] 代码示例可以正常编译
- [ ] I18N 文案同步三语资源
- [ ] 性能指标有对应的监控日志
- [ ] 废弃内容已标记或删除

---

## 📋 执行步骤

1. **审查草案**: 根据实际变更调整上述检查项
2. **更新文档**: 按优先级依次更新各层文档
3. **验证一致性**: 运行 `./check-doc-consistency.sh`
4. **提交变更**: 
   ```bash
   git add docs/*.md app/*/AGENTS.md
   git commit -m "docs: 同步更新 [模块名] 文档
   
   - 更新 FEATURES.md 交互说明
   - 补充 AGENTS.md 技术实现
   - 完善检查清单和产品对照"
   ```

---

**备注**: 本草案由自动化工具生成，请根据实际情况调整。
"""
    
    return draft


def main():
    parser = argparse.ArgumentParser(
        description="生成文档同步更新草案",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例用法:
  python3 sync-doc-template.py --commit-hash abc123
  python3 sync-doc-template.py --commit-hash abc123 --output draft.md
        """
    )
    parser.add_argument(
        "--commit-hash",
        required=True,
        help="Git commit hash（可使用 HEAD 表示最新提交）"
    )
    parser.add_argument(
        "--output",
        default="/tmp/doc_sync_draft.md",
        help="输出文件路径（默认: /tmp/doc_sync_draft.md）"
    )
    
    args = parser.parse_args()
    
    print(f"🔍 分析 Commit: {args.commit_hash}")
    
    # 获取变更文件
    changed_files = get_changed_files(args.commit_hash)
    print(f"📄 变更文件数: {len(changed_files)}")
    
    if not changed_files:
        print("⚠️  未检测到文件变更")
        sys.exit(0)
    
    # 分析变更
    analysis = analyze_changes(changed_files)
    print(f"📦 影响模块: {', '.join(sorted(analysis['modules'])) if analysis['modules'] else '无'}")
    print(f"🔧 变更类型: {', '.join(sorted(analysis['change_types'])) if analysis['change_types'] else '无'}")
    
    # 生成草案
    draft = generate_update_draft(analysis, args.commit_hash)
    
    # 保存到文件
    output_path = Path(args.output)
    output_path.write_text(draft, encoding='utf-8')
    
    print(f"\n✅ 草案已生成: {output_path}")
    print(f"📖 请查看并调整草案内容")
    print(f"\n💡 下一步:")
    print(f"   1. 编辑草案: code {output_path}")
    print(f"   2. 根据草案更新文档")
    print(f"   3. 运行一致性检查: ./check-doc-consistency.sh")


if __name__ == "__main__":
    main()

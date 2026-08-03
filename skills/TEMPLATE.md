---
name: skill-name
description: 具体描述本 Skill 做什么、何时使用。使用第三人称，包含触发关键词。Use when working with X or when the user mentions Y.
version: 1.0.0
created: 2026-05-25
updated: 2026-05-25
maintainer: [RD] 全栈工程师
tags: [tag1, tag2, tag3]
---

# Skill 标题

> **定位**：一句话说明本 Skill 解决什么问题。
> **触发时机**：何时自动应用本 Skill。

---

## 触发条件

何时使用本 Skill？列出具体的触发场景和关键词。

## 核心原则

1. **原则一**：不可违反的铁律
2. **原则二**：关键约束
3. **原则三**：最佳实践

## 诊断流程

### Step 1: 问题识别

```bash
# 诊断命令示例
```

### Step 2: 根因定位

检查清单：
- [ ] 检查点 1
- [ ] 检查点 2
- [ ] 检查点 3

### Step 3: 修复验证

```bash
# 验证命令示例
```

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| 陷阱 1 | 具体症状 | 修复方法 |
| 陷阱 2 | 具体症状 | 修复方法 |

## 相关文件

- [相关文档](docs/XXX.md) - 文档说明
- [相关 Skill](skills/xxx/SKILL.md) - Skill 说明

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-25 | 初始版本 |

---

## Skill 编写规范（维护者必读）

### 长度控制
- **SKILL.md 正文 < 500 行**。超过则拆分代码示例到 `reference.md`。
- 使用渐进式披露：核心流程在 SKILL.md，详细代码在 reference.md。

### 代码示例
- 单个代码块不超过 30 行。
- 超过 30 行的代码应移至 `reference.md`，SKILL.md 中只保留说明和链接。

### 引用规范
- 引用其他 Skill 使用相对路径：`skills/xxx/SKILL.md`
- 引用项目文档使用相对路径：`docs/XXX.md`

### 版本管理
- 每次更新必须修改 `updated` 字段和「版本历史」表格。
- 重大结构调整应升级 minor 版本（1.0 → 1.1）。
- 内容重写或架构变更应升级 major 版本（1.x → 2.0）。

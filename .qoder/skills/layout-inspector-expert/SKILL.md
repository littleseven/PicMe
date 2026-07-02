---
name: layout-inspector-expert
description: |
  指导使用 Android Studio Layout Inspector 和 Compose 调试工具排查 UI 布局问题。当用户报告 Compose UI 异常（如组件不显示、点击无响应、布局错位、状态不更新等）时自动启用，禁止基于截图猜测修改。
version: 1.0.0
created: 2026-05-31
updated: 2026-05-31
maintainer: [RD] 全栈工程师
tags:
  - compose
  - ui
  - layout-inspector
  - debug
  - android
---


# Layout Inspector UI 排查专家

> **定位**：指导使用 Android Studio Layout Inspector 和 Compose 调试工具排查 UI 布局问题。
> **触发时机**：用户报告 Compose UI 异常（组件不显示、点击无响应、布局错位、状态不更新等）时自动启用。


> **核心原则**：所有 UI 问题必须先通过 Layout Inspector 诊断，禁止凭截图或猜测直接修改代码。

## 何时启用

- Compose 组件不显示或显示异常
- 点击/滑动交互无响应
- 布局错位、padding/margin 异常
- 状态更新但 UI 不刷新
- 重组性能问题

## 禁止行为

**禁止**：**以下行为严格禁止**：
1. 未使用 Layout Inspector 就猜测修改 padding/offset
2. 仅凭截图调整布局参数
3. 不添加日志就改动状态管理代码
4. 分多次"试试看"式修复

## 诊断流程

### Phase 1: 连接 Layout Inspector

1. 确保应用运行在 Debug 模式
2. Android Studio → Tools → Layout Inspector
3. 选择正在运行的进程 `com.mamba.picme`
4. 等待组件树加载完成

### Phase 2: 检查组件树结构

在 Layout Inspector 中确认：
- **组件是否存在**：目标组件是否在树中
- **父容器类型**：Box/Column/Row/Scaffold 等
- **Modifier 链顺序**：特别是 `clickable`、`padding`、`align` 的顺序
- **尺寸约束**：实际 width/height 与预期是否一致

**常见陷阱**：
```kotlin
// 错误：Modifier 顺序错误：padding 在 clickable 外，点击区域不包含 padding
Modifier.padding(16.dp).clickable { }

// **通过**：正确：clickable 应在 padding 内，确保点击区域包含 padding
Modifier.clickable { }.padding(16.dp)
```

### Phase 3: 检查状态绑定

1. 在 Layout Inspector 中启用 **Live Edit**（如可用）
2. 查看目标组件的 State 值
3. 确认状态值是否与预期一致

**如果状态值正确但 UI 不更新**：
- 检查是否使用了 `remember` 而非 `rememberSaveable`
- 检查状态是否在正确的层级定义
- 检查是否使用了不可变类型（如 `List` → `SnapshotStateList`）

### Phase 4: 添加关键路径日志

在交互回调和重组点添加日志：
```kotlin
// 滑杆示例
Slider(
    value = sliderValue,
    onValueChange = { newValue ->
        Log.d("PicMe:Slider", "onValueChange: $newValue")
        sliderValue = newValue
    }
)

// 检查重组
@Composable
fun MyComponent() {
    SideEffect {
        Log.d("PicMe:Recompose", "MyComponent recomposed")
    }
}
```

### Phase 5: 输出诊断报告

诊断报告必须包含：
1. Layout Inspector 组件树截图（含目标组件高亮）
2. 目标组件的 Modifier 链详情
3. 相关 State 值截图或日志
4. 根因分析

## 常见问题的 Layout Inspector 特征

| 现象 | Layout Inspector 特征 | 根因 |
|------|----------------------|------|
| 组件不显示 | 组件尺寸为 0x0 或不在树中 | 条件渲染未满足 / 被其他组件覆盖 |
| 点击无响应 | clickable Modifier 不在正确位置 | Modifier 顺序错误 / 被上层拦截 |
| 布局错位 | 实际位置与预期不符 | 父容器约束 / align 设置错误 |
| 状态不更新 | State 值已变但 UI 未刷新 | remember 未触发重组 / 状态层级错误 |
| 重组频繁 | 重组计数器快速增长 | 状态定义位置不当 / 不必要的状态依赖 |

## 修复验证

修复后必须通过以下验证：
1. Layout Inspector 确认组件树符合预期
2. 交互日志确认状态流向正确
3. 真机测试录制验证

## 相关文件

- [compose-ui-expert](/compose-ui-expert) — Compose UI 代码修复
- [ui-driver](/ui-driver) — UI Driver

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-31 | 初始版本 |

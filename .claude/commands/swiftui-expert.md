# SwiftUI 专家 Skill

> **定位**：SwiftUI 布局 / 状态 / 重组 / Preview 诊断，对标 Android compose-ui-expert。
> **触发时机**：新增 SwiftUI Screen/Component、状态不同步、重组卡顿、Preview 不一致时。

## 核心原则（可调试性，S4 全 feature 通用）

1. **单一状态源**：每 feature 一个 `ObservableObject` 持全部 UI 态；枚举优于多个 Boolean。
2. **Preview 全覆盖**：每组件 PreviewProvider + 代表性 mock 态（空 / Loading / Limited / 1000 图）。
3. **accessibilityIdentifier 全量标注**：网格 cell / 权限按钮 / 分组头带稳定标识，为 XCUITest 铺路，不靠图像识别。

## 状态管理

```swift
@MainActor
final class GalleryStore: ObservableObject {
    enum State { case loading, full([MediaItem]), limited, denied }
    @Published private(set) var state: State = .loading
    // 唯一持有者；视图只读 @Published，动作走方法
}
```

| 场景 | 选择 |
|------|------|
| 视图拥有 store | `@StateObject` |
| store 由父传入 | `@ObservedObject` |
| 环境注入 | `@EnvironmentObject` / `EnvironmentKey` |
| 简单局部值 | `@State` |

## Preview 全覆盖

```swift
#Preview("空态") { GalleryView(store: .preview(.empty)) }
#Preview("1000 图") { GalleryView(store: .preview(.thousand)) }
#Preview("Limited") { GalleryView(store: .preview(.limited)) }
```

第一环自验证，不依赖真机。

## accessibilityIdentifier（为 ui-driver 等价物铺路）

```swift
ForEach(items) { item in
    MediaCell(item: item)
        .accessibilityIdentifier("gallery.cell.\(item.id)")
}
```

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| 闭包捕获旧值 | 状态始终为初始值 | `[weak store]` 或在 `@MainActor` 方法内读 `state` |
| `@State` 跨视图共享 | 改一处不生效 | `@State` 仅局部；共享用 `@StateObject` / `@EnvironmentObject` |
| body 重组过频 | CPU 高 / 动画卡 | 拆小组件；大结构用 `EquatableView` |
| `ForEach` 无 id | 滚动错乱 / 动画错位 | `ForEach(items, id: \.stableId)` |
| 主线程阻塞 | 掉帧 | 重活 `async/await`；`@Published` 在 MainActor |

## HyperOS 视觉规范双端对标

| 规范 | iOS |
|------|-----|
| 大圆角 | `RoundedRectangle(cornerRadius: 28)` |
| 毛玻璃 | `.ultraThinMaterial` + 半透明 |
| 流体动效 | `.easeInOut` / 自定义 `UnitCurve` |
| Primary | `Color(red: 0, green: 0.898, blue: 1)`（#00E5FF） |

美颜参数默认值 / 滑杆范围与 Android `BeautySettings` 完全一致（S5，shared 纯类型保证）。

## 相关文件

- [compose-ui-expert](/compose-ui-expert) — Android Compose 对照
- [ios-i18n-validator](/ios-i18n-validator) — 文案三语
- spec：`docs/superpowers/specs/2026-08-08-ios-app-skeleton-design.md` §4.4

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5.2–5.4） |

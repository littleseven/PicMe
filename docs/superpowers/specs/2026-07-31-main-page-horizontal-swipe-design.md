# 主页面横滑切换设计文档

> **日期**：2026-07-31  
> **模块**：`:app`  
> **相关页面**：Camera / Gallery / Chat / People  

## 1. 目标

在竖屏状态下，为四个主页面补充**左右边缘横滑切换**手势，作为底部悬浮 Tab 点击的替代操作。四页顺序为：

| 索引 | 路由 | 页面 |
|------|------|------|
| 0 | `Screen.Camera` | 相机 |
| 1 | `Screen.Gallery` | 相册（默认首页） |
| 2 | `Screen.Chat` | Chat |
| 3 | `Screen.People` | 人物 |

- 左滑（→）切换到下一页；右滑（←）切换到上一页。
- 到达边界时循环：在相机页继续左滑 → 人物页；在人物页继续右滑 → 相机页。

## 2. 设计决策

### 2.1 采用方案 A：保留独立路由 + 边缘横滑跳转

不改造现有 `NavHost` 与 `SceneManager/DisposableEffect` 生命周期，仅在每个主页面的根布局外套一个 Compose 层手势包装器。

**原因**：
- 风险最低，不侵入 Camera/Chat 的沉浸式代码。
- 不需要迁移 Capability 注册、系统栏管理等生命周期逻辑。
- 与现有底部悬浮 Tab 完全兼容。

### 2.2 只在屏幕左右边缘响应横滑

触发区域限定在距屏幕左右边缘约 `24dp` 的窄带内，避免与以下内部横向手势冲突：

- 相册大图 `MediaPager` 的横向滑动
- Chat 中的图片/图表预览
- 相机预览区的对焦/变焦/滑动操作

### 2.3 避开系统返回手势区

Android 系统返回手势同样占用屏幕左右边缘。为避免冲突：

- 使用 `WindowInsets.systemGestures` 获取系统手势区宽度。
- 实际检测区域向屏幕内侧偏移一个安全距离（系统手势区 + 内缩 8-12dp）。
- Android 10+ 可配合 `Modifier.systemGestureExclusion` 做兜底。

## 3. 核心组件

新增文件：`app/src/main/java/com/mamba/picme/features/common/components/MainPageSwipeWrapper.kt`

```kotlin
@Composable
fun MainPageSwipeWrapper(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)
```

职责：
- 在左右边缘检测水平拖动。
- 当拖动超过阈值时，计算循环后的目标索引并调用 `onPageChanged`。
- `enabled = false` 时完全放行手势。

## 4. 挂点与启用条件

| 页面 | 挂点 | 禁用场景 |
|------|------|----------|
| `GalleryScreen` | `FloatingBottomTab` 同级的根 `Box` | `selectedMediaIndex != null`（媒体预览打开） |
| `CameraScreen` | 权限通过后的 `CameraContent` 外层 | 无（相机预览内部手势由边缘区域隔离） |
| `ChatScreen` | `Scaffold` 内容区外层 | 全屏图片/图表/轮播预览打开时 |
| `PersonScreen` | `Scaffold` 内容区外层 | 无 |

## 5. 导航行为

在 `MainActivity.kt` 维护主页面顺序：

```kotlin
private val mainPages = listOf(
    Screen.Camera,
    Screen.Gallery,
    Screen.Chat,
    Screen.People
)
```

切换 lambda：

```kotlin
val switchMainPage: (Int) -> Unit = { index ->
    navController.navigate(mainPages[index].route) {
        launchSingleTop = true
        popUpTo(Screen.Gallery.route) { saveState = true }
    }
}
```

- 保持每个主页面单实例。
- 页面状态通过 `saveState = true` 恢复。
- 现有 `NavHost` 的 slide/fade 转场仍然生效。

## 6. 对沉浸式效果的影响

- `MainPageSwipeWrapper` 仅处理 Compose pointer 事件，不修改 `Window`、不调整 `WindowInsets`、不调用 `systemBarsBehavior`。
- `CameraScreen` 与 `ChatScreen` 现有的沉浸式 `DisposableEffect` 保持原样。
- 手势区域避开系统手势区，系统返回手势仍可正常使用。

## 7. 底部悬浮 Tab

保持只在 `GalleryScreen` 显示 `FloatingBottomTab`，入口顺序与现有保持一致：

1. 相机
2. Chat
3. 打标控制
4. 人物

本次不强制要求 Tab 高亮当前页；如需，可在后续迭代中为 `FloatingBottomTab` 增加 selected index 状态。

## 8. 验收标准

- [ ] 在相机/相册/Chat/人物四页均可在竖屏下通过左右边缘横滑切换页面。
- [ ] 页面顺序正确，且首尾循环。
- [ ] 相册大图预览、Chat 全屏预览打开时，横滑切换被禁用。
- [ ] 不破坏 Camera/Chat 的沉浸式系统栏隐藏效果。
- [ ] 不阻断系统返回手势。
- [ ] 编译通过，无新增 lint/detekt 告警。

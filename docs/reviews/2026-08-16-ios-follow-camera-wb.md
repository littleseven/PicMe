# iOS Follow Gap 报告：相机白平衡 GL 色温映射对等

- 日期：2026-08-16
- 分支：`feat/ios-camera-wb`（worktree `.worktrees/feat-ios-camera-wb`，基于 main 553fab36e）
- 实现：K3 主 agent；审查：GLM review 子 agent（交叉）
- 契约基线：`docs/08-UI-SPECS/screens/camera.yaml` §13 / §21 `white_balance`

## 改动清单（3 文件）

| 文件 | 改动 |
|------|------|
| `iosApp/PoLang/Features/Camera/Beauty/Shaders/lut.metal` | 色温系数 0.01 → 0.05（与 Android colorgrade.glsl 同源；🔴 修复「参考副本已同步、编译产物未跟」的强度偏差） |
| `iosApp/PoLang/Features/Camera/Preview/CameraPreviewView.swift` | WB chip → Kelvin 映射写入 temperature；手动拖色温滑杆退预设（chip 回 Auto）；`camera_wb_mode`（@AppStorage）+ `camera_color_temperature`（UserDefaults）持久化与恢复；色温默认态显示 `--`（偏差 ≤50K） |
| `docs/08-UI-SPECS/screens/camera.yaml` | §21 white_balance 条目回写（lut.metal 为实际编译产物、glsl 为参考副本的澄清） |

## 审查结论（GLM → K3 交叉）

- 🔴 pbxproj 误删 `face_landmarker.task` 资源引用（worktree 缺 gitignored 二进制的副产物）→ **已修**：pbxproj 还原 main + 模型文件拷入 worktree
- 🟡 色温默认态恒显 5000K vs Android `--` → **已修**（`abs(t-5000)>50` 才显数值）
- ✅ 系数完整性 / chip 与滑杆写路径隔离 / 持久化无环 / 映射逐项一致 / I18N 三语齐全——审查确认无问题

## ✅ 自动通过

- `shared:jvmTest` PASS（无 shared 变更）
- iOS Debug 真机编译 PASS、安装 PASS、启动 PASS（iPhone 15, iOS 26.6）

## ⚠️ 待真机终验（用户）

1. **WB 视觉效果**：相机页 → 专业 → 点「白炽灯」应可见画面转暖（R/B 偏移 5 倍于改版前）；「晴天」轻微转冷
2. **手动拖色温**：滑杆拖动后 chip 回「自动」，数值不回弹
3. **持久化**：划出相机页/重启 App 后 WB 与色温保持
4. 注：截图自动采集不可用（iOS 26 移除 screenshotr 服务；pymobiledevice3 screenshot 为 deprecated API 且崩溃）——视觉验收只能人工

## 📋 技术债 / 台账登记

1. **iOS 相机状态记忆全字段**（CameraMemoryState 11 字段）未对等，本次仅 WB+色温两键；另立 follow 任务
2. **iOS 无相机记忆重置通路**：新增 `camera_wb_mode`/`camera_color_temperature` 键未来必须纳入 iOS 相机设置页重置清单（Android 在 设置→相机）
3. **EV/对比度/饱和度滑杆显示偏差**：Android 默认态 `--` + EV 动态 exposureRange 离散步进，iOS 恒显数值 + 硬编码 -2..2 step 1（既有偏差，非本次引入）
4. **拖杆逐 tick 冗余写**（AppSlider 连续回调逐帧写 UserDefaults）：功能无误，可选节流优化
5. **环境问题（非本次引入）**：PoLangUITests provisioning profile 缺失导致真机单测阶段 FAIL；`face_landmarker.task`/`MNN.framework` 为 gitignored 二进制，新 worktree 需手动拷贝（建议立项处理可复现性）

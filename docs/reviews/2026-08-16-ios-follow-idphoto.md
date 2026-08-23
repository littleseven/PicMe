# iOS Follow 审查报告——证照功能（idphoto）

> 管线：`/ios-follow 证照功能`（模式 B 追齐）· 日期：2026-08-16
> 分支：`feat/ios-idphoto`（worktree `.worktrees/feat-ios-idphoto`）
> 契约基线：Android `main@9228bbe3b` · spec：`docs/08-UI-SPECS/screens/idphoto.yaml`（418 行，本次反向提取）
> 交叉审查（铁律4）：UI=GLM coder 实现/K3 主审 ✓；管线=K3 主实现/GLM reviewer 对抗审 ✓

## 0. 结论

**PASS（有条件）** —— 1 🔴 + 5 🟡 审查发现，🔴（EXIF 方向丢失）与 🟡Y1-Y4 已当场修复并重编，🟡Y5 良性不改。修复后编译绿 + 真机回归绿；**竖拍照片 E2E 与保存链路留用户真机终验**（见 §4）。

## 1. 交付物清单

| commit | 内容 |
|--------|------|
| `74baf4174` | spec(idphoto.yaml) + idphoto token 组×13 + 双端镜像重生成 + 总纲同步 |
| `fe4a887ea` | iOS 全量实现（2872 行）：域逻辑/抠图引擎/VM/UI×6/入口接线/xcstrings×32/单测 |
| `f5a963ae9` | 测试 fixture 修正 + E2E 探针 UITest |
| （本次） | 审查修复：EXIF 方向归一化 + alpha 缓存接线 + 位图释放 + 全图变换入队 + 依赖注释 |

## 2. 审查发现与处置

| # | 级别 | 发现 | 处置 |
|---|------|------|------|
| R1 | 🔴 | **EXIF 方向丢失**：`UIImage.cgImage` 直取未旋转像素，竖拍照片（证件照主场景！）整条管线横躺跑——matting 质量退化/subjectBounds 头顶检测错轴/预览保存均旋转 90°。仓库 5 处先例（MobileClipEncoder 等）均已归一化 | ✅ 已修：`normalizedCGImage`（UIGraphicsImageRenderer 烘焙方向，先例同款）；E2E 侥幸通过系测试素材恰好 .up |
| Y1 | 🟡 | adjustedAlphaCache 死代码：换底色全量重付 adjustEdges+replay（feather=20 时 O(n·41)×2） | ✅ 已修：AlphaCacheBox 队列专属缓存（edge+strokeVersion 为 key），换色仅重付合成 |
| Y2 | 🟡 | `original` CGImage 常驻仅作非空哨兵（~3-4MB） | ✅ 已修：哨兵改 `sourceW > 0`，位图即弃 |
| Y3 | 🟡 | rgbaBuffer 全图变换跑在 Main（spec §7.2 要求后台） | ✅ 已修：移入 computeQueue |
| Y4 | 🟡 | 平移/缩放重渲染依赖「顺带性」@State 写入，易被误清理 | ✅ 已修：注释固化依赖（Y4 登记） |
| Y5 | 🟡 | AppSlider onEditingChanged 系统手势接管边角：release 提交退化为「仍每次提交」 | 不改（良性；默认 nil 零影响既有调用方） |

**实证排除的疑点**：CGImage.cropping 坐标翻转——微基准实验证实全链路行 0=照片顶部、cropping 左上原点，composePreview/previewCanvas 裁切正确（真机截图中人像立正、抠图干净互证）。

## 3. ✅ 审查确认无恙（要点）

- §10 构图数学（clampFraming 可行域/8% headroom/像素终钳/zoom 1-4）穷举无越界
- §7.3 adjustEdges 顺序 sharpen→dilate/erode→feather 逐字符合
- §7.2 FUSION 管线（256² selfie mask[0] + 1024² ModNet (x/255−0.5)/0.5 + 双线性半像素上采样 + max 融合 + 幂等 sigmoid）
- 线程契约（Main 状态拷贝/描边快照先行/串行 compute 队列/@Published 仅 Main 写）
- §7.5 保存 WYSIWYG（同 base 同 cropRect 精确拉伸/JPEG 0.95/isSaving 守卫+最新态复位）
- [PRIVACY] 全链路端侧（唯一网络流=模型下载）；i18n 32 键三语 + `%1$@` 占位符正确规避 `%1$s` 陷阱
- 硬规则：UI 零内联 hex/dp（IdPhotoTokens 引用）；shared Kotlin 零改动

## 4. 验收三栏

**✅ 自动通过**
- iOS 编译绿（generic/platform=iOS，修复后复验）
- shared:jvmTest 绿（回归）
- IdPhotoDomainTests 31 用例 0 败（真机）
- E2E 探针真机 30s 全链路（gallery→pager→pager_id_photo→证照屏→四 tab→返回）
- FUSION 抠图真机实跑出图（蓝底人像，视觉+token 色值双端一致 #FFD0BCFF）
- 4 态截图 attachments（default/size/edge/repair）

**⚠️ 待真机终验（命令判不了的）**
- **竖拍照片**端到端（R1 修复后的实证——探针素材恰好 .up，未覆盖竖拍路径）
- 「完成」保存→相册落图（JPEG 质量/分辨率 295×413@1寸）真实检查
- 拖拽/双指缩放手感跟手度；修补画笔涂改观感；边缘三滑杆 release 提交流畅度
- modnet 未下载机型上的 Error→下载→重进流程

**📋 技术债清单**
- `fix-idphoto-white-margin`（BorderTrimmer 纯色边框裁剪，Android 分支 67c0e4df9 未合入 main）——本次不含，Android 合入后需三同步跟进
- chat 照片预览入口（iOS chat 无 MediaPager，另线追齐）
- onnxruntime-objc 无 `setInterOpNumThreads`（Android interOp=2 无法对等，仅 intraOp=2）
- MediaPipe 双端版本差（Android 0.10.26 / iOS 0.10.14）——selfie mask index 0 语义真机已验证一致
- 跨端同照片像素级 diff 未做（两设备相册不同）；探针色板截图缺失（tab 顺序设计瑕疵，色板态已由 default 截图覆盖）
- 顶栏条带色差（iOS AppTopBar systemBackground #000 vs 内容底 #101010，PhotoEditorScreen 同款先例，§12 登记）
- Android 侧 idphoto 代码仍用字面量未引 IdPhotoTokens（token 固化例外，改到哪替到哪）
- 探针未含竖拍 EXIF 素材断言（建议补：相册固定测试素材）

## 5. 事故记录（透明度）

Stage 3 完成后 **worktree 被外部批量清理**（`.worktrees/` 整目录消失，全部 13 个 worktree 连注册）——Stage 2 契约 commit 在分支上无损；Stage 3 未提交代码靠会话上下文留档全量重放（含 GLM agent 重写其 6 文件），BUILD SUCCEEDED 复验。教训已吸收：**阶段完成即 commit**（本报告 4 个 commit 均为阶段性提交）。

# TAG 打标 GPU 加速卡收口(去冗余)

> **版本**:1.0
> **日期**:2026-09-05
> **状态**:设计定稿(ardot 画布已落地;代码待实施)
> **动机**:Gallery 扫描页与 Settings 中关于「相册打标走 GPU 还是 CPU」的卡片 UI 信息冗余——同一开关存在两处副本、双说明文案(其一为本地 LLM 后端遗留文案,文不对题),用户难以分辨。

---

## 1. 现状问题清单

| # | 位置 | 问题 |
|---|------|------|
| 1 | `TagGenerationControlScreen` 底部 `GallerySettingsHeader`(`SettingsScreen.kt:1623`) | 小节标题「相册功能」+「TAG 生成 GPU 加速」label + CPU/OpenCL chips + **两条**说明(`tag_gen_use_opencl_desc` + `ai_agent_local_backend_desc`,后者是本地 LLM 后端遗留文案) |
| 2 | `SettingsScreen.kt:827-869` 休眠 GALLERY 分类 | 同款整块第二份副本(已不可达死代码,注释自述「保留仅供后续清理」) |
| 3 | ardot v2 帧 `gallery/tag_control` | 设计稿中无此卡片(代码侧后加),设计↔实现漂移 |

## 2. 设计决策(用户已确认)

1. **形态**:扫描页底部单行卡(60dp 级)——icon + 标题 + 单条短副标题 + 右侧 `CPU | GPU` 分段控件(所见即所得,与底层布尔 pref `tagGenerationUseOpencl` 一致)。
2. **位置**:相册扫描页(相册设置页)最底部,`BackgroundScanGuardBanner` 之前;无小节标题(全页唯一无标题独立卡,低频全局开关居页尾符合信息层级)。
3. **Settings 侧**:休眠 GALLERY 分类**整块删除**(含 GPU 卡副本与 TAG控制/标签查看/重复图三行死代码)。
4. **分段标签用 `CPU` / `GPU`**(复用 `device_preference_force_cpu/gpu`),不用 `OpenCL (GPU)` 长标签——换取文本列宽,使中英文标题/副标题均单行;OpenCL 术语细节由副标题与既有降级机制承担。

## 3. ardot 交付(已完成)

- 帧:`gallery/tag_control`(Gallery 页)末尾新增 `CardTagAccel`(node `267:2`):
  - `iconBlock`(28×28 amber 12% + `ic/speed`,自 v1 `rowOpencl` 复制)
  - 标题 15sp = 变量 `tag.gen.use.opencl.title`(复用);副标题 11sp = 新变量 `tag.gen.use.opencl.subtitle`(id `267:1`)
  - `Segmented`($2:150 底、radius 14):`CPU`(平文本)+ `GPU` 选中胶囊(浅青 14% 底、文字 $2:130 SemiBold)
  - 行高自适应 + 垂直居中(副标题换行兜底)
- v1 帧 `gallery/settings` 整帧删除(2026-09-05 用户确认:同页新旧两版冲突,结构已被 v2 完全覆盖);相关台账行标记 removed,孤儿变量(11 个,含 `ai.agent.local.backend*`、休眠入口文案)随代码阶段字符串清理
- v2 帧更名 `gallery/tag_control_v2` → `gallery/tag_control`(2026-09-05,v1 删除后版本后缀无意义;对齐兄弟帧 `tag_stage_sheet` 与路由 `Screen.TagControl`),为相册扫描页唯一 SSOT;台账 gallery.csv/gallery_en.csv、`scripts/ardot-lang-align.py`、`gallery/AGENTS.md` 同步
- 语言变量:UI Language 集 359→360;台账 `docs/08-UI-SPECS/screens/lang/`(ledger.json + gallery.csv 4 行)已登记
- 双语验证:zh/EN 标题、副标题均单行,无词内断行/孤字/裁剪;选中色与页内芯片一致

## 4. 代码改动清单(Android)

| 文件 | 改动 |
|------|------|
| `TagGenerationControlScreen.kt` | 新增参数 `useOpencl: Boolean` / `onUseOpenclChange: (Boolean) -> Unit`;页内渲染 `CardTagAccel`(精细控制卡之后、`BackgroundScanGuardBanner` 之前);删除 `header` slot |
| `MainActivity.kt` | TagControl 路由:删除 `GallerySettingsHeader` 注入,改为透传 `settingsViewModel.tagGenerationUseOpencl` |
| `SettingsScreen.kt` | 删除休眠 GALLERY 分类整块(827-869);删除 `GallerySettingsHeader` 与 `OpenClBackendSelection` 组件 |
| strings.xml ×5 | 新增 `tag_gen_use_opencl_subtitle`;删除 `tag_gen_use_opencl_desc`;`ai_agent_local_backend*` 系列若无剩余引用(debug 相册调试功能)一并删除——实施时 grep 定夺 |
| AGENTS.md | `settings/AGENTS.md:153`(TagControl 页头部描述)、`:179`(GALLERY 分类行)同步删除/改写;`gallery/AGENTS.md` 相应核对 |

### 新字符串(五语)

| key | en | zh-rCN | zh-rTW | es | fr |
|-----|----|--------|--------|----|----|
| `tag_gen_use_opencl_subtitle` | Faster tagging; disable if it hangs | 加速标签生成，卡顿时可关闭 | 加速標籤生成，卡頓時可關閉 | Acelera el etiquetado; desactívalo si se bloquea | Accélère l'étiquetage ; désactivez en cas de blocage |

## 5. 不在范围

- 开发者选项「打标模型」选择、debug 相册调试功能、`InferenceDevicePreference`(人脸检测 StageConfigDialog 专用)——保持现状
- OpenCL 守护/降级逻辑(`OpenClGuardian`)不动,仅 UI 收口

## 6. 验收

1. 扫描页底部呈现单行卡,中文/英文标题与副标题单行,切换 CPU/GPU 即时写回 pref(重启保持)
2. Settings 主界面及所有分类不再出现 GPU/CPU 打标开关任何副本
3. 五语 strings 键齐平(i18n 校验无 missing/unused 报错)
4. 编译 + 现有单测绿;与 ardot 帧逐像素对齐复核(token/间距)

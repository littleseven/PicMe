# docs-site 官网与产品现状同步

- 日期：2026-08-08
- 状态：已实现（待提交）
- 范围：`docs-site/` 下 6 个用户可见页（非 `docs-site/docs/` 工程文档镜像）

## 背景

2026-08 产品演进后，官网（`docs-site/`）多处描述与现状脱节：

1. **端云架构反转**：端侧文本 LLM（Qwen3.5-2B）已移除，AI 对话/指令改为**远程默认**（OpenAI 兼容，DeepSeek/通义）。但官网（尤其隐私政策）仍声称「对话内容不会离开设备」「远程模式可选、默认关闭」——与现状相反，且涉及隐私声明的准确性。
2. **NCNN 已全量移除**（2026-07）：人脸检测只剩 MediaPipe / MNN，官网仍写 MediaPipe/NCNN。
3. **iOS 端**：Phase 5 iOS 应用骨架尚未启动（无 IPA），官网无任何 iOS 提及，但产品已规划 iOS。

## 决策

- **iOS 呈现方式 = 轻量**：首页 Hero 徽章「📱 iOS 即将支持 / coming soon」+ FAQ 设备项补充「iOS 版本开发中」。不加下载按钮（iOS 无法像 Android 扫码直装；IPA 自测分发走独立页面，已实现于 `server/` DownloadRoute，api.polang.net/download/ios）。
- **端云表述修正**：把「所有 AI 在本地」过度断言改为「人脸检测、美颜、打标等媒体处理在本地」；AI 对话明确标注需联网。
- **隐私政策**：3.3 节由「远程模式（可选）」改写为「AI 对话推理（远程）」，如实说明对话文本/指令走远程推理、媒体文件绝不上传；INTERNET 权限行、离线使用权同步修正。
- **模型数量**：「必需 7 个 / 约 1.5 GB」保持不变（新增 CODEFORMER 属可选，未改必需计数，避免未经验证数字）。
- **工程文档镜像**（`docs-site/docs/...`）的 NCNN 历史引用**不动**——均为正确的「已移除」过去式或历史性能基线记录，属工程档案，非用户可见页。

## 涉及文件（6 个用户可见页）

| 文件 | 变更 |
|------|------|
| `docs-site/index.html`（zh） | iOS 徽章；隐私卡/FAQ 措辞；4 处本地模型离线表述修正；离线 FAQ 改写；设备 FAQ 加 iOS |
| `docs-site/en/index.html` | iOS 徽章；隐私卡/FAQ 措辞；设备 FAQ 加 iOS（EN 本地模型表述本已正确） |
| `docs-site/getting-started.html`（zh） | 移除「本地聊天」模型项；移除「切回本地模型离线对话」 |
| `docs-site/en/getting-started.html` | 无需改动（EN 本已准确） |
| `docs-site/privacy-policy/index.html`（zh） | 3.1 VLM 项；3.3 远程推理改写；INTERNET 行；NCNN→MNN（2 处）；离线使用权 |
| `docs-site/en/privacy-policy/index.html` | 同上 EN 对应 |

## 验证

- `grep` 扫描：6 个用户可见页无 NCNN / 无「切换本地模型」/ 无「对话内容不会离开设备」/ 无「可完全离线」/ 无「所有 AI 与人脸处理」过度断言。
- iOS 徽章落地点：`index.html:184`、`en/index.html:184`。
- 工程文档镜像（`docs-site/docs/`）的 NCNN 引用保留（历史/过去式，正确）。

## 备注

隐私政策的准确性修正（尤其「对话内容不离开设备」→ 远程推理）是本次同步中**最重要**的项——隐私声明须如实反映数据流向。建议用户上线前再人工通读一遍隐私政策。

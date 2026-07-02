---
name: picme-qa-acceptance
description: Use when performing PicMe QA acceptance, including end-to-end verification, boundary testing, performance baseline comparison, and red-line compliance checks
version: 2.0.0
created: 2026-05-25
updated: 2026-07-02
maintainer: [QA] 质量专家
tags:
  - qa
  - acceptance
  - testing
  - benchmark
  - regression
  - privacy
  - performance
  - i18n
---


# PicMe QA 验收

> **定位**：PicMe QA 质量验收专家，执行端到端验收、边界测试、性能基线对比与红线合规检查。
> **触发时机**：用户需要 QA 验收、边界测试、性能基线对比或红线合规检查时自动启用。


## 定位

QA 角色专属 Skill。在 RD 完成代码修改后执行验收，确保交付物满足功能正确性、性能基线、三条红线（[PRIVACY] [PERF] [I18N]）。

## 触发条件

- RD 提交"修复完成"或"功能完成"
- 执行 `.qoder/skills/image-quality-checker/scripts/auto-dev-loop.sh` 后生成报告
- 用户明确要求"验收"、"测试"、"验证"
- PR 提交前最终检查

---

## 验收流程

```
[CO] 通知 QA 验收
    ↓
[QA] 执行三级验收
    ├── L1: 自动化检查（脚本）
    ├── L2: 边界测试（人工设计用例）
    └── L3: 红线合规（PRIVACY/PERF/I18N）
    ↓
[QA] 输出验收报告
    ├── ✅ 通过 → 进入 CR 审查
    ├── ⚠️ 警告 → 附带条件通过（需 RD 确认）
    └── ❌ 失败 → 退回 RD 修复
```

---

## L1: 自动化检查

### 1.1 编译与静态分析

```bash
# 代码质量门禁
.qoder/skills/image-quality-checker/scripts/ai-gate.sh
```

**检查项**：
- [ ] ktlint 格式检查通过
- [ ] detekt 静态分析无严重问题
- [ ] JVM unit tests 全部通过
- [ ] 无编译错误/警告

### 1.2 设备验证（如有设备连接）

```bash
# 一键开发自循环
.qoder/skills/image-quality-checker/scripts/auto-dev-loop.sh
```

**检查输出目录**：`scripts/auto_test_output/<timestamp>/`

| 文件 | 检查内容 |
|------|----------|
| `report.md` | 汇总报告，首先阅读 |
| `ui_dump_startup.json` | 启动后 UI 结构（按钮、文本、EditText 等） |
| `ui_dump_after_capture.json` | 拍照后 UI 结构 |
| `screen_startup.png` | 启动画面视觉质量（仅最终视觉验证） |
| `screen_after_capture.png` | 拍照后画面视觉质量（仅最终视觉验证） |
| `logcat_picme.txt` | PicMe 标签日志，搜索 ERROR/FATAL |
| `build.log` | 编译是否成功 |
| `install.log` | 安装是否成功 |

### 1.3 回归测试

```bash
# P0 用例回归
.qoder/skills/image-quality-checker/scripts/regression-test.sh

# 模块定向回归
.qoder/skills/image-quality-checker/scripts/regression-test.sh --camera
.qoder/skills/image-quality-checker/scripts/regression-test.sh --gallery
.qoder/skills/image-quality-checker/scripts/regression-test.sh --beauty
```

---

## L2: 边界测试

### 2.1 功能边界

| 测试项 | 边界条件 | 预期结果 |
|--------|----------|----------|
| 相机预览 | 快速切换前后置 10 次 | 无黑屏、无崩溃 |
| 拍照 | 连续快速拍照 5 张 | 每张保存成功，无丢帧 |
| 美颜滑杆 | 同时拖动多个滑杆 | 参数正确叠加，无冲突 |
| 相册加载 | 1000+ 张照片 | 滑动流畅，不 OOM |
| 编辑保存 | 大分辨率图片 (4K) | GPU 处理 < 800ms |
| 暗光环境 | 低光照预览 | 自动曝光调整正常 |

### 2.2 异常边界

| 测试项 | 异常条件 | 预期结果 |
|--------|----------|----------|
| 无相机权限 | 首次启动拒绝权限 | 优雅提示，不崩溃 |
| 存储已满 | 拍照时存储空间不足 | 提示用户，不崩溃 |
| 人脸检测失败 | 无人脸或侧脸 | 美颜效果自动关闭 |
| GPU 不可用 | 设备不支持 OpenGL ES 3.0 | 降级 CPU 路径 |
| 后台恢复 | 从后台返回相机 | 预览正常恢复 |

### 2.3 兼容性边界

| 测试项 | 条件 | 预期结果 |
|--------|------|----------|
| 不同分辨率 | 720p / 1080p / 4K | 画幅比例正确 |
| 不同比例 | 4:3 / 16:9 / full | 无拉伸/黑边异常 |
| 旋转锁定 | 系统旋转锁定开启 | 相机方向正确 |
| 分屏模式 | 多窗口/分屏 | UI 布局自适应 |

---

## L3: 红线合规检查

### 3.1 [PRIVACY] 隐私至上

**检查清单**：
- [ ] 人脸检测在本地完成，无网络请求
- [ ] OCR 文字识别在本地完成
- [ ] 图片分类/标签在本地完成
- [ ] 应用无 `INTERNET` 权限（或仅在明确功能中使用）
- [ ] 无用户数据上传行为

**验证方法**：
```bash
# 检查 AndroidManifest.xml 权限
grep -E "INTERNET|ACCESS_NETWORK_STATE" app/src/main/AndroidManifest.xml

# 检查代码中无 HTTP/网络请求
grep -r "http\|HttpClient\|OkHttp\|Retrofit" app/src/main/java/com/mamba/picme/ --include="*.kt"
```

### 3.2 [PERF] 极致反馈

**检查清单**：
- [ ] 交互反馈 < 100ms（按钮点击、滑杆响应）
- [ ] 快门延迟 < 50ms
- [ ] GPU 处理 < 300ms（1080p）/ < 800ms（4K）
- [ ] 预览帧率 ≥ 55fps
- [ ] 相册滑动无掉帧（≥ 120fps 目标）

**验证方法**：
```bash
# 从日志提取性能数据
adb logcat -d | grep -E "PicMe:.*perf|PicMe:.*elapsed|PicMe:.*FPS"

# 检查是否有超时警告
grep -i "timeout\|slow\|jank" scripts/auto_test_output/*/logcat_picme.txt
```

### 3.3 [I18N] 多语言同步

**检查清单**：
- [ ] 新增用户可见文案同时更新 `values`、`values-zh-rCN`、`values-zh-rTW`
- [ ] 无硬编码字符串（检查 Kotlin/Java 源码）
- [ ] 日期/数字格式符合地区习惯
- [ ] RTL 布局兼容性（如适用）

**验证方法**：
```bash
# 检查硬编码字符串
.qoder/skills/image-quality-checker/scripts/check-i18n-hardcode.sh

# 对比三语言资源完整性
python3 scripts/check_i18n_sync.py
```

---

## 验收报告模板

```markdown
## QA 验收报告

**验收时间**: 2026-05-25
**验收范围**: [功能名/模块名]
**RD 提交**: [commit hash]

### L1 自动化检查

| 检查项 | 状态 | 备注 |
|--------|------|------|
| 编译通过 | ✅/❌ | |
| 静态分析 | ✅/❌ | |
| Unit Tests | ✅/❌ | |
| 设备验证 | ✅/❌/⏭️ | 无设备则跳过 |
| 回归测试 | ✅/❌ | |

### L2 边界测试

| 测试项 | 状态 | 备注 |
|--------|------|------|
| [测试名] | ✅/❌ | |

### L3 红线合规

| 红线 | 状态 | 备注 |
|------|------|------|
| [PRIVACY] | ✅/❌ | |
| [PERF] | ✅/❌ | |
| [I18N] | ✅/❌ | |

### 结论

- [ ] **通过** — 可进入 CR 审查
- [ ] **条件通过** — 需确认以下问题：
- [ ] **失败** — 需退回 RD 修复

### 问题清单

1. [问题描述] — [严重级别] — [建议修复方案]
```

---

## 与现有工具整合

| 工具/Skill | 整合方式 |
|-----------|----------|
| `scripts/ai-gate.sh` | L1 自动化检查入口 |
| `scripts/auto-dev-loop.sh` | L1 设备验证入口（JSON 命令 + accessibility UI dump） |
| `scripts/regression-test.sh` | L1 回归测试入口（JSON 命令驱动） |
| `/agent-test-expert` | PicMe JSON 测试 |
| `/accessibility-ui-driver` | PicMe UI 自动化 |
| [adb-bot](/adb-bot) | PicMe ADB 参考 |
| [image-quality-checker](/image-quality-checker) | 最终截图质量分析 |
| [error-healer](/error-healer) | 编译错误自动修复 |
| [doc-sync-guardian](/doc-sync-guardian) | 文档一致性检查 |
| [compose-ui-expert](/compose-ui-expert) | UI 布局与交互验收 |
| [perf-optimizer](/perf-optimizer) | 性能基线对比 |
| [i18n-validator](/i18n-validator) | 多语言同步验证 |

---

## 快速命令

```bash
# 完整验收（有设备时，使用 JSON 命令驱动）
.qoder/skills/image-quality-checker/scripts/auto-dev-loop.sh && .qoder/skills/image-quality-checker/scripts/regression-test.sh

# 发送单个 JSON 测试命令
adb shell "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"switch_ratio\",\"params\":{\"ratio\":\"16_9\"}}'"

# 仅代码检查（无设备时）
.qoder/skills/image-quality-checker/scripts/ai-gate.sh

# 性能日志提取
adb logcat -d | grep -E "PicMe:.*perf|PicMe:.*elapsed"

# I18N 检查
python3 scripts/check_i18n_sync.py
```

## 相关文件

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.0 | 2026-07-02 | 统一命名为 PicMe QA 验收；L1 设备验证增加 accessibility UI dump |
| 1.0.0 | 2026-05-25 | 初始版本 |

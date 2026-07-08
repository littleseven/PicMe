# PicMe 开发工作流规范（Development Workflow）

> **版本**: 1.1  
> **状态**: 生效中  
> **最后更新**: 2026-07-01  
> **维护者**: CO / RD  
> **上级文档**: 根目录 `AGENTS.md`（Agent First 治理与角色协作）

---

## 1. 双螺旋演进工作流（Spec ↔ Code Co-Evolution）

### 1.1 核心原则

Spec 驱动开发（Spec-Driven Development, SDD）要求**文档与代码始终保持同步**，但在实践中允许"探索-固化"的双向演进：

```
Spec 文档（What & How）
    ↓ 驱动
代码实现（Implementation）
    ↓ 发现
实现中的新问题 / 优化点
    ↓ 反馈
Spec 文档更新（Consensus）
    ↓ 驱动下一轮...
```

### 1.2 探索-固化规则

| 阶段 | 规则 | 责任人 |
|------|------|--------|
| **探索期** | 当实现中发现 Spec 不明确时，允许先行探索代码实现，**最多 1 个 Commit** | RD |
| **固化期** | 探索完成后，必须在**同一 PR / Commit** 中更新对应 Spec 文档 | RD |
| **审查期** | CR 审查时同时审查代码 + 文档变更，文档缺失 = **一票否决** | CR |
| **验收期** | QA 验收时验证代码行为与 Spec 验收条件（AC）一致 | QA |

### 1.3 红线（Hard Limits）

- **[NEVER]** 禁止"先合并代码，后补文档"
- **[NEVER]** 禁止文档更新与代码实现分离提交（必须同一 PR / Commit）
- **[MUST]** 代码修改了 `beauty-engine/` 内部实现，必须同步修改 `beauty-engine/AGENTS.md`（或 PR 描述中说明原因）
- **[MUST]** 代码修改了 `beauty-api/` 公开接口，必须同步修改 `beauty-engine/AGENTS.md` + `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` + 通知 App 层适配
- **[MUST]** 新增功能必须在 `PRODUCT.md` / `FEATURES.md` 中有对应需求描述
- **[MUST]** 修改相册搜索/TAG 生成相关代码，必须同步更新 `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` 或 `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md`
- **[MUST]** 新增/修改 AI Capability 必须同步更新 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` 与 `COMMAND_REFERENCE.md`

---

## 2. 反向链接注释规范（Spec Traceability）

### 2.1 目的

在关键接口和实现代码中嵌入 Spec 引用，实现：
- 需求变更时快速定位受影响代码
- 代码审查时快速追溯设计意图
- 自动化工具生成 Traceability Matrix

### 2.2 注释格式

```kotlin
// Spec: <文档路径>#<章节/锚点>
// Implements: <AC-ID>（如 AC-P0-3）
// Related: <关联文档路径>#<章节>
// ChangeLog: <日期> <变更描述>（可选）
```

### 2.3 必须添加反向链接的位置

| 位置 | 示例 | 说明 |
|------|------|------|
| 公开 API 接口 | `BeautyPreviewProvider` | 关联 `beauty-engine/AGENTS.md` 接口定义 |
| 核心算法实现 | `FrameSyncManager` | 关联 `FRAME_SYNC_TECH_SPEC.md` 功能需求 |
| 架构边界类 | `api/` vs `internal/` 边界 | 关联架构约束 |
| 性能关键路径 | `CameraPreviewRenderer.render()` | 关联 `NFR_SPEC.md` 指标 |
| 降级/容灾逻辑 | `onGlWarmUpFallback()` | 关联 `BEAUTY_ENGINE_FALLBACK.md` |
| 搜索召回逻辑 | `ExplicitFirstSearchPipeline` | 关联 `GALLERY_SEARCH.md` |
| TAG 生成阶段 | `TagGenerationPipeline` | 关联 `AUTO_TAG_GENERATION_SPEC.md` |

### 2.4 完整示例

```kotlin
// Spec: docs/03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md#5-frame-sync
// Implements: AC-P0-3
// Related: beauty-engine/AGENTS.md
// ChangeLog: 2026-06-30 新增 missingThresholdFrames 字段
class FrameSyncManager(
    private val config: FrameSyncConfig = FrameSyncConfig.DEFAULT
) {
    // ...
}
```

### 2.5 Traceability Matrix

维护可追溯性矩阵（Traceability Matrix），记录需求 → 代码 → 测试的映射关系：

| 需求 ID | 需求描述 | 实现文件 | 测试文件 | 验收条件 |
|---------|---------|---------|---------|---------|
| FR-5 | 严格缺失处理 | `FrameSyncManager.kt` | `FrameSyncMissingTest.kt` | AC-P0-3 |
| FR-1 | FrameId 体系 | `FrameId.kt` | `FrameIdTest.kt` | AC-P0-1 |
| FR-Search-1 | 自然语言相册搜索 | `MediaSearchEngine.kt` | `SearchIntegrationTest.kt` | AC-Search-1 |

---

## 3. CI 检查规则

### 3.1 文档同步检查（Doc Sync Check）

在 CI 流水线中加入轻量级脚本（概念实现，当前以本地脚本为主）：

```yaml
# .github/workflows/doc-sync-check.yml（参考）
doc-sync-check:
  script:
    # 检查 PR 中修改了 beauty-engine 内部实现时是否同步修改了 AGENTS.md
    - python scripts/check_doc_sync.py \
        --code-path beauty-engine/src/main/java/ \
        --doc-path beauty-engine/AGENTS.md
    
    # 检查 PR 中修改了 beauty-api 接口时是否同步修改了技术文档
    - python scripts/check_doc_sync.py \
        --code-path beauty-api/src/main/java/ \
        --doc-path docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
    
    # 检查文档间内部链接有效性
    - python scripts/check_doc_links.py \
        --root docs/
    
    # 检查 AGENTS.md 中提到的模块在代码中是否存在
    - python scripts/check_spec_completeness.py \
        --spec beauty-engine/AGENTS.md \
        --src beauty-engine/src/
```

> **当前落地状态**：`scripts/check_doc_links.py` 等工具处于设计/局部脚本阶段，日常以 RD/CR 人工检查 + 自动化链接扫描为主。

### 3.2 架构合规检查（Architecture Compliance）

使用 detekt / ktlint 自动检查代码风格与基础架构红线：

```bash
# 代码风格
./gradlew ktlintCheck

# 静态分析
./gradlew detekt

# 组合检查（CI 推荐）
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest ktlintCheck detekt
```

### 3.3 性能基线检查（Performance Baseline）

```bash
# 启动时间基准测试（需真机/模拟器）
adb shell am start -W com.mamba.picme/.MainActivity

# 与历史基线对比（退化 > 5% 需关注）
# python scripts/compare_perf_baseline.py \
#     --current benchmark-results.json \
#     --baseline perf-baseline.json \
#     --threshold 5%
```

---

## 4. 代码审查（CR）规范

### 4.1 CR 检查清单（Checklist）

审查者必须在 PR 中确认以下检查项：

```markdown
## CR Checklist

### 文档同步
- [ ] 代码变更是否同步更新了对应 Spec 文档？
- [ ] 新增接口是否补充了 `api/` 文档说明？
- [ ] 新增验收条件是否关联了 `[kimi-task]` 标记？
- [ ] 是否更新了 `docs/00-INDEX.md` 中的文档索引？

### 架构合规
- [ ] `beauty-api/` 是否引入了 `beauty-engine/internal/` 依赖？
- [ ] App 层是否直接实例化了 engine 内部类？
- [ ] 新增公开 API 是否补充了默认值与向后兼容处理？
- [ ] 新增 Capability 是否注册到 `CapabilityRegistry`？

### 性能
- [ ] 交互响应是否 ≤ 100ms？
- [ ] 相机快门延迟是否 ≤ 50ms？
- [ ] 预览帧率是否稳定 30fps？
- [ ] 搜索执行是否在后台线程？

### 资源管理
- [ ] `release()` / `close()` 是否完整释放了 EGL / GL / Surface / Thread / Bitmap 资源？
- [ ] 新增资源是否考虑了生命周期和异常路径？

### 测试
- [ ] 新增功能是否补充了单元测试？
- [ ] 边界条件是否覆盖？
- [ ] 编译与单元测试是否通过？

### 国际化与隐私
- [ ] 新增字符串是否已覆盖 EN / zh-CN / zh-TW？
- [ ] 敏感数据（人脸、照片、位置）是否全部本地处理？

### 日志与可观测性
- [ ] 是否使用了正确的日志标签（如 `PicMe:Gallery`、`PicMe:Search`）？
- [ ] 关键路径是否输出了结构化日志？
```

### 4.2 CR 一票否决项

以下任一情况，CR 必须 **Request Changes**：

1. **文档不同步**：代码修改了实现但未更新对应 Spec
2. **架构越界**：`beauty-api/` 依赖 `beauty-engine/internal/` / App 直接实例化引擎内部类
3. **性能退化**：快门延迟 > 50ms / 交互响应 > 100ms / 帧率下降 > 5%
4. **资源泄漏**：`release()` / `close()` 未覆盖新增资源
5. **无测试覆盖**：新增功能无单元测试或集成测试
6. **I18N 缺失**：新增文案未覆盖 EN / zh-CN / zh-TW
7. **隐私违规**：敏感数据上云或调用云端接口处理敏感内容

---

## 5. 术语词典（Glossary）

维护统一的术语定义，确保 Spec 语义一致性：

| 术语 | 英文 | 定义 | 禁用别名 |
|------|------|------|---------|
| 大美丽 | Big Beauty | PicMe 自研 OpenGL ES + EGL 美颜引擎 | 美颜引擎、自研引擎 |
| 帧同步 | Frame Sync | 人脸检测帧与渲染帧的时间对齐机制 | 同步系统、时序对齐 |
| 妆容甩飞 | Makeup Detachment | 妆容与人脸位置不同步的分离现象 | 妆容滞后、妆容漂移 |
| 悬空残留 | Hover | 人脸出画后妆容仍停留在屏幕上的现象 | 妆容残留 |
| 严格缺失 | Strict Missing | 无检测结果 N 帧后强制隐藏妆容的策略 | 缺失隐藏、严格模式 |
| 预测补偿 | Prediction Compensation | 基于运动轨迹预测人脸位置的补偿算法 | 运动预测、预测算法 |
| FaceId / FrameId | FrameId | 全局单调递增帧标识符 | 帧ID、frame_id |
| 零拷贝 | Zero Copy | GPU 管线中禁止 CPU-GPU 数据传输 | 无拷贝、直通 |
| 降级 | Fallback | 引擎异常时自动回退到基础预览 | 回退、降级策略 |
| 库化 | Library-ization | 将引擎模块演进为独立发布库 | 模块化、独立库 |
| TAG 生成 | Tag Generation | 本地 5-Pass 照片标签生成管道 | 打标、标签扫描 |
| 语义召回 | Semantic Recall | MobileCLIP 文本-图像相似度召回 | CLIP 搜索 |
| 显式召回 | Explicit Recall | 基于结构化字段（时间/地点/人脸/TAG）的 SQL 召回 | 规则召回 |

---

## 6. 更新历史

| 版本 | 日期 | 变更 | 作者 |
|------|------|------|------|
| 1.0 | 2026-05-14 | 初版，定义双螺旋演进工作流、反向链接规范、CI 检查规则 | PM |
| 1.1 | 2026-06-30 | 更新文档引用（GALLERY_SEARCH / AUTO_TAG / BEAUTY_ENGINE_TECH_SPEC），补充搜索/TAG 红线、I18N/隐私否决项、当前 CI 命令 | CO |

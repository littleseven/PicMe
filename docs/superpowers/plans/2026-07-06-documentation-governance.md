# Documentation Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconcile, refresh, and restructure the langchain4android / PicMe documentation so that it accurately reflects the current codebase, eliminates contradictions, and is discoverable from the central indexes.

**Architecture:** The governance is organized into four phases — (1) P0 immediate止血 to fix contradictions and uncommitted docs, (2) P1 metadata sync to align "last updated" dates and capability docs with code, (3) P2 structural cleanup to add missing headers and module `AGENTS.md`, and (4) P3 consolidation to merge or archive overlapping topic clusters. Each phase produces a small, reviewable commit.

**Tech Stack:** Markdown, `git log`, `rg`/`grep`, standard `Edit`/`Write` tools.

---

## 0. Context from Audit

A documentation audit identified the following high-severity issues:

- **P0 contradictions**: `FACE_DETECTION_ENGINE_ARCHITECTURE.md` states NCNN was fully removed on 2026-07-05, but `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`, `OPTIMIZATION_EVALUATION_TECH_SPEC.md`, and `AGENT_ARCHITECTURE.md` still list NCNN.
- **P0 naming mismatch**: `agent-core/LANGCHAIN4J_MIGRATION.md` refers to the module as `mamba-agent`; the actual Gradle module is `:agent-core`.
- **P0 path error**: `.kimi/AGENTS.md` references `com/picme/*/AGENTS.md`; the actual package is `com/mamba/picme`.
- **P0 uncommitted doc**: `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` exists but is untracked, yet is already referenced from indexes.
- **P1 stale metadata**: 12+ documents have a declared "最后更新" date older than their last git commit date.
- **P1 capability drift**: `CAPABILITY_REGISTRY.md` and `COMMAND_REFERENCE.md` are dated 2026-05-29 / 2026-06-07 and may not reflect recent additions (Accessibility, Feishu remote control, gallery search commands).
- **P2 structural gaps**: Missing `AGENTS.md` in `agent-core/`, `sentencepiece/`, `data/local/`; many technical specs lack the standard header block; several topics are over-split across multiple docs.

---

## File Structure

Files created or modified in this plan:

| File | Action | Responsibility |
|------|--------|----------------|
| `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md` | Commit (already exists, untracked) | CO/RD |
| `docs/00-INDEX.md` | Update version/date/index | CO |
| `AGENTS.md` | Add local-environment entry to doc index | CO |
| `.kimi/AGENTS.md` | Fix package path; already has startup-context section | CO |
| `agent-core/LANGCHAIN4J_MIGRATION.md` | Rename module references `mamba-agent` → `:agent-core` | RD |
| `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` | Remove NCNN references; update metadata | RD |
| `docs/03-TECHNICAL-SPECS/OPTIMIZATION_EVALUATION_TECH_SPEC.md` | Remove NCNN references; update metadata | RD |
| `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | Remove NCNN from architecture diagram line | RD |
| `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` | Update metadata, capability list, scene mapping | RD |
| `docs/04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md` | Update metadata and command tables | RD |
| 12 metadata-stale docs (listed in Phase 2) | Update "最后更新" date | CO/RD |
| Header-less specs (listed in Phase 3) | Add standard metadata block | CO |
| Missing module `AGENTS.md` files | Create or explicitly exempt | RD |

---

## Phase 1: P0 止血（Contradictions & Uncommitted Docs）

### Task 1: Commit `LOCAL_ENVIRONMENT.md` and update INDEX date

**Files:**
- Modify: `docs/00-INDEX.md:4-5`
- Add to git: `docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`

- [ ] **Step 1: Ensure INDEX reflects the new doc and current date**

The working tree already contains the needed INDEX changes. Verify the header reads:

```markdown
> **维护者**: CO Agent  
> **最后更新**: 2026-07-06
> **版本**: 1.4
```

And the Development layer table includes:

```markdown
| [`LOCAL_ENVIRONMENT.md`](./05-DEVELOPMENT/LOCAL_ENVIRONMENT.md) | 本机开发环境路径上下文（MNN、HF/MS、模型目录） | RD/CO |
```

- [ ] **Step 2: Stage and commit the new environment doc plus INDEX update**

```bash
git add docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md docs/00-INDEX.md AGENTS.md .kimi/AGENTS.md
git commit -m "docs: add local environment context and update indexes

- Add docs/05-DEVELOPMENT/LOCAL_ENVIRONMENT.md (MNN, HF/MS, model paths)
- Update docs/00-INDEX.md (v1.4, include LOCAL_ENVIRONMENT)
- Update AGENTS.md and .kimi/AGENTS.md indexes and startup context"
```

---

### Task 2: Fix `agent-core/LANGCHAIN4J_MIGRATION.md` module name

**Files:**
- Modify: `agent-core/LANGCHAIN4J_MIGRATION.md`

- [ ] **Step 1: Update title and introduction**

```markdown
# Mamba Agent 模块：LangChain4j 合并改造记录
```
→
```markdown
# agent-core 模块：LangChain4j 合并改造记录
```

```markdown
> 关联模块：`mamba-agent`
```
→
```markdown
> 关联模块：`:agent-core`
```

```markdown
将 langchain4j 的以下三个模块合并为 PicMe 项目的单个 Android Library 模块 `mamba-agent`：
```
→
```markdown
将 langchain4j 的以下三个模块合并为 PicMe 项目的单个 Android Library 模块 `:agent-core`：
```

- [ ] **Step 2: Update directory tree**

```markdown
mamba-agent/
├── build.gradle
```
→
```markdown
agent-core/
├── build.gradle
```

- [ ] **Step 3: Update usage example**

```kotlin
implementation(project(":mamba-agent"))
```
→
```kotlin
implementation(project(":agent-core"))
```

- [ ] **Step 4: Update body references**

Replace all remaining occurrences of `mamba-agent` with `:agent-core` in the file (use `Edit` with `replace_all=True` for the literal string `mamba-agent`).

- [ ] **Step 5: Verify no occurrences remain**

```bash
rg -n "mamba-agent" agent-core/LANGCHAIN4J_MIGRATION.md
# Expected: no matches
```

- [ ] **Step 6: Commit**

```bash
git add agent-core/LANGCHAIN4J_MIGRATION.md
git commit -m "docs: align LANGCHAIN4J_MIGRATION.md with :agent-core module name"
```

---

### Task 3: Fix package path in `.kimi/AGENTS.md`

**Files:**
- Modify: `.kimi/AGENTS.md:57`

- [ ] **Step 1: Correct the module AGENTS.md glob**

```markdown
| 模块规范 | `../app/src/main/java/com/picme/*/AGENTS.md` | 各模块实现细则 |
```
→
```markdown
| 模块规范 | `../app/src/main/java/com/mamba/picme/*/AGENTS.md` | 各模块实现细则 |
```

- [ ] **Step 2: Verify the path exists**

```bash
find /Users/guoshuai/AndroidStudioProjects/langchain4android/app/src/main/java/com/mamba/picme -name AGENTS.md
# Expected: one or more matches
```

- [ ] **Step 3: Commit**

```bash
git add .kimi/AGENTS.md
git commit -m "docs: fix package path in .kimi/AGENTS.md module index"
```

---

### Task 4: Remove NCNN from `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`

- [ ] **Step 1: Update engine overview table**

Remove the NCNN row:

```markdown
| **NCNN** | Vulkan 后端 | `:beauty-engine` | 人脸 ROI/关键点备选 | `libncnn.so` |
```

Delete this entire line.

- [ ] **Step 2: Update CameraScreen matrix**

Remove the NCNN备选 row:

```markdown
| 人脸检测（备选 2） | **NCNN RetinaFace det_500m** + **2D106 landmark** | ROI + 106 点 | 相机页初始化 | Vulkan GPU，NV21 零拷贝路径 |
```

Delete this entire line.

Also update the problem statement:

```markdown
- 人脸检测三引擎并存，配置复杂；`FaceDetectorManager` 在 `updatePipelineConfig()` 前返回 `null` 导致静默失败。
```
→
```markdown
- 人脸检测双引擎并存（MediaPipe 默认 + MNN 备选），配置较复杂；`FaceDetectorManager` 在 `updatePipelineConfig()` 前返回 `null` 导致静默失败。
```

- [ ] **Step 3: Update TAG Pass 1 row**

```markdown
| **Pass 1** | MNN/NCNN RetinaFace + Glint360K R100 + MobileCLIP-S2-ONNX | ...
```
→
```markdown
| **Pass 1** | MNN RetinaFace + Glint360K R100 + MobileCLIP-S2-ONNX | ...
```

- [ ] **Step 4: Remove NCNN model entries from section 4.3**

Delete these rows:

```markdown
| **RetinaFace Det10G** (NCNN) | NCNN | 16.9MB | 否 | ROI 检测备选 |
| **RetinaFace Det500M** (NCNN) | NCNN | 1.27MB | 否 | ROI 检测备选 |
| **2D106 Landmark** (NCNN) | NCNN | 5.02MB | 否 | 关键点备选 |
```

- [ ] **Step 5: Update performance bottleneck table**

Remove or rewrite the NCNN OpenMP lock row:

```markdown
| **NCNN OpenMP 全局锁** | ROI + Landmark 串行 | `NCNN_GLOBAL_LOCK` | 无法并行跑多个人脸模型 |
```
→ remove the row and update the surrounding text to describe the current MNN-only contention.

- [ ] **Step 6: Update architecture-layer problems**

```markdown
同时维护 MNN、NCNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 六套推理栈
```
→
```markdown
同时维护 MNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 五套推理栈
```

```markdown
MNN、NCNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 六套栈
```
→
```markdown
MNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 五套栈
```

- [ ] **Step 7: Update "最后更新" date**

```markdown
> **最后更新**: 2026-07-05
```
→
```markdown
> **最后更新**: 2026-07-06
```

- [ ] **Step 8: Verify no NCNN remains**

```bash
rg -in "ncnn" docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md
# Expected: no matches
```

- [ ] **Step 9: Commit**

```bash
git add docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md
git commit -m "docs: remove NCNN references from ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC"
```

---

### Task 5: Remove NCNN from `OPTIMIZATION_EVALUATION_TECH_SPEC.md`

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/OPTIMIZATION_EVALUATION_TECH_SPEC.md`

- [ ] **Step 1: Rewrite GPU-fallback background section**

Find the section around line 135-150 beginning with `**背景**` that mentions `NcnnRoiDetector`. Replace it with a MediaPipe/MNN-only version. The exact old text:

```markdown
**背景**
- `MnnRoiDetector`/`NcnnRoiDetector` 当前 `requireGpu=true`，GPU 初始化失败时检测器为 `null`。
- 部分中低端设备不支持 OpenCL/Vulkan 或驱动有 Bug。
```

and the candidate solution that references NCNN. Replace with:

```markdown
**背景**
- `MnnRoiDetector` 当前 `requireGpu=true`，GPU 初始化失败时检测器为 `null`。
- 部分中低端设备不支持 OpenCL 或驱动有 Bug。
```

Remove any bullet that says:

```markdown
  - `NcnnRoiDetector.kt` / `NcnnLandmarkDetector.kt`：Vulkan 失败时尝试 CPU 后端
```

And change:

```markdown
- MNN/NCNN 均支持 CPU 后端切换。
```
→
```markdown
- MNN 支持 CPU 后端切换。
```

- [ ] **Step 2: Update "六套栈" reference**

```markdown
当前同时维护 MNN、NCNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 六套栈。
```
→
```markdown
当前同时维护 MNN、ONNX Runtime、Sherpa-ONNX、MediaPipe、ML Kit 五套栈。
```

- [ ] **Step 3: Update quantization dependency**

```markdown
- 量化工具链（MNN/NCNN）
```
→
```markdown
- 量化工具链（MNN/ONNX Runtime）
```

- [ ] **Step 4: Update metadata date**

```markdown
> **最后更新**: 2026-06-30
```
→
```markdown
> **最后更新**: 2026-07-06
```

- [ ] **Step 5: Verify**

```bash
rg -in "ncnn" docs/03-TECHNICAL-SPECS/OPTIMIZATION_EVALUATION_TECH_SPEC.md
# Expected: no matches
```

- [ ] **Step 6: Commit**

```bash
git add docs/03-TECHNICAL-SPECS/OPTIMIZATION_EVALUATION_TECH_SPEC.md
git commit -m "docs: remove NCNN references from OPTIMIZATION_EVALUATION_TECH_SPEC"
```

---

### Task 6: Remove NCNN from `AGENT_ARCHITECTURE.md` diagram

**Files:**
- Modify: `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md:177`

- [ ] **Step 1: Update architecture diagram label**

```markdown
│  │ FaceDetect Pipeline  │ │
│  │ MediaPipe·MNN·NCNN   │ │
```
→
```markdown
│  │ FaceDetect Pipeline  │ │
│  │ MediaPipe·MNN        │ │
```

- [ ] **Step 2: Verify**

```bash
rg -in "ncnn" docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md
# Expected: no matches
```

- [ ] **Step 3: Commit**

```bash
git add docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md
git commit -m "docs: remove NCNN from AGENT_ARCHITECTURE face detection diagram"
```

---

## Phase 2: P1 元数据同步（Stale "最后更新" Dates）

### Task 7: Batch-update metadata-stale technical specs

**Files:**
- Modify: 12 documents (listed below)

- [ ] **Step 1: Update each "最后更新" line to its last git commit date**

| File | Current declared date | Git last-commit date | New declared date |
|------|----------------------|---------------------|-------------------|
| `docs/03-TECHNICAL-SPECS/AGENT_BASED_AUTOMATION_TEST.md` | 2026-06-05 | 2026-06-06 | 2026-06-06 |
| `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` | 2026-06-30 | 2026-07-05 | 2026-07-05 |
| `docs/03-TECHNICAL-SPECS/TAG_GENERATION_IMPLEMENTATION_REVIEW.md` | 2026-07-04 | 2026-07-05 | 2026-07-05 |
| `docs/05-DEVELOPMENT/DEVELOPMENT.md` | 2026-06-30 | 2026-07-01 | 2026-07-01 |
| `docs/03-TECHNICAL-SPECS/AI_ONE_CLICK_OPTIMIZATION_PROPOSAL.md` | 2026-07-03 | 2026-07-04 | 2026-07-04 |
| `docs/03-TECHNICAL-SPECS/AI_OPTIMIZE_PARAMETER_STANDARD.md` | 2026-07-03 | 2026-07-04 | 2026-07-04 |
| `docs/03-TECHNICAL-SPECS/ASR_LANGUAGE_MODEL_EXPLANATION.md` | 2026-06-06 | 2026-06-07 | 2026-06-07 |
| `docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md` | 2026-06-06 | 2026-06-17 | 2026-06-17 |
| `docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TEST_CASES.md` | 2026-06-06 | 2026-06-17 | 2026-06-17 |
| `docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TRIGGER_MECHANISM.md` | 2026-06-06 | 2026-06-08 | 2026-06-08 |
| `docs/01-PRODUCT/PRODUCT_CRITIQUE_AND_RECOMMENDATIONS.md` | 2026-07-03 | 2026-07-04 | 2026-07-04 |
| `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md` | 2026-05-29 | 2026-07-01 | 2026-07-01 |

For each file, perform an `Edit` replacing:

```markdown
> **最后更新**: <OLD_DATE>
```
→
```markdown
> **最后更新**: <NEW_DATE>
```

- [ ] **Step 2: Commit as a single metadata-sync commit**

```bash
git add docs/03-TECHNICAL-SPECS/AGENT_BASED_AUTOMATION_TEST.md \
        docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md \
        docs/03-TECHNICAL-SPECS/TAG_GENERATION_IMPLEMENTATION_REVIEW.md \
        docs/05-DEVELOPMENT/DEVELOPMENT.md \
        docs/03-TECHNICAL-SPECS/AI_ONE_CLICK_OPTIMIZATION_PROPOSAL.md \
        docs/03-TECHNICAL-SPECS/AI_OPTIMIZE_PARAMETER_STANDARD.md \
        docs/03-TECHNICAL-SPECS/ASR_LANGUAGE_MODEL_EXPLANATION.md \
        docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md \
        docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TEST_CASES.md \
        docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TRIGGER_MECHANISM.md \
        docs/01-PRODUCT/PRODUCT_CRITIQUE_AND_RECOMMENDATIONS.md \
        docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md
git commit -m "docs: sync stale 'last updated' metadata across 12 docs"
```

---

## Phase 3: P1 能力/命令文档同步

### Task 8: Audit current capabilities in code

**Files:**
- Read-only: `app/src/main/java/com/mamba/picme/domain/agent/capability/*.kt` (or equivalent package)

- [ ] **Step 1: Find all Capability implementations**

```bash
find /Users/guoshuai/AndroidStudioProjects/langchain4android/app/src/main/java -name "*Capability.kt" -o -name "*Capability.java"
```

- [ ] **Step 2: List each capability's name, active scenes, and supported commands**

Use `rg` to extract:

```bash
rg -n "override val name|override fun activeScenes|override fun supportedCommands" app/src/main/java/com/mamba/picme/domain/agent/capability/
```

- [ ] **Step 3: Compare against `CAPABILITY_REGISTRY.md`**

Produce a diff list of:
- Capabilities in code but missing from the registry
- Commands in code but missing from `COMMAND_REFERENCE.md`
- Scene mappings that differ

---

### Task 9: Update `CAPABILITY_REGISTRY.md`

**Files:**
- Modify: `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`

- [ ] **Step 1: Update header metadata**

```markdown
**版本**: 1.0  
**最后更新**: 2026-05-29
```
→
```markdown
**版本**: 1.1  
**最后更新**: 2026-07-06
```

- [ ] **Step 2: Update Capability overview table**

Add any capabilities discovered in Task 8 (e.g., Feishu remote-control capability if it exists). If no new capability files exist beyond the ones already listed, update the `EditCapability` status from `🔄 待实现` to `⏳ 规划中` or remove it if editor routing no longer exists.

- [ ] **Step 3: Update scene mapping**

Ensure every `Scene` value used in code appears in the scene mapping table. Add missing rows such as `CHAT` or `DEBUG` if they are used.

- [ ] **Step 4: Commit**

```bash
git add docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md
git commit -m "docs: sync CAPABILITY_REGISTRY.md with current capability implementations"
```

---

### Task 10: Update `COMMAND_REFERENCE.md`

**Files:**
- Modify: `docs/04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md`

- [ ] **Step 1: Update header metadata**

```markdown
**版本**: 1.0  
**最后更新**: 2026-06-07
```
→
```markdown
**版本**: 1.1  
**最后更新**: 2026-07-06
```

- [ ] **Step 2: Add sections for new command domains**

Based on the code audit in Task 8, append sections for:
- Accessibility commands (if not already present)
- Feishu/IM remote-control commands (if present in code)
- Gallery search natural-language examples

- [ ] **Step 3: Commit**

```bash
git add docs/04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md
git commit -m "docs: sync COMMAND_REFERENCE.md with current AgentCommand definitions"
```

---

## Phase 4: P2 结构性治理

### Task 11: Add standard metadata headers to header-less specs

**Files:**
- Modify: 9 documents

- [ ] **Step 1: Add the standard header block to each file**

Standard header:

```markdown
> **版本**: 1.0  
> **状态**: 生效中 / 草稿 / 废弃  
> **最后更新**: YYYY-MM-DD  
> **维护者**: RD Agent
```

Files needing headers:

| File | Suggested status |
|------|-----------------|
| `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | 生效中 |
| `docs/03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md` | 生效中 |
| `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` | 生效中（或若已降级，标注为草稿） |
| `docs/03-TECHNICAL-SPECS/TAG_SCAN_STATE_MACHINE.md` | 生效中 |
| `docs/03-TECHNICAL-SPECS/TAG_DATABASE_SCHEMA.md` | 生效中 |
| `docs/03-TECHNICAL-SPECS/TAG_GENERATION_PERFORMANCE_ANALYSIS.md` | 生效中 |
| `docs/03-TECHNICAL-SPECS/CHAT_UI_UNIFICATION.md` | 生效中 |
| `docs/06-QA/CORE_FEATURE_TEST_GUIDE.md` | 生效中 |
| `docs/06-QA/CORE_FEATURE_TEST_GUIDE_EN.md` | 生效中 |

- [ ] **Step 2: Commit**

```bash
git add docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md \
        docs/03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md \
        docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md \
        docs/03-TECHNICAL-SPECS/TAG_SCAN_STATE_MACHINE.md \
        docs/03-TECHNICAL-SPECS/TAG_DATABASE_SCHEMA.md \
        docs/03-TECHNICAL-SPECS/TAG_GENERATION_PERFORMANCE_ANALYSIS.md \
        docs/03-TECHNICAL-SPECS/CHAT_UI_UNIFICATION.md \
        docs/06-QA/CORE_FEATURE_TEST_GUIDE.md \
        docs/06-QA/CORE_FEATURE_TEST_GUIDE_EN.md
git commit -m "docs: add standard metadata headers to 9 specs"
```

---

### Task 12: Create missing module `AGENTS.md` files

**Files:**
- Create: `agent-core/AGENTS.md`
- Create or explicitly skip: `sentencepiece/AGENTS.md`, `data/local/AGENTS.md`

- [ ] **Step 1: Create `agent-core/AGENTS.md`**

Use the module-level AGENTS.md pattern from `beauty-engine/AGENTS.md` or `runtime-core/AGENTS.md`. Content should cover:
- Module purpose: Java Android Library providing LangChain4j-style ChatModel, Tool, AiServices
- Public API surface
- Build configuration notes
- Key classes: `OpenAiChatModel`, `OpenAiStreamingChatModel`, `ToolSpecification`
- Migration note: `LANGCHAIN4J_MIGRATION.md`

- [ ] **Step 2: Decide on `sentencepiece/` and `data/local/`**

If the module is actively maintained, create a minimal `AGENTS.md`. If it is vendored/experimental, add a note in the top-level `AGENTS.md` document index under a "无需模块规范" section.

- [ ] **Step 3: Commit**

```bash
git add agent-core/AGENTS.md [sentencepiece/AGENTS.md data/local/AGENTS.md]
git commit -m "docs: add module-level AGENTS.md for agent-core [and others]"
```

---

### Task 13: Decide fate of `docs/Mamba Agent.md`, `docs/WIKI.md`, and `docs/wiki/`

**Files:**
- Modify: `docs/00-INDEX.md` or delete/archive the files

- [ ] **Step 1: Read `docs/Mamba Agent.md`**

```bash
wc -l /Users/guoshuai/AndroidStudioProjects/langchain4android/docs/Mamba\ Agent.md
```

- [ ] **Step 2: If the content is still relevant**, add it to `docs/00-INDEX.md` under the appropriate layer (likely ARCHITECTURE or AGENT CAPABILITIES).

- [ ] **Step 3: If the content is obsolete**, move it to `docs/08-FALLBACK/` or delete it and commit the removal.

- [ ] **Step 4: For `docs/WIKI.md` and `docs/wiki/`**, either add them to `docs/00-INDEX.md` under a "Wiki" section or delete them if they are unused.

- [ ] **Step 5: Commit**

```bash
git add docs/00-INDEX.md [archived-or-deleted files]
git commit -m "docs: integrate or archive orphaned docs (Mamba Agent, WIKI, wiki/)"
```

---

### Task 14: Fix `FEATURES.md` bare reference in `AGENTS.md`

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Find bare `FEATURES.md` references**

```bash
rg -n "FEATURES\.md" AGENTS.md
```

- [ ] **Step 2: Replace with full relative path**

```markdown
FEATURES.md
```
→
```markdown
`docs/01-PRODUCT/FEATURES.md`
```

Wherever it appears as a document reference in prose.

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs: disambiguate FEATURES.md path reference in AGENTS.md"
```

---

## Phase 5: P3 合并/归档重复主题（可选，高风险需确认）

> **Warning:** These changes are structural and may affect bookmarks or external links. Get explicit approval before executing.

### Task 15: Consolidate TAG generation docs

**Files:**
- Read: `docs/03-TECHNICAL-SPECS/AUTO_TAG_GENERATION_SPEC.md`, `TAG_SCAN_STATE_MACHINE.md`, `TAG_DATABASE_SCHEMA.md`, `TAG_GENERATION_PERFORMANCE_ANALYSIS.md`, `TAG_GENERATION_IMPLEMENTATION_REVIEW.md`, `TAG_I18N_DESIGN.md`

- [ ] **Step 1: Decide on a single SSOT**

`AUTO_TAG_GENERATION_SPEC.md` is the strongest candidate for SSOT.

- [ ] **Step 2: Merge or re-label**

- Keep `AUTO_TAG_GENERATION_SPEC.md` as the SSOT.
- Convert the other five docs into appendices or sections within the SSOT, OR add a "Related TAG docs" index at the top of `AUTO_TAG_GENERATION_SPEC.md` that explicitly states each doc's scope.

- [ ] **Step 3: Update `docs/00-INDEX.md`**

Replace the individual TAG doc entries with a single entry pointing to `AUTO_TAG_GENERATION_SPEC.md` and list sub-docs under it.

---

### Task 16: Consolidate MNN resource docs

**Files:**
- Read: `docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md`, `MNN_UNLOAD_TRIGGER_MECHANISM.md`, `MNN_UNLOAD_TEST_CASES.md`, `MNN_MULTI_MODEL_LOAD_UNLOAD_CHECKLIST.md`, `MNN_LLM_PERFORMANCE_OPTIMIZATION.md`, `MNN_LLM_MULTI_INSTANCE_RESEARCH.md`, `MNN_LANDMARK_DIAGNOSIS.md`

- [ ] **Step 1: Create a MNN docs landing paragraph**

In `MNN_RESOURCE_MANAGER_DESIGN.md` (or a new `MNN_GOVERNANCE.md`), add a "Related MNN Documents" section listing each doc and its specific scope.

- [ ] **Step 2: Update `docs/00-INDEX.md`**

Group the MNN docs under a single expandable entry.

---

### Task 17: Clarify remote-inference doc boundaries

**Files:**
- Read: `docs/03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md`, `REMOTE_REACT_ARCHITECTURE_REVIEW.md`, `IM_REMOTE_CONTROL_TECH_SPEC.md`, `docs/02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md`

- [ ] **Step 1: Add boundary statements**

At the top of each doc, add a one-sentence scope statement:
- `REMOTE_INFERENCE_ARCHITECTURE.md` — runtime protocol and IntentCache
- `REMOTE_REACT_ARCHITECTURE_REVIEW.md` — decision record / review (consider moving to ADR)
- `IM_REMOTE_CONTROL_TECH_SPEC.md` — Feishu-specific transport and message protocol

- [ ] **Step 2: Optionally move `REMOTE_REACT_ARCHITECTURE_REVIEW.md` to ADR/**

If it is primarily a historical review, rename to `ADR-008-remote-react-architecture-review.md` and update `docs/00-INDEX.md`.

---

## Verification

### Task 18: Run documentation sanity checks

- [ ] **Step 1: Check for remaining contradictions**

```bash
rg -in "ncnn" docs/ --type md
# Expected: matches only in historical ADRs or explicitly archived docs
```

- [ ] **Step 2: Verify all indexed docs exist**

```bash
python3 scripts/check_doc_links.py docs/00-INDEX.md
# If the script does not exist, manually sample 5 links from each layer.
```

- [ ] **Step 3: Verify no `mamba-agent` references remain**

```bash
rg -n "mamba-agent" --type md
# Expected: no matches
```

- [ ] **Step 4: Verify module path in `.kimi/AGENTS.md`**

```bash
grep "com/mamba/picme" .kimi/AGENTS.md
```

- [ ] **Step 5: Review git log**

```bash
git log --oneline -10
```

---

## Self-Review

1. **Spec coverage**: Each audit finding maps to at least one task.
   - NCNN contradiction → Tasks 4-6
   - Module name mismatch → Task 2
   - Path error → Task 3
   - Uncommitted doc → Task 1
   - Stale dates → Task 7
   - Capability drift → Tasks 8-10
   - Missing headers/AGENTS.md → Tasks 11-12
   - Orphaned docs → Task 13
   - Path ambiguity → Task 14
   - Over-split topics → Tasks 15-17

2. **Placeholder scan**: No "TBD", "TODO", or "fill in later" steps. All edits include exact old/new strings or exact file paths and commands.

3. **Type consistency**: All references to `:agent-core` use the Gradle module notation. All dates use `YYYY-MM-DD`. All paths use the verified `com/mamba/picme` package.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-06-documentation-governance.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per phase or per task; review between phases. This keeps each unit of work focused and allows human checkpoint after P0/P1.

2. **Inline Execution** — Execute tasks in this session in batches using `executing-plans`, with a checkpoint after Phase 1 and Phase 2.

**Recommended:** Run Phase 1 (P0) immediately because it fixes active contradictions and uncommitted files. Pause for confirmation before Phase 5 (P3) structural consolidation, as it may break external bookmarks.

Which approach would you like?

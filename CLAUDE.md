# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PoLang is a technology research project centered on an AI-Agent-driven smart gallery (破浪相册). It explores three technical tracks in one codebase: **(1) On-device Agent Runtime + local/remote inference** — `AgentOrchestrator` + `CapabilityRegistry` map natural language to device capabilities, with local MNN-LLM (Qwen) and remote OpenAI-compatible inference via langchain4j; **(2) Smart gallery & image editing** — natural-language search, conversational editing, matting/ID-photo, Florence-2 auto-tagging, JS sandbox; **(3) Self-developed OpenGL ES + EGL beauty/filter engine** plus a self-hosted Ktor backend (remote-inference gateway, account system, admin console). This project does not pursue commercialization; its core value lies in technical exploration and engineering practice.

**Current focus (2026-07, app v1.0.26)** is the smart gallery as the default home with AI chat as the core assistant capability (相册/图片编辑为主入口, camera as auxiliary). Shipped: natural-language search, conversational image editing, matting/ID-photo, Florence-2 auto-tagging, JS sandbox; in progress: fact memory + person-relationship graph. See `PRODUCT.md` for the latest product roadmap.

Key technological decisions:
- **On-device Agent**: `runtime-core/` (package `com.mamba.picme.agent.core`) implements an Agent Runtime (AgentOrchestrator, LocalLlmEngine, CapabilityRegistry, etc.) that maps natural language to device capabilities via Qwen3.5-2B running on MNN-LLM.
- **Remote inference**: Standard OpenAI Chat Completions API protocol via langchain4j, with DeepSeek adapter support. Local/remote pipelines fully separated per ADR-005.
- **Privacy-first**: All sensitive AI processing (LLM inference, face detection, OCR) runs locally; non-sensitive commands may use remote orchestration in REMOTE mode.
- **Self-developed Engine**: Full OpenGL ES + EGL pipeline (no third-party beauty SDKs); GPUPixel has been completely removed.

## Common Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run JVM unit tests (no device required)
./gradlew test
# Or module-specific:
./gradlew :app:testDebugUnitTest
./gradlew :beauty-engine:testDebugUnitTest

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Code quality
./gradlew lint
./gradlew ktlintCheck
./gradlew detekt

# Clean build
./gradlew clean

# Install to device
adb install -r app/build/outputs/apk/debug/polang-debug.apk

# View PoLang logs
adb logcat -s "PoLang:*"

# Full dev verification loop (compile → install → launch → screenshot → logs)
./scripts/auto-dev-loop.sh
```

## High-Level Architecture

### Module Structure

Seven Gradle modules defined in `settings.gradle.kts`:
- **`:app`** — Main Android application (Camera, Gallery, Editor, Settings)
- **`:beauty-api`** — Pure Kotlin library; stable API contracts shared between `:app` and `:beauty-engine`
  (BeautySettings, FilterType, StyleFilter, Face, FaceDetector, FrameSyncConfig, etc.)
- **`:beauty-engine`** — Independent Android library; self-developed OpenGL ES + EGL real-time beauty engine
- **`:runtime-core`** — Pure Kotlin library; **Agent Runtime** infrastructure (AgentOrchestrator, CapabilityRegistry,
  LocalLlmEngine, LocalInferencePipeline, RemoteInferencePipeline, ExecutionEngine, PrivacyGuard, MemoryManager, voice/ASR, remote/orchestration, etc.). Package `com.mamba.picme.agent.core.*`
- **`:agent-core`** — **langchain4j 的 Android 适配层**（远程推理库移植；为 langchain4j 提供 Android 兼容的 HTTP 客户端 `com.mamba.client.*`）。`:runtime-core` 远程推理链路的底层依赖。
- **`:mnn-core`** — MNN inference JNI wrappers
- **`:sentencepiece`** — tokenizer

> ⚠️ **模块语义（重要）**：`:runtime-core` = 本地 Agent Runtime（编排本地 Qwen + 远程推理；AgentOrchestrator/CapabilityRegistry/LocalLlmEngine/RemoteInferencePipeline/…；包 `com.mamba.picme.agent.core`）。`:agent-core` = **langchain4j 的 Android 适配层**（远程推理库；runtime-core 远程链路的底层依赖）。旧版文档曾把 Agent Runtime 误归到 `agent-core`，已更正。依赖链：`:app → :runtime-core → :agent-core`。

GPUPixel has been fully removed; all GPU capabilities are provided by the self-developed engine.

### Clean Architecture (App Module)

```
features/  →  domain/usecase/  →  domain/repository/  →  data/
   ↓                ↓
runtime-core/   beauty-api/   beauty-engine/  (strict boundaries — see below)
```

- **Features**: Compose UI + ViewModels. Camera features include an Agent interaction panel for natural language control.
- **Domain**: Pure Kotlin, no Android dependencies. Includes `domain/usecase/AiAgentUseCase` as Facade to `:runtime-core` (Agent Runtime).
- **Data**: Repository implementations, Room DB, DataStore preferences, and LLM model download management (`LlmModelDownloadManager`).
- **runtime-core**: Agent Runtime infrastructure (moved from `domain/agent/`; package `com.mamba.picme.agent.core`).

### Beauty-Engine Layered Architecture (Critical Dependency Boundary)

```
App Layer
    ↓ (only dependency allowed)
beauty-api/                 ← Pure Kotlin API contracts (BeautySettings, FilterType,
                               StyleFilter, Face, FaceDetector, FrameSyncConfig, etc.)
    ↑
beauty-engine:api/          ← Implementation-facing API (BeautyParams, BeautyPreviewProvider,
                               BeautyPreviewEngine, PhotoProcessor, BeautyPerfStats, etc.)
    ↑
beauty-engine:render/       ← Internal OpenGL ES + EGL pipeline (BeautyRenderer,
                               CameraPreviewRenderer, PhotoProcessorImpl, EGLCore)
    ↑
beauty-engine:internal/     ← Face detection adapters (MNN/NCNN/MediaPipe), frame-sync system
```

**Dependency rules**:
- App code **must only** depend on `beauty-api/` and `beauty-engine:api/` classes. Direct references to `render/` or `internal/` are forbidden.
- `beauty-api/` is a pure Kotlin module with zero Android/OpenGL dependencies.
- `beauty-engine:api/` depends on `beauty-api/` for shared types.
- `beauty-engine:render/` implements `api/` interfaces and may depend on Android/OpenGL ES libraries.
- All GPU/EGL operations are encapsulated inside `beauty-engine:render/`.

### Face Detection Architecture

Multi-engine detection unified to 106 landmarks via adapter pattern:
- **MediaPipe Face Mesh 468→106** (default): TFLite GPU delegate inference with precise 468→106 semantic mapping.
- **MNN 2D106** (alternative): Local MNN inference for landmark detection.
- **NCNN 2D106** (alternative): Local NCNN inference for ROI + landmark detection.
- Auto mode: prefers MediaPipe; cascades through alternatives on miss or init failure.

All detection implementations live in `beauty-engine/internal/facedetect/` with adapter pattern (`FaceLandmarkAdapter`).
App layer consumes only `beauty-api/facedetect/` contracts. The old InsightFace ONNX path has been fully replaced by MNN/NCNN detectors.

### Frame-Sync Makeup System

Solves makeup "flying off" caused by face detection (~10 fps) and rendering (30–60 fps) running at different rates.

- Core components in `beauty-engine/internal/framesync/`: `FrameSyncBridge`, `FrameSyncManager`, `MotionTracker`.
- Rendering thread queries `FrameSyncManager` by `FrameId` to get time-aligned face data (exact match → history fallback → prediction compensation → hide).
- `FrameSyncConfig` and `FrameSyncResult` contracts live in `beauty-api/` for cross-module sharing.
- **Recording must reuse the same `FrameSyncManager` instance** as preview to ensure consistent behavior.
- Note: `DetectionQueue` and `FaceDetectionWorker` are design-phase concepts; current implementation uses synchronous detection via `FaceDetectionProvider`.

### Agent Runtime (On-Device & Remote)

```
User Input ("找出去年夏天的照片" / "磨皮50")
    → AiAgentUseCase (Facade in domain/usecase/)
    → AgentOrchestrator.dispatch() (in runtime-core/)
    ├── LOCAL: LocalInferencePipeline
    │   ├── LocalLlmEngine (Qwen3.5-2B via MNN-LLM, custom JSON protocol)
    │   └── L1 Cache Hit? → direct return
    └── REMOTE: RemoteInferencePipeline
        ├── RemoteOrchestrator (OpenAI Chat Completions API)
        └── tool_calls · streaming · multi-turn
    → CapabilityRegistry (route to Capability)
    → ImageEditCapability / AutoTagCapability / NavigationCapability / SystemCapability / RemoteControlCapability + Chat*Capability (execute)
```

- **Module**: `:runtime-core` — independent pure Kotlin module containing all Agent Runtime components (package `com.mamba.picme.agent.core`).
- **Local model**: Qwen3.5-2B-MNN with custom JSON array protocol (method + args).
- **Remote protocol**: Standard OpenAI Chat Completions API (tool_calls, streaming, multi-turn dialogue). langchain4j SDK as consumer layer.
- **Capabilities**: Registered `Capability` classes — `ImageEditCapability` (conversational `edit_image`), `AutoTagCapability` (Florence-2 tagging), `NavigationCapability`, `SystemCapability` (app/settings launch + cross-app a11y), `RemoteControlCapability`, plus chat-side `ChatSearchCapability` / `ChatGallerySummaryCapability` / `ChatStartTagScanCapability` / `ChatRunScriptCapability` / `ChatMediaWriteCapability`. Command→Capability routing SSOT: `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`.
- **Privacy**: `PrivacyGuard` grades operations; RESTRICTED/SENSITIVE → local only.
- **Memory**: `MemoryManager` maintains conversation context for multi-turn dialogue.
- **Voice**: Voice interaction support via `voice/` sub-package (ASR, VAD, AudioRecorder, SherpaMnnAsrEngine).
- **Remote**: Remote LLM orchestration via `remote/` sub-package (OpenAI-compatible API, IntentCache).
- **ADR-005 (2026-06-15)**: Local/Remote protocols formally separated. Unified `InferenceRouter` removed. ~1500 lines of redundant Tool Calling wrappers removed.

### Zero-Copy GPU Pipeline (Preview)

```
CameraX → SurfaceTexture → OpenGL ES Shader → SurfaceView
```

- Preview path is fully GPU-based; `glReadPixels` back to CPU is prohibited in preview.
- Photo processing uses the same shader pipeline via off-screen FBO rendering (`PhotoProcessorImpl`) to guarantee preview/photo consistency. CPU fallback exists at `core/image/`.

## Code Style & Constraints

### Hard Rules (Enforced)
- **No fully-qualified names** for `com.mamba.picme.*` in source (custom Gradle task `checkNoFullyQualifiedName`); use imports.
- **No wildcard imports** (`*`).
- **Lambda parameters must be explicitly named**; implicit `it` is prohibited.
- **Log tags** must follow `PoLang:[ModuleName]` (e.g., `PoLang:Camera`, `PoLang:BeautyEngine`).
- **Indentation**: Kotlin/Java 4 spaces; XML/JSON/MD 2 spaces.

### I18N (Mandatory)
- **Never hardcode user-facing strings** in UI code.
- When adding or refactoring features, **must sync all supported languages**: `values/strings.xml` (EN/default), `values-zh-rCN/strings.xml` (Simplified Chinese), `values-zh-rTW/strings.xml` (Traditional Chinese).

### Global Red Lines
- **`[PRIVACY]`**: All AI processing (face, OCR, classification) must be 100% on-device. Cloud inference is strictly prohibited.
- **`[PERF]`**: Interaction feedback < 100 ms; shutter capture latency < 50 ms.
- **`[I18N]`**: All user-visible text must be extracted and synchronized across the three language sets above.

## Quality Toolchain

- **ktlint** (v1.3.1) — Kotlin code style
- **detekt** (v1.23.6, config: `detekt-config.yml`) — Static analysis
- **Unit tests** — Pure JVM tests covering coordinate algorithms, state machines, converters, end-to-end flows. ~50 test files across `app/src/test/`, `beauty-engine/src/test/`, and `runtime-core/src/test/`.
- **Instrumentation tests** — Require connected device/emulator.

## Documentation Hierarchy

The project follows a three-layer documentation system. When implementation reveals spec gaps, code and docs must be updated in the **same atomic commit**.

```
PRODUCT.md          → Goals and constraints (What)
docs/01-PRODUCT/FEATURES.md    → Interaction and UX rules (How)
<module>/AGENTS.md  → Implementation specs and checklists
```

Key technical specs:
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` — Rendering pipeline, fallback, cooldown recovery, observability
- `docs/03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md` — Coordinate conversion, viewport calculation
- `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` — MediaPipe + MNN/NCNN multi-engine architecture
- `docs/02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md` — Layered module architecture decision
- `docs/02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md` — GPU off-screen rendering for photo processing
- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` — Agent runtime architecture design

## Build Configuration

- **compileSdk**: 36, **minSdk**: 24, **targetSdk**: 35
- **Java/Kotlin target**: 11
- **Dependency management**: Version Catalog (`gradle/libs.versions.toml`)
- **Plugins**: Android Application/Library, Kotlin Android + Compose, KSP, ktlint, detekt

## Useful Scripts

Located in `scripts/`:
- `auto-dev-loop.sh` — Full verification loop (compile, install, launch, screenshot, log collection)
- `ai-gate.sh` — Quality gate (lint + compile + install check)
- `regression-test.sh` — P0 end-to-end regression
- `quick-compile.sh` — Layered fast compile (syntax → compile → dex → APK, stop on first failure)
- `impact-analyzer.sh` — Change impact analysis (affected modules, red lines, doc sync needs)
- `screenshot-diff.py` — Pixel-level screenshot comparison for UI regression
- `smart-commit.sh` — Auto-generate Conventional Commits based on changes

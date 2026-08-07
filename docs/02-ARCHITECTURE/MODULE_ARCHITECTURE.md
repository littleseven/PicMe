# polang 模块架构图

> **边界声明（Boundary Statement）**
> - 本文档描述 PoLang Demo 工程当前的 Gradle 模块划分、依赖方向与 Native SO 归属。
> - 产品目标与验收口径以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 [`AGENTS.md`](../../AGENTS.md) 为准。

**模块定位**：模块分层与依赖关系可视化
**主要维护者**：项目开发者、AI Agent
**阅读对象**：项目开发者、AI Agent
**版本**：1.3（agent-core 删除 + polang 改名对齐版）
**最后更新**：2026-08-07
**状态**：生效中

---

## 1. 模块清单

| 模块 | 类型 | 主要职责 | 关键产物 |
|------|------|----------|----------|
| `:androidApp` | Android Application | PoLang 主应用：Compose UI、页面导航、手动 DI、模块组装 | `picme.apk` |
| `:runtime-core` | Android Library | Agent Runtime：AgentOrchestrator、CapabilityRegistry、PrivacyGuard、远程推理管道（tool_calls）、语音 ASR、VLM 打标引擎 | `runtime-core.aar` |
| `:engines:beauty-api` | Android Library | 美颜系统纯契约层：BeautySettings、FaceDetector、FilterType 等 | `beauty-api.aar` |
| `:engines:beauty-engine` | Android Library | 自研 GPU 美颜引擎：OpenGL ES + EGL 渲染管线、人脸检测适配器 | `beauty-engine.aar` |
| `:engines:mnn-core` | Android Library | MNN 推理运行时共享模块（人脸检测 + VLM 打标共享）：`libMNN.so`、`libOpenCL.so`、MnnResourceManager、MnnGlobalReleaseLock | `mnn-core.aar` |
| `:engines:sentencepiece` | Android Library | SentencePiece tokenizer JNI 封装（OPUS-MT 翻译专用，与 LLM 无关）：`libsentencepiece_android.so` | `sentencepiece.aar` |
| `server/` | Ktor Application | AI 网关、账号体系、管理后台、推荐引擎、限流、COS 存储 | `picme-server.jar` |

---

## 2. 模块依赖图

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                          应用层 (Application)                                                          │
│                                                         ┌─────────────────┐                                                         │
│                                                         │   :androidApp   │                                                         │
│                                                         │  PoLang 主应用  │                                                         │
│                                                         └────────┬────────┘                                                         │
└─────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────┘
            │                          │                          │                          │                          │
            ▼                          ▼                          ▼                          ▼                          ▼
┌────────────────────────┐ ┌────────────────────────┐ ┌────────────────────────┐ ┌────────────────────────┐ ┌────────────────────────┐
│  :engines:beauty-api   │ │ :engines:beauty-engine │ │     :runtime-core      │ │   :engines:mnn-core    │ │ :engines:sentencepiece │
│      美颜 API契约      │ │      美颜引擎实现      │ │     Agent Runtime      │ │      MNN 共享模块      │ │     SentencePiece      │
└───────────┬────────────┘ └───────────┬────────────┘ └───────────┬────────────┘ └───────────┬────────────┘ └────────────────────────┘
            │                          │                          │                          │
            │                          │                          ▼                          │
            │                          │ ┌────────────────────────┬───────────────────────┐  │
            │                          │ │ ai.koog:koog-agents（JetBrains Koog 外部依赖） │  │
            │                          │ │ OpenAI 兼容 client · ToolSet / Agent 策略      │  │
            │                          │ └────────────────────────────────────────────────┘  │
            ▼                          ▼                                                     ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  说明                                                                                                        │
│  • :engines:beauty-engine 不再依赖 :runtime-core，二者通过 :engines:mnn-core 共享 MNN 资源                   │
│  • :androidApp 直接依赖 :engines:mnn-core（PoLangApplication / CameraScreen 使用 MnnResourceManager）        │
│  • :runtime-core 的 Agent 框架为外部依赖 ai.koog:koog-agents（JetBrains Koog），非本仓库模块                 │
│  • server/ 为独立 Ktor 工程，不纳入 Android settings.gradle，Monorepo 管理                                   │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 依赖方向说明

- **无循环依赖**：所有模块依赖构成有向无环图（DAG）。
- **`:engines:beauty-engine` 不再依赖 `:runtime-core`**：通过 `:engines:mnn-core` 共享 MNN 资源后，视觉引擎与 Agent Runtime 解耦。
- **`:androidApp` 直接依赖 `:engines:mnn-core`**：因为 `PoLangApplication` 和 `CameraScreen` 直接调用 `MnnResourceManager`。
- **Agent 框架为外部依赖**：`:runtime-core` 的 LLM 编排基于 JetBrains Koog（`ai.koog:koog-agents`，Maven 外部依赖，非本仓库模块）。

---

## 3. Native SO 归属图

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                           项目内部 Native 库                                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────┐    ┌─────────────────────────────┐ │
│  │           :engines:mnn-core         │    │        :runtime-core        │ │
│  │  ┌─────────────────────────────┐   │    │  ┌───────────────────────┐  │ │
│  │  │ libMNN.so           (7.2 MB)│◄───┼────┤  │ libagent_native.so    │  │ │
│  │  └─────────────────────────────┘   │    │  │ VLM 打标 JNI 桥       │  │ │
│  │            ▲                       │    │  └───────────────────────┘  │ │
│  │            │                        │    └─────────────────────────────┘ │
│  │  ┌─────────────────────────────┐   │                  ▲                  │
│  │  │ libOpenCL.so        (96 KB) │◄───┼──────────────────┘                  │
│  │  └─────────────────────────────┘   │           :androidApp 启动预加载     │
│  └─────────────────────────────────────┘                                      │
│         ▲                              ▲                                      │
│         │                              │                                       │
│         │         ┌─────────────────────────────┐                             │
│         │         │   :engines:beauty-engine    │                             │
│         │         │  ┌───────────────────────┐  │                             │
│         └─────────┤  │ libbeauty_native.so   │  │                             │
│                   │  │ 人脸检测 JNI 桥接      │  │                             │
│                   │  └───────────────────────┘  │                             │
│                   └─────────────────────────────┘                             │
│                                                                              │
│  ┌─────────────────────────────────────┐    ┌─────────────────────────────┐ │
│  │        :engines:sentencepiece       │    │           :androidApp       │ │
│  │  ┌─────────────────────────────┐   │    │  (聚合所有 SO 到 APK)        │ │
│  │  │ libsentencepiece_android.so │◄───┼────┤                             │ │
│  │  └─────────────────────────────┘   │    └─────────────────────────────┘ │
│  └─────────────────────────────────────┘                                      │
├──────────────────────────────────────────────────────────────────────────────┤
│                           外部 AAR 携带的 Native 库                           │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌────────────────────────┐│
│  │ Sherpa-ONNX AAR     │  │ MediaPipe AAR       │  │ ML Kit AAR             ││
│  │ libsherpa-onnx-*.so │  │ libmediapipe_tasks_ │  │ libmlkit*.so           ││
│  │ libonnxruntime.so   │  │ vision_jni.so       │  │                        ││
│  └──────────┬──────────┘  └──────────┬──────────┘  └───────────┬────────────┘│
│             └────────────────────────┼─────────────────────────┘             │
│                                      ▼                                        │
│                              ┌───────────────┐                               │
│                              │  :androidApp  │                               │
│                              │ libonnxruntime│                               │
│                              │ 通过 pickFirsts│                               │
│                              │ 解决双来源冲突 │                               │
│                              └───────────────┘                               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### SO 归属说明

| SO | 归属模块 |  consumers | 备注 |
|----|----------|-----------|------|
| `libMNN.so` | `:engines:mnn-core` | `:runtime-core`、`:engines:beauty-engine` | 唯一来源，避免 AAR 级重复 |
| `libOpenCL.so` | `:engines:mnn-core` | `:androidApp`（启动预加载） | OpenCL ICD Loader |
| `libagent_native.so` | `:runtime-core` | `:androidApp` | VLM 打标 JNI 桥（Qwen3-VL） |
| `libbeauty_native.so` | `:engines:beauty-engine` | `:androidApp` | 人脸检测 JNI 桥接 |
| `libsentencepiece_android.so` | `:engines:sentencepiece` | `:androidApp` | 分词器 JNI |
| `libonnxruntime.so` | 外部（Sherpa-ONNX / onnxruntime-android） | `:androidApp` | 通过 `pickFirsts` 解决双来源冲突；NIMA / eDifFIQA 美学打分（NNAPI 加速，人物封面选择）亦走 ONNX Runtime |
| `libsherpa-onnx-*.so` | Sherpa-ONNX AAR | `:androidApp` | ASR / KWS |
| `libmediapipe_tasks_vision_jni.so` | MediaPipe AAR | `:androidApp` | 人脸 landmark |
| `libmlkit*.so` | ML Kit AAR | `:androidApp` | OCR 文字识别（图像标签与人脸检测已移除） |

---

## 4. 关键类所在模块

| 类 / 对象 | 所在模块 | 包路径 |
|-----------|----------|--------|
| `AgentOrchestrator` | `:runtime-core` | `com.mamba.picme.agent.core.facade` |
| `CapabilityRegistry` | `:runtime-core` | `com.mamba.picme.agent.core.runtime.capability` |
| `PrivacyGuard` | `:runtime-core` | `com.mamba.picme.agent.core.runtime.policy` |
| `MemoryManager` | `:runtime-core` | `com.mamba.picme.agent.core.platform.storage` |
| `SceneManager` | `:runtime-core` | `com.mamba.picme.agent.core.runtime.state` |
| `RemoteReActAgent` | `:runtime-core` | `com.mamba.picme.agent.core.inference.remote.react` |
| `LocalLlmEngine` / `MnnLlmClient`（VLM 打标专用，仅 `imageInference`） | `:runtime-core` | `com.mamba.picme.agent.core.inference.local.llm` |
| `MnnResourceManager` / `MnnGlobalReleaseLock`（人脸检测 + VLM 打标共享） | `:engines:mnn-core` | `com.mamba.picme.mnn` |
| `MnnFaceDetector` / `MnnFaceEmbedder` | `:engines:beauty-engine` | `com.mamba.picme.beauty.internal.facedetect.mnn` |
| `FaceDetectorManager` | `:engines:beauty-engine` | `com.mamba.picme.beauty.internal.facedetect` |
| `BeautyPreviewEngine` | `:engines:beauty-engine` | `com.mamba.picme.beauty.api` |
| `NimaScorer` / `EdiffiqaScorer`（ONNX/NNAPI 美学与人脸质量打分） | `:androidApp` | `com.mamba.picme.domain.aesthetic` |
| `CoverSelector` / `AestheticScoreWorker`（人物封面选择） | `:androidApp` | `com.mamba.picme.domain.aesthetic` |
| `SentencePieceProcessor` | `:engines:sentencepiece` | `com.mamba.picme.sentencepiece` |

---

## 5. 架构红线

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                          依赖方向红线                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [R1] :engines:beauty-engine 禁止依赖 :runtime-core                 │   │
│  │       视觉引擎经 :engines:mnn-core 共享 MNN，不反向耦合 Runtime     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [R2] :runtime-core 禁止依赖 :androidApp 业务类型                   │   │
│  │       Agent Runtime 保持平台无关，可被任意应用模块复用              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [R3] :engines:beauty-api 零第三方依赖                              │   │
│  │       仅 Kotlin stdlib + Android graphics，作为纯契约模块           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [R4] :androidApp 禁止依赖 :engines:beauty-engine:render 内部实现   │   │
│  │       App 仅通过 :engines:beauty-engine:api/ 能力契约消费视觉能力   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  [R5] :engines:mnn-core 不依赖 :runtime-core、:engines:beauty-engine│   │
│  │       Native 共享模块必须保持底层独立，避免循环依赖                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **视觉-运行时解耦** | `:engines:beauty-engine` 不直接依赖 `:runtime-core` | `./gradlew :engines:beauty-engine:dependencies` 无 `:runtime-core` |
| **API 契约纯净** | `:engines:beauty-api` 仅 Kotlin stdlib + Android graphics | 依赖树检查 |
| **Native 共享收敛** | MNN SO 由 `:engines:mnn-core` 唯一提供 | AAR 内容检查 |

---

## 6. 编译验证

```bash
# 全量构建
./gradlew :androidApp:assembleDebug

# 反向依赖检查
./gradlew :engines:beauty-engine:dependencies --configuration releaseRuntimeClasspath | grep "runtime-core" || echo "PASS: no runtime-core dependency"
```

> **维护者**：项目开发者、AI Agent
> **最后更新**：2026-08-03

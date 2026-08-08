# :engines:agent-native 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:engines:agent-native` 模块的实现细节。
> - 顶层治理规则以根目录 `AGENTS.md` 为准。

**模块定位**：Agent 端侧 VLM 推理 JNI 桥（`libagent_native.so`）构建模块
**主要维护者**：项目开发者
**阅读对象**：RD、AI Agent
**版本**：1.0
**最后更新**：2026-08-08
**状态**：生效中

---

## 1. 模块概述

`:engines:agent-native` 是 **端侧 VLM（MNN-LLM）推理的 CMake JNI 构建模块**（`com.android.library`），
承载 `llm_jni_bridge.cpp` 的编译与 `libagent_native.so` 打包。Kotlin 侧 JNI 客户端
（`MnnLlmClient` / `LocalLlmEngine` 等）位于 `:shared` androidMain（包名
`com.mamba.picme.agent.core.inference.local.llm` 不变），经 `implementation` 依赖本模块获得运行时 `.so`。

**存在理由（Phase 4 Task 12）**：AGP 9 KMP 库插件（`com.android.kotlin.multiplatform.library`）
不支持 `externalNativeBuild`（官方文档明确 omit，经 `:shared` DSL 实证报错），
官方推荐做法即「独立 `com.android.library` 模块承载 native 构建，KMP androidMain 依赖之」——本模块即此落地。

## 2. 提供的内容

- `libagent_native.so`（arm64-v8a）：MNN-LLM JNI 桥（`System.loadLibrary("agent_native")`，
  加载点在 `:shared` 的 `MnnLlmClient`）
- `consumer-rules.pro`：JNI keep 规则（`MnnLlmClient` native 方法、`StreamGenerateListener.onToken` 反射调用）

## 3. Native 构建约束

- ABI：`arm64-v8a`；STL：`c++_shared`；平台：`android-24`；CMake 3.22.1
- MNN 头文件：`libs/mnn/include`（自原 `:runtime-core` 迁入，CMake `../../../libs/mnn` 引用）
- MNN 预编译库：引用 `:engines:mnn-core` 的 jniLibs（CMake 相对路径上溯 5 级到仓库根）

## 4. 依赖方向

```
:shared (androidMain) ───→ :engines:agent-native ───→ :engines:mnn-core
```

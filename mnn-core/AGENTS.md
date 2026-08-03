# :mnn-core 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `:mnn-core` 模块的实现细节。
> - 顶层治理规则以根目录 `AGENTS.md` 为准。

**模块定位**：MNN 推理运行时共享模块
**主要维护者**：项目开发者
**阅读对象**：RD、AI Agent
**版本**：1.0
**最后更新**：2026-07-15
**状态**：生效中

---

## 1. 模块概述

`:mnn-core` 是 **MNN 推理运行时共享模块**，为 Android Library。它集中管理 MNN 预编译库（`libMNN.so`、`libOpenCL.so`）和 MNN 资源加载/释放锁，供 `:runtime-core`（VLM 打标）和 `:beauty-engine`（人脸检测）共同依赖。

该模块的独立避免了 `:beauty-engine` 因使用 MNN 而反向依赖 `:runtime-core`。

## 2. 提供的 API

- `MnnResourceManager`：MNN 模型资源路径管理
- `MnnGlobalReleaseLock`：MNN 资源释放全局锁

## 3. Native 库

- `libMNN.so`
- `libOpenCL.so`

## 4. 依赖方向

```
:runtime-core  ───→ :mnn-core ←─── :beauty-engine
```

## 5. Native 构建约束

- ABI：`arm64-v8a`
- minSdk：24
- STL：`c++_shared`
- CMake：3.22.1
- ndkVersion：28.2.13676358

## 6. 编译验证

```bash
./gradlew :mnn-core:assembleDebug
```

> **维护者**：RD Agent
> **最后更新**：2026-07-06

# sentencepiece + QuickJS iOS 编译验证 Spike 报告（Phase 2.2）

> **日期**：2026-08-07
> **关联**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 2.2
> **结论**：✅ **GO** — sentencepiece iOS 静态库编译通过，QuickJS 通过 KMP klib 零编译可用；~~⚠️ 两者均未做**运行时**调用验证~~ **运行时验证已于 2026-08-07 晚真机完成（两项均 PASS）**，见 §4（2026-08-07 review 修订 → 2026-08-07 晚补验收口）

---

## 1. 验证目标

验证 PoLang 依赖的两个 C/C++ 组件能否在 iOS 上使用：
- **sentencepiece**（0.2.2）：OPUS-MT 翻译模型的本地分词/解码，纯 C++ 库
- **QuickJS**（dokar3/quickjs-kt 1.0.5）：JS 沙箱引擎，Chat 中执行 JS 脚本

## 2. sentencepiece 验证

### 2.1 现有资产

项目 `sentencepiece/src/main/cpp/` 已包含完整的 SentencePiece 0.2.2 C++ 源码 + 第三方依赖（abseil-cpp、protobuf-lite、darts_clone），以及预置的 `cmake/ios.toolchain.cmake`（leetal/ios-cmake）。

现有 `CMakeLists.txt` 为 Android JNI 定制（含 `find_library(android-lib)` / JNI 桥接），不能直接用于 iOS。

### 2.2 iOS 构建方案

编写去 Android/JNI 化的 iOS CMakeLists（`tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt`），只编译核心静态库（`sentencepiece-static` target），链接 abseil + protobuf-lite。

**构建命令**：

```bash
export SPM_CPP=sentencepiece/src/main/cpp
cmake -S tmp/mnn-ios-spike/spm-ios-build \
      -B tmp/mnn-ios-spike/spm-ios-build/build \
  -DCMAKE_TOOLCHAIN_FILE=$SPM_CPP/cmake/ios.toolchain.cmake \
  -DPLATFORM=OS64 -DARCHS=arm64 \
  -DENABLE_BITCODE=0 -DCMAKE_BUILD_TYPE=Release \
  -DDEPLOYMENT_TARGET=17.0

cmake --build tmp/mnn-ios-spike/spm-ios-build/build --target sentencepiece-static -j8
```

### 2.3 结果

| 项 | 值 |
|----|-----|
| 产物 | `libsentencepiece-static.a`（arm64 静态库） |
| 大小 | 1.6MB |
| 符号验证 | ✅ `Encode`/`Decode`/`SentencePieceProcessor` 等 195 个匹配 |
| 编译 warnings | 仅 deprecated `Decode` API 警告（无害） |
| 编译 errors | 零 |

### 2.4 iOS 集成路径

- 将 `libsentencepiece-static.a` + sentencepiece 头文件打包为 XCFramework（或直接 `.a` + headers）
- iOS 侧通过 Swift/ObjC++ C interop 调用 `sentencepiece::SentencePieceProcessor`
- 或在 KMP 的 `iosMain` 中通过 `cinterop` 暴露给 Kotlin/Native

## 3. QuickJS 验证

### 3.1 现有使用方式

项目通过 Gradle 依赖 `io.github.dokar3:quickjs-kt:1.0.5`（KMP 库），消费方式在 `app/src/main/java/com/mamba/picme/features/chat/js/QuickJsEngine.kt`。

### 3.2 KMP iOS 支持验证

`dokar3/quickjs-kt` 1.0.5 在 Maven Central 上已发布全部三个 iOS target 的预编译 klib：

| Target | 产物 | 大小 |
|--------|------|------|
| `iosarm64` | `quickjs-kt-iosarm64-1.0.5-cinterop-quickjs.klib` | 918KB |
| `iossimulatorarm64` | `quickjs-kt-iossimulatorarm64-1.0.5-cinterop-quickjs.klib` | 917KB |
| `iosx64` | `quickjs-kt-iosx64-1.0.5-cinterop-quickjs.klib` | 927KB |

**结论**：QuickJS **零额外编译**。KMP 项目声明 `iosArm64()` target 后，Gradle 自动拉取对应 klib（内含预编译 QuickJS C 代码）。现有的 `QuickJsEngine.kt` 代码在 `shared/iosMain` 中可直接复用，无需任何 C/C++ 编译工作。

### 3.3 对比 sentencepiece

| 维度 | sentencepiece | QuickJS |
|------|---------------|---------|
| 集成方式 | 手动编译 C++ → 静态库 → C interop | KMP klib（Gradle 自动拉取） |
| 编译工作 | 需 iOS CMakeLists（已完成） | 零 |
| iOS 维护成本 | 每次升级需重新编译 | 跟随 KMP 依赖自动升级 |
| 跨端共享路径 | shared/iosMain cinterop 或 ObjC++ bridge | shared/commonMain 直接可用 |

## 4. 对改造计划的影响

### ✅ 确认可行

- **sentencepiece iOS**：CMake + ios.toolchain 编译通过，1.6MB 静态库，符号完整
- **QuickJS iOS**：KMP klib 零编译可用，现有 `QuickJsEngine.kt` 可直接进 shared commonMain

### ⚠️ 待补运行时验证（2026-08-07 review 修订：编译/符号通过 ≠ 运行时可用）

> ✅ **两项运行时补验已于 2026-08-07 晚在 Phase 2.3 spike（`tmp/kmp-koog-spike`）真机完成**，详见 `2026-08-07-kmp-koog-spike-design.md` §7.4

- **sentencepiece**：~~本次仅验证 `.a` 编译与 195 个符号存在，未执行实际 encode/decode~~ ✅ 真机 PASS——`Encode("hello world")` → `[▁he][ll][o][▁world]`（4 token）→ `Decode` 完全一致（iPhone iOS 26.6，ObjC++ 桥接合并静态库 `libspm_ios.a`）
- **QuickJS**：~~本次仅验证 klib 在 Maven 存在，未在 iOS 真机/模拟器执行 JS~~ ✅ 真机 PASS——quickjs-kt 1.0.5 在 iOS 执行 `evaluate("1+2")` 返回 3
- **cinterop 路径**：✅ 已有结论——**不走 cinterop，采用 ObjC++ 直接链接静态库**（libtool 合并 `libsentencepiece-static.a` + 83 个 absl 静态库为单个 `libspm_ios.a`，protobuf-lite 已在 sentencepiece 库内；Xcode 侧 3 条 header search path + 50 行 ObjC++ 桥接即可）

### 📋 对 Phase 4（shared KMP 抽取）的输入

- sentencepiece 的 iOS 集成方式建议用 `cinterop`：在 `shared/build.gradle.kts` 的 `iosArm64` target 中配置 cinterop def 文件指向 `.a` + headers，Kotlin/Native 自动生成绑定
- QuickJS 的 `QuickJsEngine.kt` 可直接迁入 `shared/commonMain`——它是纯 Kotlin 代码，依赖的 `com.dokar.quickjs` 是 KMP 库
- sentencepiece 的 JNI 层（`SentencePieceProcessor.kt` + `sentencepiece_jni.cpp`）在 iOS 上不适用，iOS 侧需走 cinterop 或重写 expect/actual

## 5. Spike 产物

- **iOS CMakeLists**：`tmp/mnn-ios-spike/spm-ios-build/CMakeLists.txt`
- **编译产物**：`tmp/mnn-ios-spike/spm-ios-build/build/libsentencepiece-static.a`（1.6MB，arm64）
- **构建脚本**：内联在本报告 §2.2

## 6. 结论

**GO（编译/符号级）**。sentencepiece iOS 静态库编译通过，QuickJS 通过 KMP klib 零编译可用；运行时调用验证待补（见 §4，成本极低，可并入 Phase 2.3 一并完成），补验通过后 Phase 2.2 方算全绿。

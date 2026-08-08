---
name: metal-render-expert
description: |
  Metal/MSL 渲染管线诊断（美颜相机宿主）：Metal 黑屏/shader/PSO、GLSL→MSL 翻译、美颜 pass 链、CVMetalTextureCache。Use when debugging iOS Metal rendering, translating GLSL shaders to MSL, or working on the beauty camera pipeline.
version: 1.0.0
created: 2026-08-08
updated: 2026-08-08
maintainer: [RD] 全栈工程师
tags:
  - ios
  - metal
  - msl
  - rendering
  - shader
  - beauty
---


# Metal 渲染专家 Skill

> **定位**：Metal / MSL 渲染管线诊断（美颜相机宿主），对标 Android av-gl-expert + egl-state-machine。
> **触发时机**：Metal 黑屏 / shader 编译失败 / 渲染管线问题、GLSL→MSL 翻译、美颜 pass 链调试。

## 核心原则

1. **Metal 无 EGL**：无 context / makeCurrent / swapBuffers；生命周期由 `MTLDevice` + `CAMetalLayer` drawable 驱动。
2. **shader 翻译逐行纪律**：GLSL→MSL 不是宏替换，地址空间 / 类型 / 函数全不同（见下）。
3. **零拷贝相机纹理**：`CVMetalTextureCache` 直接包 `CVPixelBuffer`，禁止回读 CPU（对标 Android 零拷贝 GPU 管线红线）。

## 渲染链路（2.4 spike 已验证）

```
AVCaptureSession（720p，YUV bi-planar）
  → CVMetalTextureCache（Y=R8Unorm + UV=RG8Unorm，两张纹理）
  → 美颜 pass 链（磨皮/美白/瘦脸/大眼 + LUT）
  → MTKView drawable（CAMetalLayer）
```

参照：`tmp/beauty-metal-spike/main.mm`（298 行完整管线）+ `Shaders.metal`（YUV→RGB 基线），重写为 Swift。

## Metal 生命周期（对照 EGL）

| Android（GLES/EGL） | iOS（Metal） |
|---------------------|--------------|
| `EGLContext` + `makeCurrent` | 无 —— 命令编码到 `MTLCommandBuffer`，无全局上下文 |
| `EGLSurface` / `swapBuffers` | `CAMetalLayer.nextDrawable()` + `presentDrawable` |
| `GLProgram` / uniform location | `MTLRenderPipelineState`（PSO）+ `setFragmentBytes` / `setBuffer` |
| `glFramebuffer` / FBO pool | `MTLTexture`（usage: renderTarget）+ render pass descriptor |
| `glGetError` | 无运行时错误枚举；靠 PSO 编译期 + `MTLCommandBuffer.error` |

## GLSL→MSL 翻译纪律

| GLSL | MSL | 说明 |
|------|-----|------|
| `const float x`（局部标量） | `constexpr float x` | `constant` 是地址空间，不能用于局部！ |
| `uniform vec4 uColor` | `BeautyUniforms` struct，走 `setFragmentBytes` | 打包成 struct 一次下发 |
| `uniform vec2 uFacePoints[212]` | `MTLBuffer`（device address space） | 大数组走 buffer，不走 bytes |
| `mat3 uTextureTransform` | **删除** | SurfaceTexture 矩阵 iOS 不需要 |
| `clamp(x, 0.0, 1.0)` | `saturate(x)` | 更地道；`clamp` 也可 |
| `texture2D(uTex, vUV)` | `tex.sample(sampler, vUV)` | sampler 是独立对象 |
| `varying`（in/out） | `[[vertex_in]]` / `stage_in` + struct | 属性绑定用 `[[attribute(n)]]` |
| shader 源码 | `device.makeLibrary(source: msl, options: nil)` | concat 多段后一次性编译 |

错误检查：

```swift
do {
    let lib = try device.makeLibrary(source: mslSource, options: nil)
} catch {
    // MTLCompileError —— 错误行号在 error.localizedDescription
}
```

## 帧同步

Android framesync 的 `FrameId` 时间戳源 → iOS 改用 `CMSampleBuffer.presentationTime`（AVFoundation 时间戳），对齐人脸检测与渲染。

## 诊断检查清单

- [ ] drawable 拿到了？（`nextDrawable` 返回 nil → layer 配置 / 帧节流）
- [ ] PSO 创建成功？（`makeLibrary` + renderPipelineState 抛错？）
- [ ] 纹理 usage 含 `.shaderRead` / `.renderTarget`？
- [ ] 美颜 uniform 每帧 `setFragmentBytes`？（漏设 → 全黑 / 上一帧残留）
- [ ] 人脸点 buffer 对齐 106 / 212 数量？（见 [mnn-ios-integration](/mnn-ios-integration)）

## 相关文件

- [av-gl-expert](/av-gl-expert) — Android OpenGL ES 诊断对照
- [egl-state-machine](/egl-state-machine) — EGL 状态机（Android 侧）
- [coordinate-system-standard](/coordinate-system-standard) — 106pt 坐标体系（双端同源）
- [mnn-ios-integration](/mnn-ios-integration) — 人脸检测前置
- spike：`tmp/beauty-metal-spike/main.mm`

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5.4 美颜宿主重写） |

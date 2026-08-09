// beauty.metal — 美颜宿主 fragment（MVP 子集：美白 + warp 调度）
//
// 翻译源:
//   uniforms_2d.glsl → BeautyUniforms struct（MVP 子集）
//   skin.glsl whitenSkin() (行 81-112；spike 已逐行验证)
//   main.glsl (MVP 简化版：warp → whitenSkin；磨皮/LUT 由独立 pass 处理)
//
// 翻译纪律:
//   - GLSL `const float` → `constexpr`（`constant` 是地址空间）
//   - `texture2D(t, uv)` → `t.sample(sampler, uv)`
//   - `clamp(x,0.,1.)` → `saturate(x)`
//   - `vec3/vec4` → `float3/float4`
//   - warp 函数从 warp.metal 的 computeWarpedUv 调用（本文件 #include 不可用，
//     需在 device.makeLibrary(source:) 时与 warp.metal concat 编译——见 BeautyRenderer）

#include <metal_stdlib>
using namespace metal;

// ===== BeautyUniforms（Metal 无跨文件 struct 链接，每个 .metal 文件内重复定义）=====
struct BeautyUniforms {
    float smoothing;
    float whitening;
    float sharpen;
    float bigEyes;
    float slimFace;
    float hasFace;
    float aspectRatio;
    int   useGpupixelWarp;
};

// Vout（Metal 每文件独立编译，struct 在每个 .metal 内重复定义）
struct Vout {
    float4 position [[position]];
    float2 uv;
};
// quad_vertex 定义在 yuv.metal（linker 解析）

// ===== 对应 skin.glsl whitenSkin()（spike 逐行验证版）=====
// [图像坐标系] 纯像素算术，无纹理采样、无 GL 状态依赖
static float3 whitenSkin(float3 rgb, float intensity, float mask) {
    if (intensity < 0.001 || mask < 0.01) return rgb;
    // 步骤1: 亮度提升（GPUPixel levelBlack/levelRangeInv）
    constexpr float levelBlack = 0.0258820;
    constexpr float levelRangeInv = 1.02657;
    float3 leveled = saturate((rgb - float3(levelBlack)) * levelRangeInv);

    // 步骤2: 混合原始颜色和提升后的颜色
    float3 brightened = mix(rgb, leveled, 0.5);

    // 步骤3: 应用美白强度
    float whitenAlpha = intensity * mask;
    float3 whitened = mix(rgb, brightened, whitenAlpha);

    // 步骤4: 轻微提升蓝色通道（冷白皮效果）
    whitened.b *= 1.0 + whitenAlpha * 0.05;

    // 步骤5: 轻微降低红色通道（减少黄气）
    whitened.r *= 1.0 - whitenAlpha * 0.03;

    return saturate(whitened);
}

// ===========================================================================
// warp 函数声明（定义在 warp.metal，concat 编译时同 TU 可见）
// BeautyUniforms 与 WarpUniforms 字段布局完全一致（同一 struct 两种 typedef）。
// ===========================================================================
float2 computeWarpedUv(float2 uv, constant BeautyUniforms& uni, constant float* facePoints);

// ===========================================================================
// beauty fragment（MVP：美白直通 + warp 调度）
// 完整管线（yuv→rgb → smoothing → beauty 上屏）的最后一 pass
// 输入纹理 = 前一 pass 的输出（直渲 rgbTex 或磨皮 smoothingTexture）
// ===========================================================================
fragment float4 beauty_fragment(
    Vout in [[stage_in]],
    texture2d<float, access::sample> inputTexture [[texture(0)]],
    sampler bilinear [[sampler(0)]],
    constant BeautyUniforms& uni [[buffer(0)]],
    constant float* facePoints [[buffer(1)]])  // 可选：hasFace>0.5 时 warp 需要
{
    // warp 形变 UV（对应 main.glsl 的 warp 调用序）
    float2 warpedUv = in.uv;
    if (uni.hasFace > 0.5 && uni.useGpupixelWarp > 0) {
        warpedUv = computeWarpedUv(in.uv, uni, facePoints);
    }

    float4 color = inputTexture.sample(bilinear, warpedUv);
    float3 rgb = color.rgb;

    // 美白（mask=1.0；完整版用 skinMask，MVP 直通全画面）
    rgb = whitenSkin(rgb, uni.whitening, 1.0);

    return float4(rgb, color.a);
}

// lut.metal — 色彩滤镜 pass（ColorMatrix + ColorGrade）
//
// 翻译源:
//   main.glsl L78-86 (ColorMatrix filter)
//   colorgrade.glsl (exposure/contrast/saturation/temperature/tint/brightness/channel adj)
//   uniforms_2d.glsl (uCMRow0..3 / uCMOffset / uHasColorMatrix / uExposure 等)
//
// FilterType（9 款，S5 双端一致——shared commonMain FilterType.kt ordinal）:
//   0=NONE, 1=LEICA_CLASSIC, 2=LEICA_VIBRANT, 3=LEICA_BW, 4=FILM_GOLD,
//   5=FILM_FUJI, 6=VINTAGE, 7=COOL, 8=WARM
//
// 实现方式（与 Android 一致）: ColorMatrix 4×5 矩阵，非纹理 LUT。
// Android FilterTypeExt.kt toAndroidColorMatrix() 定义每个 FilterType 的矩阵值，
// Swift 侧 FilterColorMatrix.swift 逐值照抄。
//
// 翻译纪律:
//   - GLSL `clamp(x,0.,1.)` → `saturate(x)`
//   - `vec3/vec4` → `float3/float4`
//   - `gl_FragColor` → return
//   - `pow` / `mix` / `dot` 同名保留
//   - vertex 复用 quad_vertex（concat 编译时共享）

#include <metal_stdlib>
using namespace metal;

// ===== LUT/ColorMatrix uniforms =====
struct ColorGradeUniforms {
    float4 cmRow0;       // uCMRow0   (ColorMatrix row 0: r,g,b,a coeffs)
    float4 cmRow1;       // uCMRow1
    float4 cmRow2;       // uCMRow2
    float4 cmRow3;       // uCMRow3
    float4 cmOffset;     // uCMOffset (r,g,b,a offsets)
    float hasColorMatrix; // uHasColorMatrix (0 or 1)

    // colorgrade.glsl 调色参数
    float exposure;      // uExposure
    float contrast;      // uContrast
    float saturation;    // uSaturation
    float temperature;   // uTemperature
    float tint;          // uTint
    float brightness;    // uBrightness
    float warmth;        // uWarmth
    float redAdj;        // uRedAdj
    float greenAdj;      // uGreenAdj
    float blueAdj;       // uBlueAdj
    float intensity;     // FilterType 混合强度（0=原图，1=完全应用）
};

// Vout（Metal 每文件独立编译，struct 在每个 .metal 内重复定义）
struct Vout {
    float4 position [[position]];
    float2 uv;
};
// quad_vertex 定义在 yuv.metal（linker 解析）

// ===== colorgrade.glsl 翻译 =====
static float3 applyColorGrade(float3 color, constant ColorGradeUniforms& uni) {
    color *= pow(2.0, uni.exposure);
    color = (color - 0.5) * uni.contrast + 0.5;
    float luma = dot(color, float3(0.299, 0.587, 0.114));
    color = mix(float3(luma), color, uni.saturation);
    color.r += uni.temperature * 0.01;
    color.b -= uni.temperature * 0.01;
    color.g += uni.tint * 0.005;
    color.b -= uni.tint * 0.005;
    color += uni.brightness;
    color.r *= uni.redAdj;
    color.g *= uni.greenAdj;
    color.b *= uni.blueAdj;
    return saturate(color);
}

// ===== ColorMatrix（main.glsl L78-86 翻译）=====
static float3 applyColorMatrix(float4 src, constant ColorGradeUniforms& uni) {
    float r = dot(uni.cmRow0, src) + uni.cmOffset.r;
    float g = dot(uni.cmRow1, src) + uni.cmOffset.g;
    float b = dot(uni.cmRow2, src) + uni.cmOffset.b;
    return saturate(float3(r, g, b));
}

// ===== LUT fragment 入口 =====
// 输入: 前一 pass 输出纹理 (texture0)
// 输出: ColorMatrix + ColorGrade 调整后颜色
// vertex 复用 quad_vertex
fragment float4 lut_fragment(
    Vout in [[stage_in]],
    texture2d<float, access::sample> inputTexture [[texture(0)]],
    sampler bilinear [[sampler(0)]],
    constant ColorGradeUniforms& uni [[buffer(0)]])
{
    // 🔴 中间 pass 方向约定同 smoothing.metal：quad_vertex 每 pass 垂直翻转一次，
    // 采样 y 取反使本 pass 输出与输入同向，保持末级 beauty pass「翻转相消」不变。
    float4 src = inputTexture.sample(bilinear, float2(in.uv.x, 1.0 - in.uv.y));
    float3 color = src.rgb;

    // ColorMatrix（FilterType 通过此路径生效）
    if (uni.hasColorMatrix > 0.5) {
        color = applyColorMatrix(src, uni);
    }

    // ColorGrade（手动调色参数）
    color = applyColorGrade(color, uni);

    // intensity 混合（0=原图，1=完全应用）
    float3 result = mix(src.rgb, color, uni.intensity);
    return float4(result, src.a);
}

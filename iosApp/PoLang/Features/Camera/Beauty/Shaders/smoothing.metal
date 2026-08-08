// smoothing.metal — 磨皮 pass（GLSL→MSL 翻译）
//
// 翻译源: engines/beauty-engine/src/main/assets/shaders/pass_smoothing.glsl (195 行)
// uniform: uInputTexture / uLookUpGray / uLookUpOrigin / uLookUpSkin / uLookUpLight
//          + uBlurAlpha / uSharpen / uWhiten / uWidthOffset / uHeightOffset
//
// 翻译纪律:
//   - GLSL `const float` → `constexpr`
//   - `texture2D(t, uv)` → `t.sample(sampler, uv)`
//   - `clamp(x,0.,1.)` → `saturate(x)`
//   - `vec2/vec3` → `float2/float3`
//   - `gl_FragColor` → return value
//   - `varying vec2 vTextureCoord` → `Vout in [[stage_in]]`
//   - vertex 复用 beauty_vertex（同名定义在 beauty.metal，concat 时共享）

#include <metal_stdlib>
using namespace metal;

// 磨皮 pass 专用 uniforms
struct SmoothingUniforms {
    float blurAlpha;     // uBlurAlpha  (磨皮强度 0~1)
    float sharpen;       // uSharpen    (锐化强度 0~1)
    float whiten;        // uWhiten     (美白强度 0~1，LUT 路径用)
    float widthOffset;   // uWidthOffset  (1.0 / width)
    float heightOffset;  // uHeightOffset (1.0 / height)
};

// 顶点输出（guard: concat 去重）
#ifndef POLANG_VOUT_DEFINED
#define POLANG_VOUT_DEFINED
struct Vout {
    float4 position [[position]];
    float2 uv;
};
#endif

// vertex 复用 quad_vertex（定义在其他 .metal；concat 编译时共享）

// GPUPixel 原始常量
// ⚠️ Metal program-scope 变量须在 constant 地址空间；此处移入函数作用域用 constexpr（见下）

// ===== 优化磨皮：扩展半径双边滤波 + 皮肤检测 + 自适应混合 =====
static float3 smoothSkin(
    float2 uv,
    float intensity,
    texture2d<float, access::sample> inputTexture,
    sampler bilinear,
    SmoothingUniforms uni)
{
    if (intensity < 0.001) {
        return inputTexture.sample(bilinear, uv).rgb;
    }

    float3 centerRgb = inputTexture.sample(bilinear, uv).rgb;
    float centerL = dot(centerRgb, float3(0.299, 0.587, 0.114));

    // 扩展采样半径：根据强度调整
    float radiusScale = 3.0 + intensity * 5.0;
    float2 texelSize = float2(uni.widthOffset, uni.heightOffset) * radiusScale;

    // 7x7 采样核（扩展半径双边滤波）
    float3 sum = centerRgb;
    float wSum = 1.0;

    float sigmaSpatial = 3.5;
    float sigmaSpatialSq = sigmaSpatial * sigmaSpatial * 2.0;
    float sigmaRange = 0.06 + intensity * 0.10;
    float sigmaRangeSq = sigmaRange * sigmaRange * 2.0;

    for (int x = -3; x <= 3; x++) {
        for (int y = -3; y <= 3; y++) {
            if (x == 0 && y == 0) continue;
            float2 offset = float2(float(x), float(y)) * texelSize;
            float3 sRgb = inputTexture.sample(bilinear, uv + offset).rgb;
            float sL = dot(sRgb, float3(0.299, 0.587, 0.114));

            float dL = sL - centerL;
            float spatialDistSq = float(x * x + y * y);

            float spatialW = exp(-spatialDistSq / sigmaSpatialSq);
            float rangeW = exp(-(dL * dL) / sigmaRangeSq);

            float w = spatialW * rangeW;
            sum += sRgb * w;
            wSum += w;
        }
    }

    float3 blurColor = sum / wSum;

    // 皮肤检测：YCbCr 空间 Cb∈[77,127], Cr∈[133,173] → 归一化后判断
    float cb = -0.169 * centerRgb.r - 0.331 * centerRgb.g + 0.500 * centerRgb.b + 0.5;
    float cr = 0.500 * centerRgb.r - 0.419 * centerRgb.g - 0.081 * centerRgb.b + 0.5;
    float skinMask = smoothstep(0.0, 1.0, 1.0 - abs(cb - 0.52) * 8.0) *
                     smoothstep(0.0, 1.0, 1.0 - abs(cr - 0.58) * 8.0);

    // 边缘检测：Sobel 算子
    float2 texel = float2(uni.widthOffset, uni.heightOffset) * 2.0;
    float tl = dot(inputTexture.sample(bilinear, uv + float2(-texel.x, -texel.y)).rgb, float3(0.299, 0.587, 0.114));
    float t  = dot(inputTexture.sample(bilinear, uv + float2(0.0, -texel.y)).rgb, float3(0.299, 0.587, 0.114));
    float tr = dot(inputTexture.sample(bilinear, uv + float2(texel.x, -texel.y)).rgb, float3(0.299, 0.587, 0.114));
    float l  = dot(inputTexture.sample(bilinear, uv + float2(-texel.x, 0.0)).rgb, float3(0.299, 0.587, 0.114));
    float r  = dot(inputTexture.sample(bilinear, uv + float2(texel.x, 0.0)).rgb, float3(0.299, 0.587, 0.114));
    float bl = dot(inputTexture.sample(bilinear, uv + float2(-texel.x, texel.y)).rgb, float3(0.299, 0.587, 0.114));
    float b  = dot(inputTexture.sample(bilinear, uv + float2(0.0, texel.y)).rgb, float3(0.299, 0.587, 0.114));
    float br = dot(inputTexture.sample(bilinear, uv + float2(texel.x, texel.y)).rgb, float3(0.299, 0.587, 0.114));

    float edgeX = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float edgeY = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float edgeStrength = length(float2(edgeX, edgeY));

    // 边缘保留：边缘越强，磨皮越弱
    float edgeFactor = smoothstep(0.0, 0.3, 0.15 - edgeStrength);

    // 自适应混合
    float blendAlpha = intensity * skinMask * edgeFactor;
    blendAlpha = clamp(blendAlpha, 0.0, intensity * 0.85);

    float3 result = mix(centerRgb, blurColor, blendAlpha);

    // 细节增强
    float detailStrength = 0.08 * intensity * skinMask;
    float3 highPass = centerRgb - blurColor;
    result = result + detailStrength * highPass;
    result = saturate(result);

    return result;
}

// ===== 磨皮 fragment（对应 pass_smoothing.glsl main()）=====
// 输入: 原图 (texture0) + 4 LUT (texture1..4)
// vertex 复用 beauty_vertex（concat 编译时同名共享）
fragment float4 smoothing_fragment(
    Vout in [[stage_in]],
    texture2d<float, access::sample> uInputTexture [[texture(0)]],
    texture2d<float, access::sample> uLookUpGray   [[texture(1)]],
    texture2d<float, access::sample> uLookUpOrigin [[texture(2)]],
    texture2d<float, access::sample> uLookUpSkin   [[texture(3)]],
    texture2d<float, access::sample> uLookUpLight  [[texture(4)]],
    sampler bilinear [[sampler(0)]],
    constant SmoothingUniforms& uni [[buffer(0)]])
{
    float2 uv = in.uv;
    float4 iColor = uInputTexture.sample(bilinear, uv);
    float3 color = iColor.rgb;

    // ========== 磨皮 ==========
    if (uni.blurAlpha > 0.0) {
        color = smoothSkin(uv, uni.blurAlpha, uInputTexture, bilinear, uni);
    }

    // ========== 美白（LUT 路径）==========
    if (uni.whiten > 0.0) {
        // GPUPixel 常量（GLSL `const` → MSL `constexpr`，函数作用域）
        constexpr float levelRangeInv = 1.02657;
        constexpr float levelBlack = 0.0258820;
        constexpr float lut_alpha = 0.7; // 原名 alpha，避与 MSL 关键字冲突

        float3 colorEPM = color;
        // Level 调整
        color = saturate((colorEPM - float3(levelBlack)) * levelRangeInv);

        // lookUpGray LUT (16x1)
        float3 texel = float3(
            uLookUpGray.sample(bilinear, float2(color.r, 0.5)).r,
            uLookUpGray.sample(bilinear, float2(color.g, 0.5)).g,
            uLookUpGray.sample(bilinear, float2(color.b, 0.5)).b
        );
        texel = mix(color, texel, 0.5);
        texel = mix(colorEPM, texel, lut_alpha);
        texel = saturate(texel);

        // lookUpOrigin LUT (64x64, 4x4 blocks)
        float blueColor = texel.b * 15.0;
        float2 quad1;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        float2 quad2;
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        float2 texPos2 = texel.rg * 0.234375 + 0.0078125;
        float2 texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        float3 newColor1Origin = uLookUpOrigin.sample(bilinear, texPos1).rgb;
        float3 newColor2Origin = uLookUpOrigin.sample(bilinear, texPos2).rgb;
        float3 colorOrigin = mix(newColor1Origin, newColor2Origin, fract(blueColor));
        texel = mix(colorOrigin, color, lut_alpha);

        // lookUpSkin LUT (64x64, 4x4 blocks)
        texel = saturate(texel);
        blueColor = texel.b * 15.0;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        texPos2 = texel.rg * 0.234375 + 0.0078125;
        texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        float3 newColor1 = uLookUpSkin.sample(bilinear, texPos1).rgb;
        float3 newColor2 = uLookUpSkin.sample(bilinear, texPos2).rgb;
        color = mix(newColor1, newColor2, fract(blueColor));
        color = saturate(color);

        // lookUpLight LUT (512x512, 8x8 blocks)
        float blueColorCustom = color.b * 63.0;
        float2 quad1Custom;
        quad1Custom.y = floor(floor(blueColorCustom) / 8.0);
        quad1Custom.x = floor(blueColorCustom) - (quad1Custom.y * 8.0);
        float2 quad2Custom;
        quad2Custom.y = floor(ceil(blueColorCustom) / 8.0);
        quad2Custom.x = ceil(blueColorCustom) - (quad2Custom.y * 8.0);
        float2 texPos1Custom;
        texPos1Custom.x = (quad1Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos1Custom.y = (quad1Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        float2 texPos2Custom;
        texPos2Custom.x = (quad2Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos2Custom.y = (quad2Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        float3 newColor1Light = uLookUpLight.sample(bilinear, texPos1Custom).rgb;
        float3 newColor2Light = uLookUpLight.sample(bilinear, texPos2Custom).rgb;
        float3 colorCustom = mix(newColor1Light, newColor2Light, fract(blueColorCustom));
        color = mix(color, colorCustom, uni.whiten);
    }

    return float4(color, iColor.a);
}

// BeautyPass - 磨皮+美白+LUT 合并Pass
// 简化版：使用9-tap双边滤波近似实现磨皮，无需BoxBlur/BoxHighPass前置Pass
//
// 输入：uInputTexture（原图）
// 输入：uLookUpGray, uLookUpOrigin, uLookUpSkin, uLookUpLight（LUT查找表）
// 输出：磨皮+美白后的图像

precision highp float;

uniform sampler2D uInputTexture;    // 原图
uniform sampler2D uLookUpGray;      // 灰度LUT
uniform sampler2D uLookUpOrigin;    // 原始色调LUT
uniform sampler2D uLookUpSkin;      // 肤色LUT
uniform sampler2D uLookUpLight;     // 美白LUT

uniform float uBlurAlpha;           // 磨皮强度 0~1
uniform float uSharpen;             // 锐化强度 0~1
uniform float uWhiten;              // 美白强度 0~1
uniform float uWidthOffset;         // 1.0 / width
uniform float uHeightOffset;        // 1.0 / height

varying vec2 vTextureCoord;

// GPUPixel 原始常量
const float levelRangeInv = 1.02657;
const float levelBlack = 0.0258820;
const float alpha = 0.7;

// 优化磨皮：扩展半径双边滤波 + 皮肤检测 + 自适应混合
vec3 smoothSkin(vec2 uv, float intensity) {
    if (intensity < 0.001) {
        return texture2D(uInputTexture, uv).rgb;
    }

    vec3 centerRgb = texture2D(uInputTexture, uv).rgb;
    float centerL = dot(centerRgb, vec3(0.299, 0.587, 0.114));

    // 扩展采样半径：根据强度调整，最大覆盖约15-25像素
    float radiusScale = 3.0 + intensity * 5.0;
    vec2 texelSize = vec2(uWidthOffset, uHeightOffset) * radiusScale;

    // 7x7采样核（扩展半径双边滤波）
    vec3 sum = centerRgb;
    float wSum = 1.0;

    float sigmaSpatial = 3.5;
    float sigmaSpatialSq = sigmaSpatial * sigmaSpatial * 2.0;
    float sigmaRange = 0.06 + intensity * 0.10;
    float sigmaRangeSq = sigmaRange * sigmaRange * 2.0;

    for (int x = -3; x <= 3; x++) {
        for (int y = -3; y <= 3; y++) {
            if (x == 0 && y == 0) continue;
            vec2 offset = vec2(float(x), float(y)) * texelSize;
            vec3 sRgb = texture2D(uInputTexture, uv + offset).rgb;
            float sL = dot(sRgb, vec3(0.299, 0.587, 0.114));

            float dL = sL - centerL;
            float spatialDistSq = float(x*x + y*y);

            float spatialW = exp(-spatialDistSq / sigmaSpatialSq);
            float rangeW = exp(-(dL * dL) / sigmaRangeSq);

            float w = spatialW * rangeW;
            sum += sRgb * w;
            wSum += w;
        }
    }

    vec3 blurColor = sum / wSum;

    // 皮肤检测：基于肤色范围判断当前像素是否属于皮肤
    // 肤色在YCbCr空间中 Cb∈[77,127], Cr∈[133,173]
    float cb = -0.169 * centerRgb.r - 0.331 * centerRgb.g + 0.500 * centerRgb.b + 0.5;
    float cr = 0.500 * centerRgb.r - 0.419 * centerRgb.g - 0.081 * centerRgb.b + 0.5;
    float skinMask = smoothstep(0.0, 1.0, 1.0 - abs(cb - 0.52) * 8.0) *
                     smoothstep(0.0, 1.0, 1.0 - abs(cr - 0.58) * 8.0);

    // 边缘检测：使用Sobel算子计算边缘强度
    vec2 texel = vec2(uWidthOffset, uHeightOffset) * 2.0;
    float tl = dot(texture2D(uInputTexture, uv + vec2(-texel.x, -texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float t  = dot(texture2D(uInputTexture, uv + vec2(0.0, -texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float tr = dot(texture2D(uInputTexture, uv + vec2(texel.x, -texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float l  = dot(texture2D(uInputTexture, uv + vec2(-texel.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
    float r  = dot(texture2D(uInputTexture, uv + vec2(texel.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
    float bl = dot(texture2D(uInputTexture, uv + vec2(-texel.x, texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float b  = dot(texture2D(uInputTexture, uv + vec2(0.0, texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float br = dot(texture2D(uInputTexture, uv + vec2(texel.x, texel.y)).rgb, vec3(0.299, 0.587, 0.114));

    float edgeX = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float edgeY = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float edgeStrength = length(vec2(edgeX, edgeY));

    // 边缘保留：边缘越强，磨皮效果越弱
    float edgeFactor = smoothstep(0.0, 0.3, 0.15 - edgeStrength);

    // 自适应混合：皮肤区域磨皮更强，边缘区域保留原图
    float blendAlpha = intensity * skinMask * edgeFactor;
    blendAlpha = clamp(blendAlpha, 0.0, intensity * 0.85);

    vec3 result = mix(centerRgb, blurColor, blendAlpha);

    // 细节增强：在磨皮后的图像上叠加轻微的高频细节
    float detailStrength = 0.08 * intensity * skinMask;
    vec3 highPass = centerRgb - blurColor;
    result = result + detailStrength * highPass;
    result = clamp(result, 0.0, 1.0);

    return result;
}

void main() {
    vec2 uv = vTextureCoord;
    vec4 iColor = texture2D(uInputTexture, uv);

    vec3 color = iColor.rgb;

    // ========== 磨皮部分 ==========
    if (uBlurAlpha > 0.0) {
        color = smoothSkin(uv, uBlurAlpha);
    }

    // ========== 美白部分（LUT） ==========
    if (uWhiten > 0.0) {
        vec3 colorEPM = color;
        // Level调整
        color = clamp((colorEPM - vec3(levelBlack)) * levelRangeInv, 0.0, 1.0);

        // lookUpGray LUT（16x1）
        vec3 texel = vec3(
            texture2D(uLookUpGray, vec2(color.r, 0.5)).r,
            texture2D(uLookUpGray, vec2(color.g, 0.5)).g,
            texture2D(uLookUpGray, vec2(color.b, 0.5)).b
        );
        texel = mix(color, texel, 0.5);
        texel = mix(colorEPM, texel, alpha);
        texel = clamp(texel, 0.0, 1.0);

        // lookUpOrigin LUT（64x64, 4x4 blocks）
        float blueColor = texel.b * 15.0;
        vec2 quad1;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        vec2 quad2;
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        vec2 texPos2 = texel.rg * 0.234375 + 0.0078125;
        vec2 texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        vec3 newColor1Origin = texture2D(uLookUpOrigin, texPos1).rgb;
        vec3 newColor2Origin = texture2D(uLookUpOrigin, texPos2).rgb;
        vec3 colorOrigin = mix(newColor1Origin, newColor2Origin, fract(blueColor));
        texel = mix(colorOrigin, color, alpha);

        // lookUpSkin LUT（64x64, 4x4 blocks）
        texel = clamp(texel, 0.0, 1.0);
        blueColor = texel.b * 15.0;
        quad1.y = floor(floor(blueColor) * 0.25);
        quad1.x = floor(blueColor) - (quad1.y * 4.0);
        quad2.y = floor(ceil(blueColor) * 0.25);
        quad2.x = ceil(blueColor) - (quad2.y * 4.0);
        texPos2 = texel.rg * 0.234375 + 0.0078125;
        texPos1 = quad1 * 0.25 + texPos2;
        texPos2 = quad2 * 0.25 + texPos2;
        vec3 newColor1 = texture2D(uLookUpSkin, texPos1).rgb;
        vec3 newColor2 = texture2D(uLookUpSkin, texPos2).rgb;
        color = mix(newColor1, newColor2, fract(blueColor));
        color = clamp(color, 0.0, 1.0);

        // lookUpLight LUT（512x512, 8x8 blocks）
        float blueColorCustom = color.b * 63.0;
        vec2 quad1Custom;
        quad1Custom.y = floor(floor(blueColorCustom) / 8.0);
        quad1Custom.x = floor(blueColorCustom) - (quad1Custom.y * 8.0);
        vec2 quad2Custom;
        quad2Custom.y = floor(ceil(blueColorCustom) / 8.0);
        quad2Custom.x = ceil(blueColorCustom) - (quad2Custom.y * 8.0);
        vec2 texPos1Custom;
        texPos1Custom.x = (quad1Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos1Custom.y = (quad1Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        vec2 texPos2Custom;
        texPos2Custom.x = (quad2Custom.x * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.r);
        texPos2Custom.y = (quad2Custom.y * 1.0 / 8.0) + 0.5 / 512.0 +
                          ((1.0 / 8.0 - 1.0 / 512.0) * color.g);
        vec3 newColor1Light = texture2D(uLookUpLight, texPos1Custom).rgb;
        vec3 newColor2Light = texture2D(uLookUpLight, texPos2Custom).rgb;
        vec3 colorCustom = mix(newColor1Light, newColor2Light, fract(blueColorCustom));
        color = mix(color, colorCustom, uWhiten);
    }

    gl_FragColor = vec4(color, iColor.a);
}

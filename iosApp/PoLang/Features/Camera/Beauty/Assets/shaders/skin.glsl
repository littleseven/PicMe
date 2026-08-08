float skinMask(vec2 uv) {
    if (uHasFace < 0.5) {
        return 0.0;
    }
    vec2 delta = uv - uFaceCenter;
    delta.y *= 1.22;
    float faceDist = length(delta) / max(uFaceRadius, 0.001);
    float faceArea = 1.0 - smoothstep(0.72, 1.05, faceDist);
    float eyeRadius = max(uFaceRadius * 0.18, 0.04);
    float leftEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uLeftEye));
    float rightEyeKeep = smoothstep(eyeRadius, eyeRadius * 0.55, length(uv - uRightEye));
    return clamp(faceArea * leftEyeKeep * rightEyeKeep, 0.0, 1.0);
}

// GPUPixel风格磨皮：增强版双边滤波 + 边缘保留 + 锐化
// 参考: gpupixel/src/main/cpp/src/filter/beauty_face_unit_filter.cc
vec4 smoothSkin(vec2 uv, float intensity) {
    vec4 centerColor = texture2D(uTexture, uv);
    float mask = skinMask(uv);
    if (mask < 0.01 || intensity < 0.001) {
        return centerColor;
    }

    vec3 centerRgb = centerColor.rgb;
    float centerL = dot(centerRgb, vec3(0.299, 0.587, 0.114));

    vec2 texelSize = uTexelSize;
    // GPUPixel风格：更大的空间sigma，更强的磨皮效果
    float sigmaSpatial = 2.5;
    float sigmaSpatialSq = sigmaSpatial * sigmaSpatial * 2.0;
    // 根据强度调整范围sigma
    float sigmaRange = 0.08 + intensity * 0.12;
    float sigmaRangeSq = sigmaRange * sigmaRange * 2.0;

    vec3 sum = centerRgb;
    float wSum = 1.0;

    // 5x5 采样（比原来的3x3更强效）
    for (int x = -2; x <= 2; x++) {
        for (int y = -2; y <= 2; y++) {
            if (x == 0 && y == 0) continue;
            vec2 offset = vec2(float(x), float(y)) * texelSize;
            vec3 sRgb = texture2D(uTexture, uv + offset).rgb;
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

    // GPUPixel风格边缘保留：根据亮度差异调整混合强度
    // 参考: float p = clamp((min(iColor.r, meanColor.r - 0.1) - 0.2) * 4.0, 0.0, 1.0)
    float meanL = dot(blurColor, vec3(0.299, 0.587, 0.114));
    float edgeFactor = clamp((min(centerL, meanL - 0.1) - 0.2) * 4.0, 0.0, 1.0);
    // 在边缘处减少磨皮强度
    float blendAlpha = intensity * mask * edgeFactor;

    vec3 result = mix(centerRgb, blurColor, blendAlpha);

    // GPUPixel风格锐化：增强边缘细节
    // 参考: vec3 hPass = iColor.rgb - sum; color = resultColor + sharpen * hPass * 2.0
    float sharpenStrength = 0.15 * intensity;  // 根据磨皮强度自动调整锐化
    vec3 highPass = centerRgb - blurColor;
    result = result + sharpenStrength * highPass * 2.0;
    result = clamp(result, 0.0, 1.0);

    return vec4(result, centerColor.a);
}

// GPUPixel风格美白：亮度提升 + 色调调整
// 参考: gpupixel beauty_face_unit_filter.cc 中的 whiten 逻辑
vec4 whitenSkin(vec4 color, float intensity, float mask) {
    if (intensity < 0.001 || mask < 0.01) {
        return color;
    }

    vec3 rgb = color.rgb;

    // 步骤1: 亮度提升（参考GPUPixel的levelBlack/levelRangeInv逻辑）
    // 提升暗部，压缩动态范围
    const float levelBlack = 0.0258820;
    const float levelRangeInv = 1.02657;
    vec3 leveled = clamp((rgb - vec3(levelBlack)) * levelRangeInv, 0.0, 1.0);

    // 步骤2: 混合原始颜色和提升后的颜色
    vec3 brightened = mix(rgb, leveled, 0.5);

    // 步骤3: 应用美白强度
    float whitenAlpha = intensity * mask;
    vec3 whitened = mix(rgb, brightened, whitenAlpha);

    // 步骤4: 轻微提升蓝色通道（冷白皮效果）
    // 参考GPUPixel lookUpSkin/LUT逻辑中的色调调整
    float blueBoost = 1.0 + whitenAlpha * 0.05;
    whitened.b *= blueBoost;

    // 步骤5: 轻微降低红色通道（减少黄气）
    float redReduce = 1.0 - whitenAlpha * 0.03;
    whitened.r *= redReduce;

    whitened = clamp(whitened, 0.0, 1.0);
    return vec4(whitened, color.a);
}

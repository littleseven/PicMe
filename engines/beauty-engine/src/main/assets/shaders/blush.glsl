// GPUPixel 风格腮红颜色定义
vec3 blushColorByFamily(int family) {
    if (family == 1) return vec3(1.00, 0.62, 0.45);  // 橙色系
    if (family == 2) return vec3(0.67, 0.31, 0.52);  // 梅色系
    return vec3(1.00, 0.56, 0.67);                     // 粉色系（默认）
}

// GPUPixel 风格混合函数：SoftLight
float blendSoftLight(float base, float blend) {
    return (blend < 0.5)
        ? (base + (2.0 * blend - 1.0) * (base - base * base))
        : (base + (2.0 * blend - 1.0) * (sqrt(base) - base));
}

vec3 blendSoftLight(vec3 base, vec3 blend) {
    return vec3(
        blendSoftLight(base.r, blend.r),
        blendSoftLight(base.g, blend.g),
        blendSoftLight(base.b, blend.b)
    );
}

// GPUPixel 风格混合函数：Multiply
vec3 blendMultiply(vec3 base, vec3 blend) {
    return base * blend;
}

// 计算点到线段的最短距离平方
float pointToSegmentDistSq(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    vec2 ap = p - a;
    float t = clamp(dot(ap, ab) / max(dot(ab, ab), 0.0001), 0.0, 1.0);
    vec2 closest = a + ab * t;
    return dot(p - closest, p - closest);
}

// 使用 133 点脸颊 contour 计算腮红蒙版
float blushMaskFromContour(vec2 uv, vec2 contourPoints[20], float contourCount) {
    if (contourCount < 3.0) {
        return 0.0;
    }
    int count = int(contourCount + 0.5);

    // 计算 contour 中心点
    vec2 center = vec2(0.0);
    for (int i = 0; i < 20; i++) {
        if (i >= count) break;
        center += contourPoints[i];
    }
    center /= max(contourCount, 1.0);

    // 计算 contour 的近似半径（最大距离）
    float maxRadius = 0.0;
    for (int i = 0; i < 20; i++) {
        if (i >= count) break;
        float dist = length(contourPoints[i] - center);
        maxRadius = max(maxRadius, dist);
    }

    // 计算 UV 到 contour 各线段的距离
    float minDistSq = 10000.0;
    for (int i = 0; i < 20; i++) {
        if (i + 1 >= count) break;
        float distSq = pointToSegmentDistSq(uv, contourPoints[i], contourPoints[i + 1]);
        minDistSq = min(minDistSq, distSq);
    }

    // 判断点是否在 contour 内部（使用绕数法简化版：判断点到中心的方向一致性）
    float distToCenter = length(uv - center);
    float normalizedDist = distToCenter / max(maxRadius, 0.001);

    // 内部点：normalizedDist < 1，外部点：normalizedDist > 1
    // 使用 smoothstep 创建柔和边缘
    float mask = 1.0 - smoothstep(0.6, 1.15, normalizedDist);

    // 距离 contour 边缘越近，mask 越弱（边缘羽化）
    float edgeFade = 1.0 - smoothstep(0.0, maxRadius * 0.25, sqrt(minDistSq));
    mask *= mix(0.65, 1.0, edgeFade);

    return clamp(mask, 0.0, 1.0);
}

// 主腮红蒙版函数：融合 133 点 contour 和几何估算
float blushMaskFromCheeks(vec2 uv) {
    if (uHasFace < 0.5 || uBlush < 0.001) {
        return 0.0;
    }

    // 优先使用 133 点脸颊 contour（如果可用）
    bool hasLeftContour = uLeftCheekContourCount >= 3.0;
    bool hasRightContour = uRightCheekContourCount >= 3.0;

    float leftMask = 0.0;
    float rightMask = 0.0;

    if (hasLeftContour) {
        leftMask = blushMaskFromContour(uv, uLeftCheekContourPoints, uLeftCheekContourCount);
    }
    if (hasRightContour) {
        rightMask = blushMaskFromContour(uv, uRightCheekContourPoints, uRightCheekContourCount);
    }

    // 如果 contour 数据不可用，回退到几何估算
    if (!hasLeftContour || !hasRightContour) {
        vec2 faceRight = normalize(uRightEye - uLeftEye);
        if (length(faceRight) < 0.0001) {
            faceRight = vec2(1.0, 0.0);
        }
        vec2 eyeCenter = (uLeftEye + uRightEye) * 0.5;
        vec2 mouthCenter = (uMouthLeft + uMouthRight) * 0.5;
        vec2 faceUp = normalize(eyeCenter - mouthCenter);
        if (length(faceUp) < 0.0001) {
            faceUp = vec2(-faceRight.y, faceRight.x);
        }
        float eyeWidth = max(length(uRightEye - uLeftEye), 0.12);
        float eyeMouthDist = max(length(eyeCenter - mouthCenter), uFaceRadius * 0.55);
        float faceAspect = clamp(eyeMouthDist / eyeWidth, 0.9, 1.8);
        float roundFace = clamp((1.28 - faceAspect) / 0.28, 0.0, 1.0);
        float longFace = clamp((faceAspect - 1.40) / 0.30, 0.0, 1.0);
        float appleBaseFactor = 0.34 + longFace * 0.05 - roundFace * 0.03;
        vec2 appleBase = eyeCenter - faceUp * max(eyeMouthDist * appleBaseFactor, uFaceRadius * 0.17);
        float cheekOffsetX = max(eyeWidth * (0.34 + roundFace * 0.05 - longFace * 0.02), uFaceRadius * (0.31 + roundFace * 0.03));
        float cheekOffsetY = max(eyeMouthDist * (0.06 + roundFace * 0.05 - longFace * 0.03), uFaceRadius * (0.03 + roundFace * 0.04 - longFace * 0.01));
        vec2 leftCheekCenter = appleBase - faceRight * cheekOffsetX + faceUp * cheekOffsetY;
        vec2 rightCheekCenter = appleBase + faceRight * cheekOffsetX + faceUp * cheekOffsetY;
        float radiusX = max(uFaceRadius * (0.128 + longFace * 0.018 + roundFace * 0.005), 0.05);
        float radiusY = max(uFaceRadius * (0.102 + roundFace * 0.010 - longFace * 0.008), 0.04);

        if (!hasLeftContour) {
            vec2 leftDelta = uv - leftCheekCenter;
            float leftX = dot(leftDelta, faceRight) / radiusX;
            float leftY = dot(leftDelta, faceUp) / radiusY;
            float leftEllipse = leftX * leftX + leftY * leftY;
            leftMask = 1.0 - smoothstep(0.48, 1.0, leftEllipse);
        }
        if (!hasRightContour) {
            vec2 rightDelta = uv - rightCheekCenter;
            float rightX = dot(rightDelta, faceRight) / radiusX;
            float rightY = dot(rightDelta, faceUp) / radiusY;
            float rightEllipse = rightX * rightX + rightY * rightY;
            rightMask = 1.0 - smoothstep(0.48, 1.0, rightEllipse);
        }

        // 几何估算的安全区域裁剪
        float centerGap = smoothstep(uFaceRadius * 0.18, uFaceRadius * 0.30, abs(dot(uv - eyeCenter, faceRight)));
        float mouthGap = smoothstep(uFaceRadius * 0.10, uFaceRadius * 0.23, dot(uv - mouthCenter, faceUp));
        float mouthCornerGapLeft = smoothstep(uFaceRadius * 0.08, uFaceRadius * 0.18, length(uv - uMouthLeft));
        float mouthCornerGapRight = smoothstep(uFaceRadius * 0.08, uFaceRadius * 0.18, length(uv - uMouthRight));
        float eyeSafeRadius = max(uFaceRadius * 0.17, eyeWidth * 0.28);
        float leftEyeGap = smoothstep(eyeSafeRadius * 0.58, eyeSafeRadius, length(uv - uLeftEye));
        float rightEyeGap = smoothstep(eyeSafeRadius * 0.58, eyeSafeRadius, length(uv - uRightEye));
        float underEyeGap = smoothstep(uFaceRadius * 0.04, uFaceRadius * 0.12, -dot(uv - eyeCenter, faceUp));
        float eyeGap = leftEyeGap * rightEyeGap * underEyeGap;
        float sideSplitLeft = smoothstep(-uFaceRadius * 0.02, uFaceRadius * 0.09, dot(leftCheekCenter - uv, faceRight));
        float sideSplitRight = smoothstep(-uFaceRadius * 0.02, uFaceRadius * 0.09, dot(uv - rightCheekCenter, faceRight));
        leftMask *= sideSplitLeft;
        rightMask *= sideSplitRight;
        float safetyMask = centerGap * mouthGap * mouthCornerGapLeft * mouthCornerGapRight * eyeGap;
        leftMask *= safetyMask;
        rightMask *= safetyMask;
    }

    float cheekMask = max(leftMask, rightMask);
    return clamp(cheekMask, 0.0, 1.0);
}

// GPUPixel 风格腮红应用：使用 SoftLight 混合
vec3 applyBlush(vec3 baseColor, vec2 uv) {
    float mask = blushMaskFromCheeks(uv);
    if (mask < 0.001 || uBlush < 0.001) {
        return baseColor;
    }

    vec3 blushTarget = blushColorByFamily(uBlushColorFamily);
    float blendStrength = clamp(uBlush, 0.0, 1.0) * 0.45 * mask;

    // GPUPixel 风格 SoftLight 混合
    vec3 blended = blendSoftLight(baseColor, blushTarget);

    // 最终混合
    return mix(baseColor, blended, blendStrength);
}

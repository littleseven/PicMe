// GPUPixel FaceReshapeFilter - 大眼算法（基于106点关键点）
// 参考: gpupixel/src/main/cpp/src/filter/face_reshape_filter.cc
// 注意: uFacePoints, uAspectRatio, uUseGpupixelWarp 在 uniforms.glsl 中已声明

// 大眼效果：径向放大（GPUPixel enlargeEye）
// 注意：GPUPixel原始实现是正向映射（变形坐标后采样），
// 大美丽使用反向映射（从输出位置反推输入位置）
//
// 正向映射公式：output = origin + (input - origin) * w
//   其中 w = 1 - (1 - t^2) * delta, t = dist/radius
//   当 delta>0 时，w<1，坐标向origin收缩 = 放大效果
//
// 反向映射：使用近似公式
//   正向：y = origin + (x - origin) * w(x)
//   反向近似：x ≈ origin + (y - origin) / w(y)
//   其中 w(y) 是用输出距离计算的weight（近似）
vec2 gpupixelEnlargeEye(vec2 textureCoord, vec2 originPosition, float radius, float delta) {
    // 在aspectRatio校正后的坐标系中计算距离
    vec2 correctedCoord = vec2(textureCoord.x, textureCoord.y / uAspectRatio);
    vec2 correctedOrigin = vec2(originPosition.x, originPosition.y / uAspectRatio);
    vec2 correctedOffset = correctedCoord - correctedOrigin;
    float r = length(correctedOffset);

    // 超出radius范围，不做形变（与GPUPixel原始行为一致）
    if (r > radius) {
        return textureCoord;
    }

    float t = r / radius;
    // delta 已经在入口处映射到 [0, 0.3] 范围
    // 注意：GPUPixel原始实现是正向映射，大美丽使用反向映射
    // 正向映射：output = origin + (input - origin) * w
    // 反向映射：input = origin + (output - origin) / w
    // 其中 w = 1 - (1-t^2) * delta，当 delta>0 时 w<1，产生放大效果
    float w = 1.0 - (1.0 - t * t) * delta;
    w = clamp(w, 0.001, 1.0);

    // 反向映射：使用 w 直接收缩坐标（而不是 1/w 扩展）
    // 因为我们是反向映射，需要从输出位置反推输入位置
    // 如果正向是 *w（收缩），反向就是 *w（也是收缩）
    // 这样采样位置更靠近origin，相当于把周围的像素拉向origin = 放大效果
    float scale = w;

    // 在校正后的坐标系中计算新位置，然后转换回原始UV坐标系
    vec2 correctedResult = correctedOrigin + correctedOffset * scale;
    return vec2(correctedResult.x, correctedResult.y * uAspectRatio);
}

// 大眼：应用2对控制点变形（GPUPixel bigEye）
vec2 gpupixelBigEye(vec2 currentCoordinate, float bigEyeDelta) {
    // 2对控制点映射（originIndex=瞳孔 -> targetIndex=眼角）
    // 74->72: 右瞳孔 -> 右眼内角
    // 77->75: 左瞳孔 -> 左眼内角
    vec2 faceIndexs[2];
    faceIndexs[0] = vec2(74.0, 72.0);
    faceIndexs[1] = vec2(77.0, 75.0);

    for (int i = 0; i < 2; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);

        vec2 originPoint = getFacePoint(originIndex);
        vec2 targetPoint = getFacePoint(targetIndex);

        float radius = distance(
            vec2(targetPoint.x, targetPoint.y / uAspectRatio),
            vec2(originPoint.x, originPoint.y / uAspectRatio)
        );
        radius = radius * 5.0;
        
        // 限制radius最大值为0.08（约画面8%），确保只影响眼睛周围
        radius = min(radius, 0.08);
        currentCoordinate = gpupixelEnlargeEye(currentCoordinate, originPoint, radius, bigEyeDelta);
    }
    return currentCoordinate;
}

// 大眼 warp 入口
vec2 warpCoordGpupixelBigEye(vec2 uv) {
    if (uHasFace < 0.5) {
        return uv;
    }

    // 将 uBigEyes [0,1] 映射到 [0, 0.3] 范围，增强效果
    float bigEyeDelta = uBigEyes * 0.3;
    
    if (bigEyeDelta > 0.001) {
        return gpupixelBigEye(uv, bigEyeDelta);
    }

    return uv;
}

// 调试模式：返回红色表示在radius内，绿色表示在radius外
// 用白色十字标记瞳孔位置，用青色圆圈标记radius边界
vec3 warpCoordGpupixelBigEyeDebug(vec2 uv) {
    if (uHasFace < 0.5) {
        return vec3(0.0, 0.0, 1.0);  // 蓝色：无脸
    }

    vec2 faceIndexs[2];
    faceIndexs[0] = vec2(74.0, 72.0);
    faceIndexs[1] = vec2(77.0, 75.0);

    vec3 resultColor = vec3(0.0, 1.0, 0.0);  // 默认绿色

    for (int i = 0; i < 2; i++) {
        int originIndex = int(faceIndexs[i].x);
        vec2 originPoint = getFacePoint(originIndex);

        vec2 correctedCoord = vec2(uv.x, uv.y / uAspectRatio);
        vec2 correctedOrigin = vec2(originPoint.x, originPoint.y / uAspectRatio);
        float r = distance(correctedCoord, correctedOrigin);

        // 使用与bigEye相同的radius计算
        int targetIndex = int(faceIndexs[i].y);
        vec2 targetPoint = getFacePoint(targetIndex);
        float radius = distance(
            vec2(targetPoint.x, targetPoint.y / uAspectRatio),
            vec2(originPoint.x, originPoint.y / uAspectRatio)
        );
        radius = radius * 5.0;
        radius = min(radius, 0.08);

        // 绘制瞳孔位置（白色十字，2x2像素）
        float dx = abs(uv.x - originPoint.x);
        float dy = abs(uv.y - originPoint.y);
        if (dx < 0.003 || dy < 0.003) {
            return vec3(1.0, 1.0, 1.0);  // 白色十字
        }

        // 绘制radius边界（青色圆圈，线宽2像素）
        float radiusDist = abs(r - radius);
        if (radiusDist < 0.003) {
            return vec3(0.0, 1.0, 1.0);  // 青色边界
        }

        if (r < radius) {
            // 在radius内：根据距离返回红色到黄色的渐变
            float t = r / radius;
            resultColor = vec3(1.0, 1.0 - t, 0.0);  // 红->黄
        }
    }

    return resultColor;
}

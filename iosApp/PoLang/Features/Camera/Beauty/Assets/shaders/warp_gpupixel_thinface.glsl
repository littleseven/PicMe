// GPUPixel FaceReshapeFilter - 瘦脸算法（基于106点关键点）
// 参考: gpupixel/src/main/cpp/src/filter/face_reshape_filter.cc
// 注意: uFacePoints, uAspectRatio, uUseGpupixelWarp 在 uniforms.glsl 中已声明

// 辅助函数：从关键点数组获取坐标
vec2 getFacePoint(int index) {
    return vec2(uFacePoints[index * 2], uFacePoints[index * 2 + 1]);
}

// 曲线变形：局部区域位移（GPUPixel curveWarp）
// 注意：GPUPixel原始实现是正向映射（变形坐标后采样），
// 大美丽使用反向映射（从输出位置反推输入位置），所以偏移方向需要取反
vec2 gpupixelCurveWarp(vec2 textureCoord, vec2 originPosition, vec2 targetPosition, float delta) {
    vec2 offset = vec2(0.0);
    vec2 result = vec2(0.0);
    vec2 direction = (targetPosition - originPosition) * delta;

    float radius = distance(
        vec2(targetPosition.x, targetPosition.y / uAspectRatio),
        vec2(originPosition.x, originPosition.y / uAspectRatio)
    );
    float ratio = distance(
        vec2(textureCoord.x, textureCoord.y / uAspectRatio),
        vec2(originPosition.x, originPosition.y / uAspectRatio)
    ) / radius;

    ratio = 1.0 - ratio;
    ratio = clamp(ratio, 0.0, 1.0);
    offset = direction * ratio;

    // 反向映射：从输出位置反推输入采样位置
    result = textureCoord + offset;
    return result;
}

// 瘦脸：应用9对控制点变形（GPUPixel thinFace）
vec2 gpupixelThinFace(vec2 currentCoordinate, float thinFaceDelta) {
    // 9对控制点映射（originIndex -> targetIndex）
    // 3->44: 右脸轮廓点 -> 鼻梁右侧
    // 29->44: 左脸轮廓点 -> 鼻梁右侧
    // 7->45: 右脸颊 -> 鼻梁中
    // 25->45: 左脸颊 -> 鼻梁中
    // 10->46: 右下颌 -> 鼻梁下
    // 22->46: 左下颌 -> 鼻梁下
    // 14->49: 右下巴 -> 下巴中心
    // 18->49: 左下巴 -> 下巴中心
    // 16->49: 下巴尖 -> 下巴中心
    vec2 faceIndexs[9];
    faceIndexs[0] = vec2(3.0, 44.0);
    faceIndexs[1] = vec2(29.0, 44.0);
    faceIndexs[2] = vec2(7.0, 45.0);
    faceIndexs[3] = vec2(25.0, 45.0);
    faceIndexs[4] = vec2(10.0, 46.0);
    faceIndexs[5] = vec2(22.0, 46.0);
    faceIndexs[6] = vec2(14.0, 49.0);
    faceIndexs[7] = vec2(18.0, 49.0);
    faceIndexs[8] = vec2(16.0, 49.0);

    for (int i = 0; i < 9; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);
        vec2 originPoint = getFacePoint(originIndex);
        vec2 targetPoint = getFacePoint(targetIndex);
        currentCoordinate = gpupixelCurveWarp(currentCoordinate, originPoint, targetPoint, thinFaceDelta);
    }
    return currentCoordinate;
}

// 瘦脸 warp 入口
vec2 warpCoordGpupixelThinFace(vec2 uv) {
    if (uHasFace < 0.5) {
        return uv;
    }

    if (abs(uSlimFace) > 0.001) {
        return gpupixelThinFace(uv, uSlimFace);
    }

    return uv;
}

// 调试模式：可视化瘦脸控制点的影响范围
// 返回颜色：红色=在radius内（靠近origin），绿色=在radius外
vec3 warpCoordGpupixelThinFaceDebug(vec2 uv) {
    if (uHasFace < 0.5) {
        return vec3(0.0, 0.0, 1.0);  // 蓝色：无脸
    }

    vec2 faceIndexs[9];
    faceIndexs[0] = vec2(3.0, 44.0);
    faceIndexs[1] = vec2(29.0, 44.0);
    faceIndexs[2] = vec2(7.0, 45.0);
    faceIndexs[3] = vec2(25.0, 45.0);
    faceIndexs[4] = vec2(10.0, 46.0);
    faceIndexs[5] = vec2(22.0, 46.0);
    faceIndexs[6] = vec2(14.0, 49.0);
    faceIndexs[7] = vec2(18.0, 49.0);
    faceIndexs[8] = vec2(16.0, 49.0);

    float maxInfluence = 0.0;

    for (int i = 0; i < 9; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);
        vec2 originPoint = getFacePoint(originIndex);
        vec2 targetPoint = getFacePoint(targetIndex);

        float radius = distance(
            vec2(targetPoint.x, targetPoint.y / uAspectRatio),
            vec2(originPoint.x, originPoint.y / uAspectRatio)
        );

        float r = distance(
            vec2(uv.x, uv.y / uAspectRatio),
            vec2(originPoint.x, originPoint.y / uAspectRatio)
        );

        float ratio = 1.0 - (r / radius);
        ratio = clamp(ratio, 0.0, 1.0);
        maxInfluence = max(maxInfluence, ratio);
    }

    if (maxInfluence > 0.0) {
        // 红色=强影响，黄色=弱影响
        return vec3(1.0, 1.0 - maxInfluence, 0.0);
    }

    return vec3(0.0, 1.0, 0.0);  // 绿色：无影响
}

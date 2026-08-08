// warp.metal — GPUPixel 瘦脸/大眼 shader（GLSL→MSL 翻译）
//
// 翻译源:
//   engines/beauty-engine/src/main/assets/shaders/warp_gpupixel_thinface.glsl (129 行)
//   engines/beauty-engine/src/main/assets/shaders/warp_gpupixel_bigeye.glsl  (146 行)
//
// 翻译纪律 (skill metal-render-expert + spike 踩坑):
//   - GLSL `const float` 局部标量 → `constexpr`（`constant` 是地址空间，非 const）
//   - `vec2/vec3/vec4` → `float2/float3/float4`
//   - `clamp(x,0.,1.)` → `saturate(x)`
//   - `distance/length/mix/abs/exp` 同名保留
//   - `uFacePoints[212]` (flat float[106*2]) → `constant float* facePoints [[buffer(1)]]`
//   - `uAspectRatio/uHasFace/uSlimFace/uBigEyes` → `WarpUniforms` struct `[[buffer(0)]]`
//   - `uTextureTransform` (SurfaceTexture 矩阵) → 删除（iOS 无 SurfaceTexture）
//   - Metal UV 原点左上 vs GL 左下 → 顶点 UV 翻转补偿（见 quad_vertex）

#include <metal_stdlib>
using namespace metal;

// ===========================================================================
// Uniforms
// ===========================================================================
struct BeautyUniforms {
    float smoothing;
    float whitening;
    float sharpen;
    float bigEyes;       // 对应 GLSL uBigEyes [0,1]
    float slimFace;      // 对应 GLSL uSlimFace [0,1]
    float hasFace;       // 对应 GLSL uHasFace (0 or 1)
    float aspectRatio;   // 对应 GLSL uAspectRatio
    int   useGpupixelWarp; // 对应 GLSL uUseGpupixelWarp (0 or 1)
};

// ===========================================================================
// Vout（Metal 每文件独立编译；quad_vertex 定义在 yuv.metal，linker 解析）
// ===========================================================================
struct Vout {
    float4 position [[position]];
    float2 uv;
};

// ===========================================================================
// 辅助：从扁平关键点数组取坐标
// [图像坐标系] facePoints 是 106 点 × 2 = 212 floats 扁平数组
// ===========================================================================
static inline float2 getFacePoint(int index, constant float* facePoints) {
    return float2(facePoints[index * 2], facePoints[index * 2 + 1]);
}

// ===========================================================================
// 瘦脸：曲线变形（GPUPixel curveWarp）
// 注意：GPUPixel 原始实现是正向映射；大美丽使用反向映射，
//       从输出位置反推输入采样位置（偏移方向已处理）。
// ===========================================================================
static float2 gpupixelCurveWarp(
    float2 textureCoord,
    float2 originPosition,
    float2 targetPosition,
    float delta,
    float aspectRatio)
{
    float2 direction = (targetPosition - originPosition) * delta;

    // 在 aspectRatio 校正后的坐标系中计算距离（与 GLSL 一致）
    float radius = distance(
        float2(targetPosition.x, targetPosition.y / aspectRatio),
        float2(originPosition.x, originPosition.y / aspectRatio)
    );
    float ratio = distance(
        float2(textureCoord.x, textureCoord.y / aspectRatio),
        float2(originPosition.x, originPosition.y / aspectRatio)
    ) / radius;

    ratio = 1.0 - ratio;
    ratio = saturate(ratio);

    float2 offset = direction * ratio;

    // 反向映射：从输出位置反推输入采样位置
    return textureCoord + offset;
}

// ===========================================================================
// 瘦脸：应用 9 对控制点变形（GPUPixel thinFace）
// 3->44: 右脸轮廓点 -> 鼻梁右侧
// 29->44: 左脸轮廓点 -> 鼻梁右侧
// 7->45: 右脸颊 -> 鼻梁中
// 25->45: 左脸颊 -> 鼻梁中
// 10->46: 右下颌 -> 鼻梁下
// 22->46: 左下颌 -> 鼻梁下
// 14->49: 右下巴 -> 下巴中心
// 18->49: 左下巴 -> 下巴中心
// 16->49: 下巴尖 -> 下巴中心
// ===========================================================================
static float2 gpupixelThinFace(
    float2 currentCoordinate,
    float thinFaceDelta,
    constant float* facePoints,
    float aspectRatio)
{
    // 9 对控制点映射（originIndex -> targetIndex）
    const float2 faceIndexs[9] = {
        float2(3.0, 44.0),  float2(29.0, 44.0),
        float2(7.0, 45.0),  float2(25.0, 45.0),
        float2(10.0, 46.0), float2(22.0, 46.0),
        float2(14.0, 49.0), float2(18.0, 49.0),
        float2(16.0, 49.0)
    };

    for (int i = 0; i < 9; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);
        float2 originPoint = getFacePoint(originIndex, facePoints);
        float2 targetPoint = getFacePoint(targetIndex, facePoints);
        currentCoordinate = gpupixelCurveWarp(
            currentCoordinate, originPoint, targetPoint, thinFaceDelta, aspectRatio);
    }
    return currentCoordinate;
}

// 瘦脸 warp 入口
static float2 warpCoordGpupixelThinFace(
    float2 uv, constant BeautyUniforms& uni, constant float* facePoints)
{
    if (uni.hasFace < 0.5) {
        return uv;
    }
    if (abs(uni.slimFace) > 0.001) {
        return gpupixelThinFace(uv, uni.slimFace, facePoints, uni.aspectRatio);
    }
    return uv;
}

// ===========================================================================
// 大眼：径向放大（GPUPixel enlargeEye）
// 正向映射：output = origin + (input - origin) * w, w = 1-(1-t^2)*delta
// 反向映射近似：使用 w 直接收缩坐标（*w），采样位置更靠近 origin = 放大效果
// ===========================================================================
static float2 gpupixelEnlargeEye(
    float2 textureCoord,
    float2 originPosition,
    float radius,
    float delta,
    float aspectRatio)
{
    float2 correctedCoord  = float2(textureCoord.x, textureCoord.y / aspectRatio);
    float2 correctedOrigin = float2(originPosition.x, originPosition.y / aspectRatio);
    float2 correctedOffset = correctedCoord - correctedOrigin;
    float r = length(correctedOffset);

    // 超出 radius 范围，不做形变
    if (r > radius) {
        return textureCoord;
    }

    float t = r / radius;
    float w = 1.0 - (1.0 - t * t) * delta;
    w = clamp(w, 0.001f, 1.0f);

    // 反向映射：*w 收缩坐标（采样更靠近 origin）
    float scale = w;
    float2 correctedResult = correctedOrigin + correctedOffset * scale;
    return float2(correctedResult.x, correctedResult.y * aspectRatio);
}

// ===========================================================================
// 大眼：应用 2 对控制点变形（GPUPixel bigEye）
// 74->72: 右瞳孔 -> 右眼内角
// 77->75: 左瞳孔 -> 左眼内角
// ===========================================================================
static float2 gpupixelBigEye(
    float2 currentCoordinate,
    float bigEyeDelta,
    constant float* facePoints,
    float aspectRatio)
{
    const float2 faceIndexs[2] = {
        float2(74.0, 72.0),
        float2(77.0, 75.0)
    };

    for (int i = 0; i < 2; i++) {
        int originIndex = int(faceIndexs[i].x);
        int targetIndex = int(faceIndexs[i].y);

        float2 originPoint = getFacePoint(originIndex, facePoints);
        float2 targetPoint = getFacePoint(targetIndex, facePoints);

        float radius = distance(
            float2(targetPoint.x, targetPoint.y / aspectRatio),
            float2(originPoint.x, originPoint.y / aspectRatio)
        );
        radius = radius * 5.0;
        radius = min(radius, 0.08f); // 约 8% 画面，仅影响眼睛周围

        currentCoordinate = gpupixelEnlargeEye(
            currentCoordinate, originPoint, radius, bigEyeDelta, aspectRatio);
    }
    return currentCoordinate;
}

// 大眼 warp 入口
static float2 warpCoordGpupixelBigEye(
    float2 uv, constant BeautyUniforms& uni, constant float* facePoints)
{
    if (uni.hasFace < 0.5) {
        return uv;
    }
    // uBigEyes [0,1] 映射到 [0, 0.3]，增强效果
    float bigEyeDelta = uni.bigEyes * 0.3;
    if (bigEyeDelta > 0.001) {
        return gpupixelBigEye(uv, bigEyeDelta, facePoints, uni.aspectRatio);
    }
    return uv;
}

// ===========================================================================
// 主 warp 入口：先瘦脸再大眼（对应 main.glsl 调用序）
// 返回形变后的采样 UV
// ===========================================================================
// 非 static：允许 beauty.metal 跨文件调用（Metal metallib 链接时解析）
float2 computeWarpedUv(
    float2 uv, constant BeautyUniforms& uni, constant float* facePoints)
{
    if (uni.useGpupixelWarp > 0) {
        float2 warpedUv = warpCoordGpupixelThinFace(uv, uni, facePoints);
        warpedUv = warpCoordGpupixelBigEye(warpedUv, uni, facePoints);
        return warpedUv;
    }
    return uv;
}

// ===========================================================================
// 独立 warp fragment（可单独编译验证；Task 16 合入 beauty_fragment 时由后者调用 computeWarpedUv）
// ===========================================================================
fragment float4 warp_fragment(
    Vout in [[stage_in]],
    texture2d<float, access::sample> inputTexture [[texture(0)]],
    sampler bilinear [[sampler(0)]],
    constant BeautyUniforms& uni [[buffer(0)]],
    constant float* facePoints [[buffer(1)]])
{
    float2 warpedUv = computeWarpedUv(in.uv, uni, facePoints);
    float4 color = inputTexture.sample(bilinear, warpedUv);
    return color;
}

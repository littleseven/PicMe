// yuv.metal — YUV bi-planar → RGB 直渲（spike 已验证基线）
//
// 移植源: tmp/beauty-metal-spike/BeautyMetalSpike/Shaders.metal (BT.601)
// 用途: 相机 CVPixelBuffer (Y=R8Unorm + UV=RG8Unorm) → RGB 中间纹理 / drawable
//
// 🔴10 UV 翻转约定（Metal vs GL）:
//   Metal 纹理 UV 原点 = 左上角（GL = 左下角）。
//   quad_vertex 的 UV 映射 {0,0}→左下NDC, {1,1}→右上NDC，
//   对应 Metal 纹理左上→右下（与 GL 相反，但 Metal 渲染目标和采样纹理都是左上原点，
//   所以全管线 pass-to-pass 传递不翻转）。
//   预览 drawable 也是左上原点，因此最终画面方向正确。
//   真机验证清单：预览不上下颠倒 + 拍照保存方向与预览一致。

#include <metal_stdlib>
using namespace metal;

// Vout + quad_vertex 的唯一定义（其他 .metal 文件通过外部链接引用）
struct Vout {
    float4 position [[position]];
    float2 uv;
};

vertex Vout quad_vertex(uint vid [[vertex_id]]) {
    float2 pos[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    float2 uv[4]  = { {0,0}, {1,0}, {0,1}, {1,1} };
    Vout o;
    o.position = float4(pos[vid], 0, 1);
    o.uv = uv[vid];
    return o;
}

// BT.601 limited range YUV → RGB
fragment float4 yuv_fragment(
    Vout in [[stage_in]],
    texture2d<float, access::sample> yTexture  [[texture(0)]],
    texture2d<float, access::sample> uvTexture [[texture(1)]],
    sampler bilinear [[sampler(0)]])
{
    float y  = yTexture.sample(bilinear, in.uv).r;
    float2 cbcr = uvTexture.sample(bilinear, in.uv).rg;
    // BT.601 limited range (Y∈[16,235])
    float y1 = 1.164 * (y - 16.0 / 255.0);
    float cb = cbcr.x - 128.0 / 255.0;
    float cr = cbcr.y - 128.0 / 255.0;
    float r = y1 + 1.596 * cr;
    float g = y1 - 0.392 * cb - 0.813 * cr;
    float b = y1 + 2.017 * cb;
    return float4(saturate(float3(r, g, b)), 1.0);
}

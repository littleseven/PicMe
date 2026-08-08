// yuv.metal — YUV bi-planar → RGB 直渲（spike 已验证基线）
//
// 移植源: tmp/beauty-metal-spike/BeautyMetalSpike/Shaders.metal (BT.601)
// 用途: 相机 CVPixelBuffer (Y=R8Unorm + UV=RG8Unorm) → RGB 中间纹理 / drawable

#include <metal_stdlib>
using namespace metal;

#ifndef POLANG_VOUT_DEFINED
#define POLANG_VOUT_DEFINED
struct Vout {
    float4 position [[position]];
    float2 uv;
};
#endif

#ifndef POLANG_QUAD_VERTEX_DEFINED
#define POLANG_QUAD_VERTEX_DEFINED
vertex Vout quad_vertex(uint vid [[vertex_id]]) {
    float2 pos[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    float2 uv[4]  = { {0,0}, {1,0}, {0,1}, {1,1} };
    Vout o;
    o.position = float4(pos[vid], 0, 1);
    o.uv = uv[vid];
    return o;
}
#endif

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

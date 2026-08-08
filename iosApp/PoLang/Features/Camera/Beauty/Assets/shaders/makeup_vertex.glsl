// FaceMakeupPass 顶点着色器
// 基于 GPUPixel FaceMakeupFilter 顶点 Shader

attribute vec2 aPosition;           // 人脸关键点位置（NDC [-1,1]）
attribute vec2 aTextureCoord;       // 妆容纹理坐标（对应 mouth.png / blusher.png）

varying vec2 vTextureCoord;         // 妆容纹理采样坐标
varying vec2 vScreenCoord;          // 屏幕/原始帧采样坐标

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    
    // 妆容纹理坐标直接使用（由预定义的标准脸 UV 提供）
    vTextureCoord = aTextureCoord;
    
    // 与 GPUPixel 保持一致：直接用 landmark 顶点推导背景采样坐标，
    // 避免额外 Y 翻转导致采样到错误区域，出现亮斑或妆容漂移。
    vScreenCoord = vec2(aPosition.x * 0.5 + 0.5, aPosition.y * 0.5 + 0.5);
}

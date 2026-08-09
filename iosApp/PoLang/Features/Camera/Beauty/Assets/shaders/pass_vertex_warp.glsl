// 主Shader2D专用顶点 Shader - 多Pass后从FBO采样
// 多Pass模式下：FBO纹理已包含正确方向的图像，使用标准UV即可
// 人脸关键点在CPU侧通过逆矩阵还原到标准UV，与vWarpCoord匹配
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
varying vec2 vWarpCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = aTextureCoord.xy;
    vWarpCoord = aTextureCoord.xy;  // 标准UV，与CPU侧逆变换后的人脸关键点匹配
}

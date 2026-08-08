// Copy Pass - 将外部纹理(OES)复制到内部纹理(2D)
// 用于多Pass管线的第一步：将相机输入转换为可链式处理的2D纹理
//
// [坐标系对齐说明]
// SurfaceTexture.getTransformMatrix() 已经包含前置/后置的方向差异
//（前置 m[4]=1，后置 m[4]=-1），因此不需要在 Shader 中额外做前置镜像。
// 人脸关键点的镜像由 MediaPipe468Adapter 在 CPU 端统一处理。

#extension GL_OES_EGL_image_external : require

precision highp float;

uniform samplerExternalOES uCameraTexture;
uniform mat4 uTextureTransform;

varying vec2 vTextureCoord;

void main() {
    vec2 uv = (uTextureTransform * vec4(vTextureCoord, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uCameraTexture, uv);
}

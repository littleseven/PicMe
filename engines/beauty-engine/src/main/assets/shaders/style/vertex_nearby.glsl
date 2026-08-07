attribute vec4 aPosition;
attribute vec4 aTextureCoord;
uniform float uTexelWidth;
uniform float uTexelHeight;
varying vec2 vTextureCoord;
varying vec2 vLeftTexCoord;
varying vec2 vRightTexCoord;
varying vec2 vTopTexCoord;
varying vec2 vTopLeftTexCoord;
varying vec2 vTopRightTexCoord;
varying vec2 vBottomTexCoord;
varying vec2 vBottomLeftTexCoord;
varying vec2 vBottomRightTexCoord;
void main() {
    gl_Position = aPosition;
    vec2 widthStep = vec2(uTexelWidth, 0.0);
    vec2 heightStep = vec2(0.0, uTexelHeight);
    vec2 widthHeightStep = vec2(uTexelWidth, uTexelHeight);
    vec2 widthNegativeHeightStep = vec2(uTexelWidth, -uTexelHeight);
    vTextureCoord = aTextureCoord.xy;
    vLeftTexCoord = aTextureCoord.xy - widthStep;
    vRightTexCoord = aTextureCoord.xy + widthStep;
    vTopTexCoord = aTextureCoord.xy - heightStep;
    vTopLeftTexCoord = aTextureCoord.xy - widthHeightStep;
    vTopRightTexCoord = aTextureCoord.xy + widthNegativeHeightStep;
    vBottomTexCoord = aTextureCoord.xy + heightStep;
    vBottomLeftTexCoord = aTextureCoord.xy - widthNegativeHeightStep;
    vBottomRightTexCoord = aTextureCoord.xy + widthHeightStep;
}

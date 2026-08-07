precision mediump float;
uniform sampler2D uInputTexture;
uniform mat3 uConvolutionMatrix;
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
    vec3 bottomColor = texture2D(uInputTexture, vBottomTexCoord).rgb;
    vec3 bottomLeftColor = texture2D(uInputTexture, vBottomLeftTexCoord).rgb;
    vec3 bottomRightColor = texture2D(uInputTexture, vBottomRightTexCoord).rgb;
    vec4 centerColor = texture2D(uInputTexture, vTextureCoord);
    vec3 leftColor = texture2D(uInputTexture, vLeftTexCoord).rgb;
    vec3 rightColor = texture2D(uInputTexture, vRightTexCoord).rgb;
    vec3 topColor = texture2D(uInputTexture, vTopTexCoord).rgb;
    vec3 topRightColor = texture2D(uInputTexture, vTopRightTexCoord).rgb;
    vec3 topLeftColor = texture2D(uInputTexture, vTopLeftTexCoord).rgb;
    vec3 resultColor = topLeftColor * uConvolutionMatrix[0][0]
                     + topColor * uConvolutionMatrix[0][1]
                     + topRightColor * uConvolutionMatrix[0][2];
    resultColor += leftColor * uConvolutionMatrix[1][0]
                + centerColor.rgb * uConvolutionMatrix[1][1]
                + rightColor * uConvolutionMatrix[1][2];
    resultColor += bottomLeftColor * uConvolutionMatrix[2][0]
                + bottomColor * uConvolutionMatrix[2][1]
                + bottomRightColor * uConvolutionMatrix[2][2];
    gl_FragColor = vec4(resultColor, centerColor.a);
}

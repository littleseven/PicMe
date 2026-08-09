precision mediump float;
uniform sampler2D uInputTexture;
uniform float uThreshold;
uniform float uQuantizationLevels;
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
    float bottomLeftIntensity = texture2D(uInputTexture, vBottomLeftTexCoord).r;
    float topRightIntensity = texture2D(uInputTexture, vTopRightTexCoord).r;
    float topLeftIntensity = texture2D(uInputTexture, vTopLeftTexCoord).r;
    float bottomRightIntensity = texture2D(uInputTexture, vBottomRightTexCoord).r;
    float leftIntensity = texture2D(uInputTexture, vLeftTexCoord).r;
    float rightIntensity = texture2D(uInputTexture, vRightTexCoord).r;
    float bottomIntensity = texture2D(uInputTexture, vBottomTexCoord).r;
    float topIntensity = texture2D(uInputTexture, vTopTexCoord).r;
    float h = -topLeftIntensity - 2.0 * topIntensity - topRightIntensity
            + bottomLeftIntensity + 2.0 * bottomIntensity + bottomRightIntensity;
    float v = -bottomLeftIntensity - 2.0 * leftIntensity - topLeftIntensity
            + bottomRightIntensity + 2.0 * rightIntensity + topRightIntensity;
    float mag = length(vec2(h, v));
    vec4 color = texture2D(uInputTexture, vTextureCoord);
    vec3 posterized = (floor(color.rgb * uQuantizationLevels) + 0.5) / uQuantizationLevels;
    float thresholdTest = 1.0 - step(uThreshold, mag);
    gl_FragColor = vec4(posterized * thresholdTest, color.a);
}

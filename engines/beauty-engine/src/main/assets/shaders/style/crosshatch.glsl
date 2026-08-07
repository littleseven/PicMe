precision mediump float;
uniform sampler2D uInputTexture;
uniform float uCrossHatchSpacing;
uniform float uLineWidth;
varying vec2 vTextureCoord;
const vec3 W = vec3(0.2125, 0.7154, 0.0721);

void main() {
    float luminance = dot(texture2D(uInputTexture, vTextureCoord).rgb, W);
    vec4 colorToDisplay = vec4(1.0, 1.0, 1.0, 1.0);
    if (luminance < 1.00) {
        if (mod(vTextureCoord.x + vTextureCoord.y, uCrossHatchSpacing) <= uLineWidth) {
            colorToDisplay = vec4(0.0, 0.0, 0.0, 1.0);
        }
    }
    if (luminance < 0.75) {
        if (mod(vTextureCoord.x - vTextureCoord.y, uCrossHatchSpacing) <= uLineWidth) {
            colorToDisplay = vec4(0.0, 0.0, 0.0, 1.0);
        }
    }
    if (luminance < 0.50) {
        if (mod(vTextureCoord.x + vTextureCoord.y - (uCrossHatchSpacing / 2.0), uCrossHatchSpacing) <= uLineWidth) {
            colorToDisplay = vec4(0.0, 0.0, 0.0, 1.0);
        }
    }
    if (luminance < 0.3) {
        if (mod(vTextureCoord.x - vTextureCoord.y - (uCrossHatchSpacing / 2.0), uCrossHatchSpacing) <= uLineWidth) {
            colorToDisplay = vec4(0.0, 0.0, 0.0, 1.0);
        }
    }
    gl_FragColor = colorToDisplay;
}

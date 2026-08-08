// FaceMakeupPass 片段着色器
// 基于 GPUPixel FaceMakeupFilter 片段 Shader，增加可选 tintColor，
// 让纹理 alpha 决定精确区域，颜色由业务色板实时控制。

precision mediump float;

varying vec2 vTextureCoord;
varying vec2 vScreenCoord;

uniform sampler2D uInputTexture;
uniform sampler2D uMakeupTexture;
uniform float uIntensity;
uniform int uBlendMode;
uniform vec3 uTintColor;
uniform float uUseTintColor;

float blendHardLight(float base, float blend) {
    return blend < 0.5 
        ? (2.0 * base * blend)
        : (1.0 - 2.0 * (1.0 - base) * (1.0 - blend));
}

vec3 blendHardLight(vec3 base, vec3 blend) {
    return vec3(
        blendHardLight(base.r, blend.r),
        blendHardLight(base.g, blend.g),
        blendHardLight(base.b, blend.b)
    );
}

vec3 blendMultiply(vec3 base, vec3 blend) {
    return base * blend;
}

float blendOverlay(float base, float blend) {
    return base < 0.5 
        ? (2.0 * base * blend)
        : (1.0 - 2.0 * (1.0 - base) * (1.0 - blend));
}

vec3 blendOverlay(vec3 base, vec3 blend) {
    return vec3(
        blendOverlay(base.r, blend.r),
        blendOverlay(base.g, blend.g),
        blendOverlay(base.b, blend.b)
    );
}

vec3 blendFunc(vec3 base, vec3 blend, int blendMode) {
    if (blendMode == 0) {
        return blend;
    } else if (blendMode == 15) {
        return blendMultiply(base, blend);
    } else if (blendMode == 17) {
        return blendOverlay(base, blend);
    } else if (blendMode == 22) {
        return blendHardLight(base, blend);
    }
    return blend;
}

void main() {
    vec4 textureSample = texture2D(uMakeupTexture, vTextureCoord);
    vec4 bgColor = texture2D(uInputTexture, vScreenCoord);
    
    float alpha = clamp(textureSample.a * uIntensity, 0.0, 1.0);
    if (alpha < 0.01) {
        gl_FragColor = bgColor;
        return;
    }
    
    vec3 textureRgb = textureSample.a > 0.0001
        ? clamp(textureSample.rgb / textureSample.a, 0.0, 1.0)
        : textureSample.rgb;
    vec3 makeupRgb = mix(textureRgb, uTintColor, clamp(uUseTintColor, 0.0, 1.0));
    vec3 blended = blendFunc(bgColor.rgb, makeupRgb, uBlendMode);
    vec3 finalColor = mix(bgColor.rgb, blended, alpha);

    gl_FragColor = vec4(finalColor, bgColor.a);
}

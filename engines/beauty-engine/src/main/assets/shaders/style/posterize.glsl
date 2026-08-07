precision mediump float;
uniform sampler2D uInputTexture;
uniform float uColorLevels;
varying vec2 vTextureCoord;

void main() {
    vec4 color = texture2D(uInputTexture, vTextureCoord);
    gl_FragColor = floor((color * uColorLevels) + vec4(0.5)) / uColorLevels;
}

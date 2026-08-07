uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;
uniform float uSmoothing;
uniform float uWhitening;
uniform float uSharpen;               // 锐化强度 0~1
uniform float uBigEyes;
uniform float uSlimFace;
uniform vec2 uFaceCenter;
uniform vec2 uLeftEye;
uniform vec2 uRightEye;
uniform vec2 uMouthCenter;
uniform vec2 uMouthLeft;
uniform vec2 uMouthRight;
uniform vec2 uUpperLipCenter;
uniform vec2 uLowerLipCenter;
uniform vec2 uLipOuterContourPoints[20];
uniform float uLipOuterContourCount;
uniform vec2 uLipInnerContourPoints[20];
uniform float uLipInnerContourCount;
uniform float uFaceRadius;
uniform float uHasFace;
uniform float uLipColor;
uniform int uLipColorIndex;
uniform float uBlush;
uniform int uBlushColorFamily;
uniform vec2 uLeftCheekContourPoints[20];
uniform float uLeftCheekContourCount;
uniform vec2 uRightCheekContourPoints[20];
uniform float uRightCheekContourCount;
uniform vec4 uCMRow0;
uniform vec4 uCMRow1;
uniform vec4 uCMRow2;
uniform vec4 uCMRow3;
uniform vec4 uCMOffset;
uniform float uHasColorMatrix;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
uniform float uTint;
uniform float uBrightness;
uniform float uWarmth;               // 暖色调强度 0~1
uniform float uRedAdj;
uniform float uGreenAdj;
uniform float uBlueAdj;
uniform float uContourThinFace;      // 轮廓瘦脸强度 0~1
uniform int uDebugMode;

// GPUPixel 瘦脸/大眼：106点人脸关键点
uniform float uFacePoints[106 * 2];
uniform float uAspectRatio;
uniform int uUseGpupixelWarp;  // 0=使用原有warp, 1=使用GPUPixel风格warp

varying vec2 vTextureCoord;
varying vec2 vWarpCoord;

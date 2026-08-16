vec3 applyColorGrade(vec3 color) {
    color *= pow(2.0, uExposure);
    color = (color - 0.5) * uContrast + 0.5;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, uSaturation);
    // 色温强度系数 0.05（2026-08-16 由 0.01 上调，与 Android 端保持同一 GLSL 源一致）
    color.r += uTemperature * 0.05;
    color.b -= uTemperature * 0.05;
    color.g += uTint * 0.005;
    color.b -= uTint * 0.005;
    color += uBrightness;
    color.r *= uRedAdj;
    color.g *= uGreenAdj;
    color.b *= uBlueAdj;
    return clamp(color, 0.0, 1.0);
}

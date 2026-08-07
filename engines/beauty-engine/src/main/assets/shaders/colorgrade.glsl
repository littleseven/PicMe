vec3 applyColorGrade(vec3 color) {
    color *= pow(2.0, uExposure);
    color = (color - 0.5) * uContrast + 0.5;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luma), color, uSaturation);
    color.r += uTemperature * 0.01;
    color.b -= uTemperature * 0.01;
    color.g += uTint * 0.005;
    color.b -= uTint * 0.005;
    color += uBrightness;
    color.r *= uRedAdj;
    color.g *= uGreenAdj;
    color.b *= uBlueAdj;
    return clamp(color, 0.0, 1.0);
}

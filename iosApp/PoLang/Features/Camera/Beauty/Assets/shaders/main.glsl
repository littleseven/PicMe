void main() {
    // 多Pass模式下：vWarpCoord=变换后坐标(用于warp)，vTextureCoord=未变换坐标(用于采样)
    // 单Pass模式下：两者相同（vertex shader中vWarpCoord = vTextureCoord）
    vec2 warpInputCoord = vWarpCoord;
    vec2 sampleCoord = vTextureCoord;
    
    vec2 warpedUv;
    if (uUseGpupixelWarp > 0) {
        // GPUPixel风格：瘦脸和大眼分开调用，便于独立调试
        warpedUv = warpCoordGpupixelThinFace(warpInputCoord);
        warpedUv = warpCoordGpupixelBigEye(warpedUv);
    } else {
        warpedUv = warpCoord(warpInputCoord);
    }
    
    // Debug Mode Definitions:
    // 1 = Show Skin Mask
    // 2 = Show Warp Offset
    // 3 = Show BigEye Radius (GPUPixel)
    // 4 = Show ThinFace Radius (GPUPixel)
    // 5 = Show All GPUPixel Warp (BigEye + ThinFace combined)

    // Debug Mode: Show Skin Mask
    if (uDebugMode == 1) {
        float mask = skinMask(warpedUv);
        gl_FragColor = vec4(mask, mask, mask, 1.0);
        return;
    }

    // Debug Mode: Show Warp Offset
    if (uDebugMode == 2) {
        vec2 offset = warpedUv - vTextureCoord;
        float len = length(offset) * 100.0;
        gl_FragColor = vec4(len, 0.0, 1.0 - len, 1.0);
        return;
    }

    // Debug Mode: Show BigEye Radius (GPUPixel)
    if (uDebugMode == 3) {
        vec3 debugColor = warpCoordGpupixelBigEyeDebug(vTextureCoord);
        gl_FragColor = vec4(debugColor, 1.0);
        return;
    }

    // Debug Mode: Show ThinFace Radius (GPUPixel)
    if (uDebugMode == 4) {
        vec3 debugColor = warpCoordGpupixelThinFaceDebug(vTextureCoord);
        gl_FragColor = vec4(debugColor, 1.0);
        return;
    }

    // Debug Mode: Show All GPUPixel Warp (combined)
    if (uDebugMode == 5) {
        vec3 bigEyeColor = warpCoordGpupixelBigEyeDebug(vTextureCoord);
        vec3 thinFaceColor = warpCoordGpupixelThinFaceDebug(vTextureCoord);
        // 合并显示：红色通道=大眼，绿色通道=瘦脸
        vec3 combined = vec3(bigEyeColor.r, thinFaceColor.r, 0.0);
        // 如果两者都无影响，显示绿色
        if (bigEyeColor.g > 0.5 && thinFaceColor.g > 0.5) {
            combined = vec3(0.0, 1.0, 0.0);
        }
        gl_FragColor = vec4(combined, 1.0);
        return;
    }

    // Normal Rendering Pipeline
    // warp后的坐标用于皮肤mask、唇色、腮红等定位
    float mask = skinMask(warpedUv);
    // 使用warp后的坐标采样纹理，实现瘦脸/大眼变形效果
    vec4 smoothed = smoothSkin(warpedUv, uSmoothing);
    vec4 whitened = whitenSkin(smoothed, uWhitening, mask);
    vec4 lipTinted = applyLipTint(whitened, warpedUv);
    
    vec3 makeupColor = applyBlush(lipTinted.rgb, warpedUv);
    vec3 graded = applyColorGrade(makeupColor);
    vec4 finalColor = vec4(clamp(graded, 0.0, 1.0), lipTinted.a);

    // Color Matrix Filter
    if (uHasColorMatrix > 0.5) {
        vec4 src = finalColor;
        float r = dot(uCMRow0, src) + uCMOffset.r;
        float g = dot(uCMRow1, src) + uCMOffset.g;
        float b = dot(uCMRow2, src) + uCMOffset.b;
        float a = dot(uCMRow3, src) + uCMOffset.a;
        finalColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), clamp(a, 0.0, 1.0));
    }

    gl_FragColor = finalColor;
}

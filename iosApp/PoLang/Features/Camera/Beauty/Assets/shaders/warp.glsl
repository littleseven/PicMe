vec2 applyBigEye(vec2 uv, vec2 eyeCenter, float radius, float intensity) {
    vec2 dir = uv - eyeCenter;
    float dist = length(dir);
    if (dist >= radius) {
        return uv;
    }
    float percent = 1.0 - dist / radius;
    float factor = 1.0 - intensity * percent * 0.38;
    return eyeCenter + dir * factor;
}

vec2 applySlimFace(vec2 uv, vec2 center, float radius, float intensity) {
    vec2 dir = uv - center;
    float dist = length(dir);
    if (dist >= radius) {
        return uv;
    }
    vec2 eyeAxis = normalize(uRightEye - uLeftEye);
    if (length(eyeAxis) < 0.0001) {
        eyeAxis = vec2(1.0, 0.0);
    }
    float percent = 1.0 - dist / radius;
    float strength = intensity * percent * percent * 0.45;
    float axisOffset = dot(dir, eyeAxis) / max(radius, 0.0001);
    vec2 offset = eyeAxis * axisOffset * strength * radius;
    return uv - offset;
}

vec2 warpCoord(vec2 uv) {
    if (uHasFace < 0.5) {
        return uv;
    }
    float eyeRadius = max(uFaceRadius * 0.22, 0.05);
    vec2 warped = uv;
    if (uBigEyes > 0.001) {
        warped = applyBigEye(warped, uLeftEye, eyeRadius, uBigEyes);
        warped = applyBigEye(warped, uRightEye, eyeRadius, uBigEyes);
    }
    if (abs(uSlimFace) > 0.001) {
        warped = applySlimFace(warped, uFaceCenter, uFaceRadius, uSlimFace);
    }
    return clamp(warped, 0.0, 1.0);
}

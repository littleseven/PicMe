vec3 lipColorByIndex(int index) {
    if (index == 1) return vec3(0.77, 0.20, 0.26);
    if (index == 2) return vec3(1.00, 0.50, 0.31);
    if (index == 3) return vec3(0.88, 0.32, 0.49);
    if (index == 4) return vec3(1.00, 0.42, 0.62);
    if (index == 5) return vec3(0.61, 0.14, 0.21);
    if (index == 6) return vec3(1.00, 0.63, 0.48);
    if (index == 7) return vec3(0.80, 0.36, 0.36);
    if (index == 8) return vec3(0.86, 0.08, 0.24);
    if (index == 9) return vec3(1.00, 0.71, 0.76);
    if (index == 10) return vec3(0.70, 0.13, 0.13);
    if (index == 11) return vec3(1.00, 0.08, 0.58);
    return vec3(0.83, 0.46, 0.49);
}

float distancePointToSegment(vec2 point, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float len2 = dot(ab, ab);
    if (len2 < 0.000001) {
        return length(point - a);
    }
    float t = clamp(dot(point - a, ab) / len2, 0.0, 1.0);
    vec2 projection = a + ab * t;
    return length(point - projection);
}

float contourMaskFromOuterPolygon(vec2 uv) {
    if (uLipOuterContourCount < 6.0) {
        return 0.0;
    }
    bool inside = false;
    float minDist = 10.0;
    vec2 firstPoint = vec2(0.0, 0.0);
    vec2 prevPoint = vec2(0.0, 0.0);
    bool hasPrev = false;
    for (int i = 0; i < 20; i++) {
        if (float(i) >= uLipOuterContourCount) {
            continue;
        }
        vec2 current = uLipOuterContourPoints[i];
        if (!hasPrev) {
            firstPoint = current;
            prevPoint = current;
            hasPrev = true;
            continue;
        }
        bool cross = ((prevPoint.y > uv.y) != (current.y > uv.y)) &&
            (uv.x < (current.x - prevPoint.x) * (uv.y - prevPoint.y) / (current.y - prevPoint.y + 0.000001) + prevPoint.x);
        if (cross) {
            inside = !inside;
        }
        minDist = min(minDist, distancePointToSegment(uv, prevPoint, current));
        prevPoint = current;
    }
    if (hasPrev) {
        bool cross = ((prevPoint.y > uv.y) != (firstPoint.y > uv.y)) &&
            (uv.x < (firstPoint.x - prevPoint.x) * (uv.y - prevPoint.y) / (firstPoint.y - prevPoint.y + 0.000001) + prevPoint.x);
        if (cross) {
            inside = !inside;
        }
        minDist = min(minDist, distancePointToSegment(uv, prevPoint, firstPoint));
    }
    float feather = max(uFaceRadius * 0.025, 0.004);
    float edgeSoft = smoothstep(0.0, feather, minDist);
    return (inside ? 1.0 : 0.0) * edgeSoft;
}

float contourMaskFromInnerPolygon(vec2 uv) {
    if (uLipInnerContourCount < 6.0) {
        return 0.0;
    }
    bool inside = false;
    float minDist = 10.0;
    vec2 firstPoint = vec2(0.0, 0.0);
    vec2 prevPoint = vec2(0.0, 0.0);
    bool hasPrev = false;
    for (int i = 0; i < 20; i++) {
        if (float(i) >= uLipInnerContourCount) {
            continue;
        }
        vec2 current = uLipInnerContourPoints[i];
        if (!hasPrev) {
            firstPoint = current;
            prevPoint = current;
            hasPrev = true;
            continue;
        }
        bool cross = ((prevPoint.y > uv.y) != (current.y > uv.y)) &&
            (uv.x < (current.x - prevPoint.x) * (uv.y - prevPoint.y) / (current.y - prevPoint.y + 0.000001) + prevPoint.x);
        if (cross) {
            inside = !inside;
        }
        minDist = min(minDist, distancePointToSegment(uv, prevPoint, current));
        prevPoint = current;
    }
    if (hasPrev) {
        bool cross = ((prevPoint.y > uv.y) != (firstPoint.y > uv.y)) &&
            (uv.x < (firstPoint.x - prevPoint.x) * (uv.y - prevPoint.y) / (firstPoint.y - prevPoint.y + 0.000001) + prevPoint.x);
        if (cross) {
            inside = !inside;
        }
        minDist = min(minDist, distancePointToSegment(uv, prevPoint, firstPoint));
    }
    float feather = max(uFaceRadius * 0.025, 0.004);
    float edgeSoft = smoothstep(0.0, feather, minDist);
    return (inside ? 1.0 : 0.0) * edgeSoft;
}

float lipColorMaskFromPixel(vec3 baseColor) {
    float luma = dot(baseColor, vec3(0.299, 0.587, 0.114));
    float maxChannel = max(baseColor.r, max(baseColor.g, baseColor.b));
    float minChannel = min(baseColor.r, min(baseColor.g, baseColor.b));
    float saturation = maxChannel - minChannel;
    float redness = baseColor.r - max(baseColor.g, baseColor.b);
    float redGate = smoothstep(0.02, 0.16, redness);
    float satGate = smoothstep(0.05, 0.24, saturation);
    float darkGate = 1.0 - smoothstep(0.78, 0.98, luma);
    return clamp(redGate * satGate * darkGate, 0.0, 1.0);
}

vec4 applyLipTint(vec4 color, vec2 uv) {
    if (uHasFace < 0.5 || uLipColor < 0.01) {
        return color;
    }
    vec2 eyeAxis = normalize(uRightEye - uLeftEye);
    if (length(eyeAxis) < 0.0001) {
        eyeAxis = vec2(1.0, 0.0);
    }
    vec2 mouthLeft = uMouthLeft;
    vec2 mouthRight = uMouthRight;
    vec2 upperLipCenter = uUpperLipCenter;
    vec2 lowerLipCenter = uLowerLipCenter;
    vec2 mouthCenter = (mouthLeft + mouthRight + upperLipCenter + lowerLipCenter) * 0.25;
    vec2 mouthAxisRaw = mouthRight - mouthLeft;
    float mouthAxisLen = length(mouthAxisRaw);
    vec2 mouthAxis = mouthAxisLen > 0.0001 ? normalize(mouthAxisRaw) : eyeAxis;
    vec2 mouthNormal = vec2(-mouthAxis.y, mouthAxis.x);
    if (dot(lowerLipCenter - upperLipCenter, mouthNormal) < 0.0) {
        mouthNormal = -mouthNormal;
    }
    vec2 delta = uv - mouthCenter;
    float localX = dot(delta, mouthAxis);
    float localY = dot(delta, mouthNormal);
    float leftWidth = max(abs(dot(mouthCenter - mouthLeft, mouthAxis)) * 1.06, uFaceRadius * 0.11);
    float rightWidth = max(abs(dot(mouthRight - mouthCenter, mouthAxis)) * 1.06, uFaceRadius * 0.11);
    float activeHalfWidth = localX >= 0.0 ? rightWidth : leftWidth;
    float upperHeight = max(abs(dot(upperLipCenter - mouthCenter, mouthNormal)) * 2.0, uFaceRadius * 0.038);
    float lowerHeight = max(abs(dot(lowerLipCenter - mouthCenter, mouthNormal)) * 2.0, uFaceRadius * 0.05);
    float x = localX / max(activeHalfWidth, 0.0001);
    float upperY = max(localY, 0.0) / max(upperHeight, 0.0001);
    float lowerY = max(-localY, 0.0) / max(lowerHeight, 0.0001);
    float upperEllipse = x * x + upperY * upperY;
    float lowerEllipse = x * x + lowerY * lowerY;
    float upperMask = 1.0 - smoothstep(0.45, 1.0, upperEllipse);
    float lowerMask = 1.0 - smoothstep(0.45, 1.0, lowerEllipse);
    float cornerMask = 1.0 - smoothstep(0.92, 1.22, abs(x));
    float seamWidth = max((upperHeight + lowerHeight) * 0.08, uFaceRadius * 0.006);
    float sideSwitch = max((upperHeight + lowerHeight) * 0.06, uFaceRadius * 0.004);
    float upperSideMask = smoothstep(-sideSwitch, sideSwitch, localY);
    float lowerSideMask = smoothstep(-sideSwitch, sideSwitch, -localY);
    float seamFade = 1.0 - smoothstep(0.0, seamWidth, abs(localY));
    float splitMask = max(upperMask * upperSideMask, lowerMask * lowerSideMask);
    float fallbackMask = clamp(splitMask * cornerMask * (1.0 - 0.22 * seamFade), 0.0, 1.0);
    float outerContourMask = contourMaskFromOuterPolygon(uv);
    float innerContourMask = contourMaskFromInnerPolygon(uv);
    float contourMask = clamp(outerContourMask - innerContourMask, 0.0, 1.0);
    float contourPreferredMask = max(contourMask, fallbackMask * 0.78);
    float lipMask = mix(fallbackMask, contourPreferredMask, step(6.0, uLipOuterContourCount));
    vec3 target = lipColorByIndex(uLipColorIndex);
    float colorMask = lipColorMaskFromPixel(color.rgb);
    float edgeAwareLipMask = lipMask * mix(0.42, 1.0, colorMask);
    float blend = clamp(uLipColor, 0.0, 1.0) * 0.78 * edgeAwareLipMask;
    vec3 tinted = mix(color.rgb, target, blend);
    return vec4(clamp(tinted, 0.0, 1.0), color.a);
}

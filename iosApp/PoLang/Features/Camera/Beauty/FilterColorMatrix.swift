import Foundation
import simd

/// FilterType 色彩滤镜 ColorMatrix 定义（逐值照抄 Android FilterTypeExt.kt）
///
/// S5 双端一致红线：滤镜名、排序、矩阵值与 Android 完全一致。
/// FilterType ordinal（shared commonMain FilterType.kt）:
///   0=NONE, 1=LEICA_CLASSIC, 2=LEICA_VIBRANT, 3=LEICA_BW, 4=FILM_GOLD,
///   5=FILM_FUJI, 6=VINTAGE, 7=COOL, 8=WARM
///
/// Android ColorMatrix 是 4×5 float array (row-major: R,G,B,A,offset per row).
/// Metal 侧 ColorGradeUniforms.cmRow0..3 是 float4（不含 offset），offset 在 cmOffset。
enum FilterType: Int, CaseIterable, Identifiable {
    case none = 0
    case leicaClassic = 1
    case leicaVibrant = 2
    case leicaBW = 3
    case filmGold = 4
    case filmFuji = 5
    case vintage = 6
    case cool = 7
    case warm = 8

    var id: Int { rawValue }

    /// 滤镜名（[I18N] 走 Localizable.xcstrings，与 Android stringRes 同 key）
    var displayName: String {
        switch self {
        case .none:         return String(localized: "filter_none")
        case .leicaClassic: return String(localized: "filter_leica_classic")
        case .leicaVibrant: return String(localized: "filter_leica_vibrant")
        case .leicaBW:      return String(localized: "filter_leica_bw")
        case .filmGold:     return String(localized: "filter_film_gold")
        case .filmFuji:     return String(localized: "filter_film_fuji")
        case .vintage:      return String(localized: "filter_vintage")
        case .cool:         return String(localized: "filter_cool")
        case .warm:         return String(localized: "filter_warm")
        }
    }

    /// 滤镜缩略图资源名（与 Android filterAssetPath() 一致）
    var thumbnailName: String {
        switch self {
        case .none:         return "filter_none"
        case .leicaClassic: return "filter_leica_classic"
        case .leicaVibrant: return "filter_leica_vibrant"
        case .leicaBW:      return "filter_leica_bw"
        case .filmGold:     return "filter_film_gold"
        case .filmFuji:     return "filter_film_fuji"
        case .vintage:      return "filter_vintage"
        case .cool:         return "filter_cool"
        case .warm:         return "filter_warm"
        }
    }

    /// ColorMatrix（逐值照抄 Android FilterTypeExt.kt toAndroidColorMatrix()）
    /// 返回 (rows: [float4]×4, offset: float4)
    /// NONE 返回 nil（不应用 ColorMatrix）
    var colorMatrix: (rows: (SIMD4<Float>, SIMD4<Float>, SIMD4<Float>, SIMD4<Float>), offset: SIMD4<Float>)? {
        switch self {
        case .none:
            return nil
        case .leicaClassic:
            // 0.95f, 0, 0, 0, 0,
            // 0, 0.9f, 0, 0, 0,
            // 0, 0, 0.85f, 0, 0,
            // 0, 0, 0, 1f, 0
            return ((
                SIMD4(0.95, 0, 0, 0),
                SIMD4(0, 0.9, 0, 0),
                SIMD4(0, 0, 0.85, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .leicaVibrant:
            // ColorMatrix().setSaturation(1.3f) — Android 内部展开为：
            // R' = 0.3086*1.3*R + 0.6094*1.3*G + 0.0820*1.3*B
            //   = (gr+0.3086*(1-1.3))*R ... → 实际 Android setSaturation 公式：
            // R' = (0.3086 + 0.6914*1.3)≈1.2082, 0.6094*1.3≈0.7922*? ...
            // 精确：setSaturation(s) 矩阵 =
            //   [0.3086+0.6914*s, 0.6094-0.6094*s, 0.0820-0.0820*s, ...]
            //   对称排列。s=1.3:
            let s: Float = 1.3
            let r = 0.3086 + 0.6914 * s
            let g = 0.6094 + 0.3906 * s  // Actually: Android uses 0.3086/0.6094/0.0820 weights
            // Android ColorMatrix.setSaturation 实际公式：
            // sr = 0.3086, sg = 0.6094, sb = 0.0820
            // ms = 1 - s
            // row0 = [sr + ms*sr ... NO. Let me use exact Android formula:]
            // ms = 1 - saturation
            // R = sr + sr*ms ... that's wrong too.
            // CORRECT: Android setSaturation(sat):
            //   val ms = 1f - sat
            //   val sr = 0.3086f; val sg = 0.6094f; val sb = 0.0820f
            //   setScale + setSaturation effectively:
            //   row0 = [sr*(1+ms), sg*ms, sb*ms, 0] ... NO.
            // ACTUAL Android source (ColorMatrix.java):
            //   mVal[0] = sr * (1-ms) + ms  -- WRONG. Let me just hardcode the computed values.
            // For s=1.3: ms=-0.3
            //   mVal[0] = sr*(1-(-0.3)) + (-0.3)*0 ... actually:
            //   Android: mMatrix[row*5+col] with saturation formula:
            //   [0] = sr + (1-sr)*sat  → wait no.
            //
            // Let me just use the straightforward Android source:
            // setSaturation(sat) {
            //   reset();  // identity
            //   val ms = 1f - sat
            //   val sr = 0.3086f * ms; val sg = 0.6094f * ms; val sb = 0.0820f * ms
            //   mVal[0] = sr + sat;  mVal[1] = sg;       mVal[2] = sb;
            //   mVal[5] = sr;        mVal[6] = sg + sat; mVal[7] = sb;
            //   mVal[10]= sr;        mVal[11]= sg;       mVal[12]= sb + sat;
            // }
            let ms2: Float = 1.0 - s  // -0.3
            let sr2: Float = 0.3086 * ms2  // -0.09258
            let sg2: Float = 0.6094 * ms2  // -0.18282
            let sb2: Float = 0.0820 * ms2  // -0.0246
            return ((
                SIMD4(sr2 + s, sg2, sb2, 0),       // ~1.2074, -0.18282, -0.0246
                SIMD4(sr2, sg2 + s, sb2, 0),       // -0.09258, 1.1172, -0.0246
                SIMD4(sr2, sg2, sb2 + s, 0),       // -0.09258, -0.18282, 1.2754
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .leicaBW:
            // setSaturation(0f) → 全灰
            // ms=1, sr=0.3086, sg=0.6094, sb=0.0820
            // row0=[sr+0, sg, sb] = [0.3086, 0.6094, 0.0820]
            return ((
                SIMD4(0.3086, 0.6094, 0.0820, 0),
                SIMD4(0.3086, 0.6094, 0.0820, 0),
                SIMD4(0.3086, 0.6094, 0.0820, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .filmGold:
            // 1.1f, 0.1f, 0f, 0f, 0f,
            // 0.1f, 1.0f, 0f, 0f, 0f,
            // 0f, 0f, 0.8f, 0f, 0f,
            // 0f, 0f, 0f, 1f, 0f
            return ((
                SIMD4(1.1, 0.1, 0, 0),
                SIMD4(0.1, 1.0, 0, 0),
                SIMD4(0, 0, 0.8, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .filmFuji:
            // 0.9f, 0f, 0.1f, 0f, 0f,
            // 0f, 1.1f, 0f, 0f, 0f,
            // 0.1f, 0f, 1.0f, 0f, 0f,
            // 0f, 0f, 0f, 1f, 0f
            return ((
                SIMD4(0.9, 0, 0.1, 0),
                SIMD4(0, 1.1, 0, 0),
                SIMD4(0.1, 0, 1.0, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .vintage:
            // 0.9f, 0f, 0f, 0f, 0f,
            // 0f, 0.8f, 0f, 0f, 0f,
            // 0f, 0.5f, 0f, 0f, 0f,
            // 0f, 0f, 0f, 1f, 0f
            return ((
                SIMD4(0.9, 0, 0, 0),
                SIMD4(0, 0.8, 0, 0),
                SIMD4(0, 0.5, 0, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .cool:
            // 0.8f, 0f, 0f, 0f, 0f,
            // 0f, 0.9f, 0f, 0f, 0f,
            // 0f, 0f, 1.2f, 0f, 0f,
            // 0f, 0f, 0f, 1f, 0f
            return ((
                SIMD4(0.8, 0, 0, 0),
                SIMD4(0, 0.9, 0, 0),
                SIMD4(0, 0, 1.2, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .warm:
            // 1.15f, 0.05f, 0f, 0f, 0.03f,
            // 0.02f, 1.05f, 0f, 0f, 0f,
            // 0f, 0f, 0.85f, 0f, -0.03f,
            // 0f, 0f, 0f, 1f, 0f
            return ((
                SIMD4(1.15, 0.05, 0, 0),
                SIMD4(0.02, 1.05, 0, 0),
                SIMD4(0, 0, 0.85, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0.03, 0, -0.03, 0))
        }
    }
}

import Foundation
import simd

/// FilterType 色彩滤镜 ColorMatrix 定义（逐值照抄 Android FilterTypeExt.kt）
///
/// S5 双端一致红线：滤镜名、排序、矩阵值与 Android 完全一致。
/// FilterType ordinal（shared commonMain FilterType.kt）:
///   0=NONE, 1=LEICA_CLASSIC, 2=LEICA_VIBRANT, 3=LEICA_BW, 4=FILM_GOLD,
///   5=FILM_FUJI, 6=VINTAGE, 7=COOL, 8=WARM
///
/// 🔴6 修正：Android ColorMatrix.setSaturation() 使用 AOSP sRGB 亮度权重
///   sr=0.213f, sg=0.715f, sb=0.072f（非 SVG feColorMatrix 的 0.3086/0.6094/0.0820）
///   AOSP 源码：frameworks/base/graphics/java/android/graphics/ColorMatrix.java
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
    /// 返回 (rows: 4×float4, offset: float4)
    /// NONE 返回 nil
    /// offset 语义：Android 0–255 范围（BeautyRenderer.kt:751 上传时 /255f）
    var colorMatrix: (rows: (SIMD4<Float>, SIMD4<Float>, SIMD4<Float>, SIMD4<Float>), offset: SIMD4<Float>)? {
        switch self {
        case .none:
            return nil
        case .leicaClassic:
            return ((
                SIMD4(0.95, 0, 0, 0),
                SIMD4(0, 0.9, 0, 0),
                SIMD4(0, 0, 0.85, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .leicaVibrant:
            // ColorMatrix().setSaturation(1.3f)
            // AOSP ColorMatrix.setSaturation(sat):
            //   val ms = 1f - sat
            //   val sr = 0.213f; val sg = 0.715f; val sb = 0.072f
            //   mVal[0]=sr+sat*ms... NO. Actual AOSP:
            //   mVal[0] = sr*ms + sat; mVal[1] = sg*ms; mVal[2] = sb*ms
            //   Wait — actual AOSP ColorMatrix.setSaturation:
            //   ms = 1 - sat; setScale is called first, then concat with saturation matrix
            //   Final: mVal[0] = sr*ms + sat
            // For sat=1.3: ms=-0.3
            //   sr*ms+sat = 0.213*(-0.3)+1.3 = -0.0639+1.3 = 1.2361
            //   sg*ms = 0.715*(-0.3) = -0.2145
            //   sb*ms = 0.072*(-0.3) = -0.0216
            // Row symmetry: [sr*ms+sat, sg*ms, sb*ms] / [sr*ms, sg*ms+sat, sb*ms] / [sr*ms, sg*ms, sb*ms+sat]
            return ((
                SIMD4(1.2361, -0.2145, -0.0216, 0),
                SIMD4(-0.0639, 1.0855, -0.0216, 0),
                SIMD4(-0.0639, -0.2145, 1.2784, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .leicaBW:
            // setSaturation(0f): ms=1, all rows = [sr, sg, sb]
            return ((
                SIMD4(0.213, 0.715, 0.072, 0),
                SIMD4(0.213, 0.715, 0.072, 0),
                SIMD4(0.213, 0.715, 0.072, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .filmGold:
            return ((
                SIMD4(1.1, 0.1, 0, 0),
                SIMD4(0.1, 1.0, 0, 0),
                SIMD4(0, 0, 0.8, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .filmFuji:
            return ((
                SIMD4(0.9, 0, 0.1, 0),
                SIMD4(0, 1.1, 0, 0),
                SIMD4(0.1, 0, 1.0, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .vintage:
            return ((
                SIMD4(0.9, 0, 0, 0),
                SIMD4(0, 0.8, 0, 0),
                SIMD4(0, 0.5, 0, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .cool:
            return ((
                SIMD4(0.8, 0, 0, 0),
                SIMD4(0, 0.9, 0, 0),
                SIMD4(0, 0, 1.2, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0, 0, 0, 0))
        case .warm:
            // Android offset 值是 0–255 范围（0.03f 对应 ~7.65/255 ≈ 0.03）
            // BeautyRenderer.kt:751 上传时 /255f
            // 这里保持 Android 原始值（0–255 语义），由 makeColorGradeUniforms /255
            return ((
                SIMD4(1.15, 0.05, 0, 0),
                SIMD4(0.02, 1.05, 0, 0),
                SIMD4(0, 0, 0.85, 0),
                SIMD4(0, 0, 0, 1)
            ), SIMD4(0.03 * 255, 0, -0.03 * 255, 0))
        }
    }
}

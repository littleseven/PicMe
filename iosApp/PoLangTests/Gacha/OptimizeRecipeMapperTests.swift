import XCTest
@testable import PoLang

/// preset→EditRecipe 映射测试。
/// 覆盖：映射继承（crop/markup/filterIntensity/vignette 自 base，beauty/filter/adjust 覆盖）、
/// 全新基底（baseRecipe=nil）、buildExplanation 8 场景穷举（返回 i18n key 非硬编码文案）、
/// 滤镜名别名解析（含中英文/空格/连字符归一化）、Android assets presets JSON 原样解码。
final class OptimizeRecipeMapperTests: XCTestCase {

    // MARK: - 工具

    private func preset() -> OptimizePreset {
        OptimizePreset(
            scene: OptimizeScene.selfie.rawValue,
            beauty: BeautyPreset(enabled: true, smoothing: 35, whitening: 25,
                                 slimFace: 10, bigEyes: 15, lipColor: 25, blush: 10),
            filter: FilterPreset(colorFilter: "LEICA_VIBRANT", styleFilter: "NONE"),
            adjustment: AdjustmentPreset(brightness: 5, exposure: 0, contrast: 52,
                                         saturation: 102, temperature: 5200, tint: 2)
        )
    }

    private func baseRecipe() -> EditRecipe {
        var base = EditRecipe(sourceUri: "file:///tmp/original.jpg")
        base.crop.rotation = 90
        base.crop.aspectRatio = .square
        base.filterIntensity = 0.5
        base.adjustments.vignette = 20
        base.markup = [.text(id: "t1",
                             text: "hi",
                             position: NormPoint(x: 0.5, y: 0.5),
                             color: 0xFF000000,
                             size: 0.05)]
        return base
    }

    // MARK: - 映射继承

    func testMappingInheritsBaseNonAiFields() {
        let mapped = OptimizeRecipeMapper.toEditRecipe(preset: preset(),
                                                       sourceUri: "file:///tmp/candidate.jpg",
                                                       baseRecipe: baseRecipe())
        // crop / markup / filterIntensity 继承 base
        XCTAssertEqual(mapped.crop.rotation, 90)
        XCTAssertEqual(mapped.crop.aspectRatio, .square)
        XCTAssertEqual(mapped.filterIntensity, 0.5)
        XCTAssertEqual(mapped.markup.count, 1)
        // sourceUri 覆盖
        XCTAssertEqual(mapped.sourceUri, "file:///tmp/candidate.jpg")
    }

    func testMappingOverridesAiDimensions() {
        let p = preset()
        let mapped = OptimizeRecipeMapper.toEditRecipe(preset: p,
                                                       sourceUri: "file:///tmp/candidate.jpg",
                                                       baseRecipe: baseRecipe())
        // beauty 全字段覆盖
        XCTAssertEqual(mapped.beauty.enabled, true)
        XCTAssertEqual(mapped.beauty.smoothing, 35)
        XCTAssertEqual(mapped.beauty.whitening, 25)
        XCTAssertEqual(mapped.beauty.slimFace, 10)
        XCTAssertEqual(mapped.beauty.bigEyes, 15)
        XCTAssertEqual(mapped.beauty.lipColor, 25)
        XCTAssertEqual(mapped.beauty.blush, 10)
        // filter 解析为枚举
        XCTAssertEqual(mapped.colorFilter, .leicaVibrant)
        XCTAssertEqual(mapped.styleFilter, .none)
        // adjustment 6 参数覆盖；vignette 不在 AI 维度——对齐 Android toAdjustmentRecipe
        // 的整体替换语义（AdjustmentRecipe 全新构造），vignette 重置默认 0 而非继承 base 的 20
        XCTAssertEqual(mapped.adjustments.brightness, 5)
        XCTAssertEqual(mapped.adjustments.exposure, 0)
        XCTAssertEqual(mapped.adjustments.contrast, 52)
        XCTAssertEqual(mapped.adjustments.saturation, 102)
        XCTAssertEqual(mapped.adjustments.temperature, 5200)
        XCTAssertEqual(mapped.adjustments.tint, 2)
        XCTAssertEqual(mapped.adjustments.vignette, 0, "对齐 Android：adjustments 整体替换，vignette 不继承 base")
    }

    func testMappingFromFreshBaseWhenBaseRecipeNil() {
        let mapped = OptimizeRecipeMapper.toEditRecipe(preset: preset(),
                                                       sourceUri: "file:///tmp/x.jpg",
                                                       baseRecipe: nil)
        XCTAssertEqual(mapped.crop.rotation, 0, "无 base 时 crop 取全新默认")
        XCTAssertTrue(mapped.markup.isEmpty)
        XCTAssertEqual(mapped.filterIntensity, 1.0)
    }

    // MARK: - buildExplanation 8 场景穷举（i18n key）

    func testBuildExplanationReturnsI18nKeysForAllScenes() {
        let expected: [OptimizeScene: String] = [
            .selfie: "ai_optimize_explain_selfie",
            .portrait: "ai_optimize_explain_portrait",
            .group: "ai_optimize_explain_group",
            .food: "ai_optimize_explain_food",
            .landscape: "ai_optimize_explain_landscape",
            .lowLight: "ai_optimize_explain_low_light",
            .document: "ai_optimize_explain_document",
            .general: "ai_optimize_explain_general",
        ]
        XCTAssertEqual(OptimizeScene.allCases.count, 8, "场景穷举前提：8 个场景")
        for scene in OptimizeScene.allCases {
            let key = OptimizeRecipeMapper.buildExplanation(scene)
            XCTAssertEqual(key, expected[scene], "scene=\(scene.rawValue)")
            XCTAssertTrue(key.hasPrefix("ai_optimize_explain_"),
                          "必须返回 key 而非硬编码文案（[I18N] 红线）")
        }
        XCTAssertEqual(Set(expected.values).count, 8, "8 个 key 互不重复")
    }

    // MARK: - 滤镜名解析（别名表对齐 Android OptimizeRecipeMapper.kt）

    func testResolveFilterTypeAliases() {
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("NONE"), .none)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("leica_vibrant"), .leicaVibrant)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("leica vibrant"), .leicaVibrant, "空格归一化为下划线")
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("vivid"), .leicaVibrant)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("徕卡鲜艳"), .leicaVibrant)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("LEICA_CLASSIC"), .leicaClassic)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("徕卡黑白"), .leicaBW)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("film-gold"), .filmGold, "连字符归一化为下划线")
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("富士"), .filmFuji)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("RETRO"), .vintage)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("COLD"), .cool)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("暖色调"), .warm)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("WARM"), .warm)
        XCTAssertEqual(OptimizeRecipeMapper.resolveFilterType("not_a_filter"), .none, "未知名回 NONE（对齐 Android 兜底）")
    }

    func testResolveStyleFilterAliases() {
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("NONE"), .none)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("CARTOON"), .toon)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("卡通"), .toon)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("素描"), .sketch)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("POSTER"), .posterize)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("浮雕"), .emboss)
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("CROSS HATCH"), .crosshatch, "空格归一化")
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("cross-hatch"), .crosshatch, "连字符归一化")
        XCTAssertEqual(OptimizeRecipeMapper.resolveStyleFilter("unknown"), .none)
    }

    // MARK: - presets JSON 解码（Android assets 原样数据内联）

    func testPresetDecodingFromAndroidAssetsJson() throws {
        // Android optimize_presets.json 的 SELFIE 条目（含 Kotlin data class 外多余键 "eyebrow"，须忽略）
        let json = """
        [
          {
            "scene": "SELFIE",
            "beauty": {
              "enabled": true, "smoothing": 35, "whitening": 25,
              "slimFace": 10, "bigEyes": 15, "lipColor": 25, "blush": 10, "eyebrow": 10
            },
            "filter": { "colorFilter": "NONE", "styleFilter": "NONE" },
            "adjustment": {
              "brightness": 5, "exposure": 0, "contrast": 52,
              "saturation": 102, "temperature": 5200, "tint": 2
            }
          },
          {
            "scene": "low_light",
            "beauty": { "enabled": true, "smoothing": 15, "whitening": 10 },
            "filter": { "colorFilter": "WARM" },
            "adjustment": { "brightness": 12, "contrast": 55 }
          }
        ]
        """
        let data = try XCTUnwrap(json.data(using: .utf8))
        let list = try JSONDecoder().decode([OptimizePreset].self, from: data)
        XCTAssertEqual(list.count, 2)

        let selfie = try XCTUnwrap(list.first { preset in preset.scene == "SELFIE" })
        XCTAssertEqual(selfie.beauty.smoothing, 35)
        XCTAssertEqual(selfie.beauty.blush, 10)
        XCTAssertEqual(selfie.adjustment.temperature, 5200)
        XCTAssertEqual(selfie.filter.colorFilter, "NONE")

        // 小写 scene 名可解析（caseInsensitive 对齐 Android ignoreCase = true）+ 缺字段取默认值
        let lowLight = try XCTUnwrap(list.first { preset in preset.scene == "low_light" })
        XCTAssertEqual(lowLight.adjustment.brightness, 12)
        XCTAssertEqual(lowLight.adjustment.saturation, 100, "缺省字段取默认（Moshi 默认值语义）")
        XCTAssertEqual(lowLight.adjustment.temperature, 5000)
        XCTAssertEqual(lowLight.filter.styleFilter, "NONE", "缺省字段取默认")
    }
}

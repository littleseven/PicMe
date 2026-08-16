import XCTest
@testable import PoLang

/// 证照域纯逻辑测试（对标 specs/screens/idphoto.yaml §6/§7/§9/§10 契约；
/// 语义对齐 Android androidApp/src/test .../domain/matting/ 测试组，fixture 独立构造）
final class IdPhotoDomainTests: XCTestCase {

    // MARK: - IDPhotoSpecs（国标）

    func testSpecs_oneInchNationalStandard() {
        let all = IDPhotoSizeSpec.allCases
        XCTAssertEqual(all.count, 4)
        XCTAssertEqual(all[0].pixelW, 295)
        XCTAssertEqual(all[0].pixelH, 413)   // 1寸 25×35mm @300dpi
        // 全部竖版（h > w）
        for spec in all {
            XCTAssertGreaterThan(spec.pixelH, spec.pixelW, "\(spec) 应为竖版")
        }
    }

    func testSpecs_colorsBlueRedWhite() {
        XCTAssertEqual(IDPhotoColorSpec.allCases.count, 3)
        XCTAssertEqual(IDPhotoColorSpec.allCases[0].rgb.r, 0x43)
        XCTAssertEqual(IDPhotoColorSpec.allCases[0].rgb.g, 0x8E)
        XCTAssertEqual(IDPhotoColorSpec.allCases[0].rgb.b, 0xDB)
        XCTAssertEqual(IDPhotoColorSpec.allCases[1].rgb.r, 0xD9)
        XCTAssertEqual(IDPhotoColorSpec.allCases[2].rgb.b, 0xFF)
    }

    // MARK: - coverCropRect

    func testCoverCrop_squareToPortrait_cropsVerticalCentered() {
        let crop = IDPhotoComposer.coverCropRect(srcW: 100, srcH: 100, dstW: 50, dstH: 100)
        XCTAssertEqual(crop, CropRect(left: 25, top: 0, width: 50, height: 100))
    }

    func testCoverCrop_wideToSquare_cropsHorizontalCentered() {
        let crop = IDPhotoComposer.coverCropRect(srcW: 200, srcH: 100, dstW: 100, dstH: 100)
        XCTAssertEqual(crop, CropRect(left: 50, top: 0, width: 100, height: 100))
    }

    func testCoverCrop_sameAspect_fullSource() {
        // 比例精确一致（0.75）→ 全源
        let crop = IDPhotoComposer.coverCropRect(srcW: 300, srcH: 400, dstW: 150, dstH: 200)
        XCTAssertEqual(crop, CropRect(left: 0, top: 0, width: 300, height: 400))
    }

    // MARK: - subjectBounds

    func testSubjectBounds_empty_returnsNil() {
        let alpha = [Float](repeating: 0.2, count: 8 * 6)
        XCTAssertNil(IDPhotoComposer.subjectBounds(alpha, w: 8, h: 6))
    }

    func testSubjectBounds_topAndCenterX() {
        // 8×6：前景 y=2..3、x=2..6 → top=2, centerX=(2+3+4+5+6)/5=4
        var alpha = [Float](repeating: 0, count: 8 * 6)
        for y in 2...3 {
            for x in 2...6 { alpha[y * 8 + x] = 0.9 }
        }
        let bounds = IDPhotoComposer.subjectBounds(alpha, w: 8, h: 6)
        XCTAssertEqual(bounds?.top, 2)
        XCTAssertEqual(bounds?.centerX ?? -1, 4, accuracy: 0.01)
    }

    // MARK: - subjectAwareCropRect（砍头修复回归）

    func testSubjectAwareCrop_keepsHeadWithHeadroom_insteadOfCenterChop() {
        // 1000×2000 → 1000×1400：居中裁切 top=300 会砍掉 y<300 的头顶；
        // 主体感知（subject.top=100）→ top=0（autoTop=100−112 clamp 到 0）
        let crop = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 100, centerX: 500),
            offsetX: 0, offsetY: 0, zoom: 1,
            srcW: 1000, srcH: 2000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(crop.top, 0)
        XCTAssertEqual(crop.height, 1400)
        XCTAssertLessThan(crop.top, 300, "不得回退居中裁切砍头")
    }

    func testSubjectAwareCrop_headroomAt8Percent_whenSpaceAllows() {
        // cropH=1400 → headroom=112；subject.top=500 → top=388
        let crop = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 500, centerX: 500),
            offsetX: 0, offsetY: 0, zoom: 1,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(crop.top, 388)
    }

    func testSubjectAwareCrop_noSubject_fallsBackCentered() {
        let crop = IDPhotoComposer.subjectAwareCropRect(
            subject: nil, offsetX: 0, offsetY: 0, zoom: 1,
            srcW: 1000, srcH: 2000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(crop.top, 300)
    }

    func testSubjectAwareCrop_horizontalSubjectCentering() {
        // 方源→竖版目的：cropW = 2000×1000/1400 = 1428；主体偏右 centerX=1200
        // → left = 1200 − 714 = 486（区间 [0, 572] 内）
        let crop = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 500, centerX: 1200),
            offsetX: 0, offsetY: 0, zoom: 1,
            srcW: 2000, srcH: 2000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(crop.width, 1428)
        XCTAssertEqual(crop.left, 486)
    }

    func testSubjectAwareCrop_userOffsetAppliedAndClamped() {
        // offsetY=+0.5 → top = 388 + 700 = 1088 < srcH−cropH=1600（未越界）
        let inRange = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 500, centerX: 500),
            offsetX: 0, offsetY: 0.5, zoom: 1,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(inRange.top, 1088)
        // offsetY 极大 → clamp 到 (srcH−cropH)
        let clamped = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 500, centerX: 500),
            offsetX: 0, offsetY: 10, zoom: 1,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(clamped.top, 1600)
        XCTAssertEqual(clamped.left, 0)
    }

    func testSubjectAwareCrop_zoomShrinksWindow_keepsHeadroom() {
        let z1 = IDPhotoComposer.subjectAwareCropRect(
            subject: (top: 500, centerX: 500),
            offsetX: 0, offsetY: 0, zoom: 2,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(z1.width, 500)
        XCTAssertEqual(z1.height, 700)
        // autoTop = 500 − 700×0.08 = 444
        XCTAssertEqual(z1.top, 444)
        XCTAssertEqual(z1.left, 250)
    }

    func testSubjectAwareCrop_zoomBelowOne_coercedToCover() {
        let crop = IDPhotoComposer.subjectAwareCropRect(
            subject: nil, offsetX: 0, offsetY: 0, zoom: 0.1,
            srcW: 1000, srcH: 2000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(crop.height, 1400, "zoom<1 不得放大取景窗")
        XCTAssertEqual(crop.width, 1000)
    }

    // MARK: - clampFraming（过拖死区防护）

    func testClampFraming_overDragClamped() {
        // subject.top=500, srcH=3000, cropH=1400, autoTop=388: minY=−388/1400
        let out = IDPhotoComposer.clampFraming(
            subject: (top: 500, centerX: 500),
            offsetX: 0, offsetY: -10, zoom: 1,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(out.offsetY, -388.0 / 1400.0, accuracy: 0.001)
        XCTAssertEqual(out.zoom, 1)
    }

    func testClampFraming_inRangeUnchanged() {
        let out = IDPhotoComposer.clampFraming(
            subject: (top: 500, centerX: 500),
            offsetX: 0.1, offsetY: 0.1, zoom: 2,
            srcW: 1000, srcH: 3000, dstW: 1000, dstH: 1400)
        XCTAssertEqual(out.offsetX, 0.1, accuracy: 0.0001)
        XCTAssertEqual(out.offsetY, 0.1, accuracy: 0.0001)
        XCTAssertEqual(out.zoom, 2)
    }

    // MARK: - 坐标桥接

    func testFrameToSource_mapping() {
        let crop = CropRect(left: 100, top: 200, width: 50, height: 100)
        let p = IDPhotoComposer.frameToSource(px: 50, py: 50, frameW: 100, frameH: 100, crop: crop)
        XCTAssertEqual(p.x, 125, accuracy: 0.01)
        XCTAssertEqual(p.y, 250, accuracy: 0.01)
    }

    func testFrameRadiusToSource_widthAxisOnly() {
        let crop = CropRect(left: 0, top: 0, width: 500, height: 1000)
        // frameW=100 → 半径 10 → 源 50（仅宽度轴；高度 1000 不参与）
        XCTAssertEqual(IDPhotoComposer.frameRadiusToSource(radiusPx: 10, frameW: 100, crop: crop), 50)
    }

    // MARK: - MaskPostProcessor

    func testBinarize_threshold() {
        let out = MaskPostProcessor.binarize([0.2, 0.5, 0.7])
        XCTAssertEqual(out, [0, 1, 1])
    }

    func testUpsample_bilinearAndEdgeClamp() {
        // 2×2 → 4×4：角点保持原值；(1,1) 半像素中心采样 src=(0.25,0.25)
        // → (v00·0.75+v10·0.25)·0.75 + (v01·0.75+v11·0.25)·0.25 = 0.25·0.75 + 1·0.25 = 0.4375
        let src: [Float] = [0, 1, 1, 1]
        let out = MaskPostProcessor.upsample(src, srcW: 2, srcH: 2, dstW: 4, dstH: 4)
        XCTAssertEqual(out[0], 0, accuracy: 0.01)                 // 左上角
        XCTAssertEqual(out[15], 1, accuracy: 0.01)                // 右下角
        XCTAssertEqual(out[5], 0.4375, accuracy: 0.01)            // 双线性混合
        XCTAssertEqual(out.count, 16)
    }

    func testFeather_radiusZeroCopy_and_smoothing() {
        let mask = [Float](repeating: 1, count: 16)
        let copy = MaskPostProcessor.feather(mask, w: 4, h: 4, radius: 0)
        XCTAssertEqual(copy, mask)
        // 半列 0/半列 1 → 羽化后边界行出现 0~1 过渡
        var edge: [Float] = []
        for y in 0..<4 { for x in 0..<4 { edge.append(x < 2 ? 0 : 1) } }
        let soft = MaskPostProcessor.feather(edge, w: 4, h: 4, radius: 1)
        let row = Array(soft[0..<4])
        XCTAssertLessThan(row[1], 1)
        XCTAssertGreaterThan(row[1], 0)
    }

    func testSharpen_narrowsSoftEdge_identityAt1() {
        let out = MaskPostProcessor.sharpenAlpha([0, 0.6, 1], contrast: 2.5)
        XCTAssertEqual(out[0], 0)
        XCTAssertEqual(out[2], 1)
        XCTAssertGreaterThan(out[1], 0.6)   // >0.5 的被推高
        let identity = MaskPostProcessor.sharpenAlpha([0.1, 0.4, 0.9], contrast: 1)
        XCTAssertEqual(identity, [0.1, 0.4, 0.9])
    }

    func testErode_shrinksStripe_dilateGrows() {
        // 8×1 中间 4 格前景
        var m = [Float](repeating: 0, count: 8)
        for i in 2...5 { m[i] = 1 }
        let eroded = MaskPostProcessor.erode(m, w: 8, h: 1, radius: 1)
        XCTAssertEqual(Array(eroded[0..<8]), [0, 0, 0, 1, 1, 0, 0, 0])
        let dilated = MaskPostProcessor.dilate(m, w: 8, h: 1, radius: 1)
        // x=6 窗口 [5..7]=max(1,0,0)=1 → 扩到 7 格
        XCTAssertEqual(Array(dilated[0..<8]), [0, 1, 1, 1, 1, 1, 1, 0])
    }

    func testAdjustEdges_default_sharpenOnly() {
        let mask: [Float] = [0, 0.6, 1, 0.3]
        let adjusted = MaskPostProcessor.adjustEdges(mask, w: 4, h: 1, params: .defaultValue)
        let sharpened = MaskPostProcessor.sharpenAlpha(mask, contrast: 2.5)
        XCTAssertEqual(adjusted, sharpened, "默认参数=仅锐化（2.5/0/0）")
    }

    // MARK: - StrokeLayer

    func testStrokeRestore_hard_setsAlpha1() {
        let base = [Float](repeating: 0, count: 10 * 10)
        let stroke = BrushStroke(mode: .restore, radiusPx: 2, softness: 0,
                                 points: [StrokePoint(x: 5, y: 5), StrokePoint(x: 5, y: 5)])
        let out = StrokeLayer.replay(strokes: [stroke], base: base, w: 10, h: 10)
        XCTAssertEqual(out[5 * 10 + 5], 1)
        XCTAssertEqual(out[0], 0)
    }

    func testStrokeErase_hard_setsAlpha0() {
        let base = [Float](repeating: 1, count: 10 * 10)
        let stroke = BrushStroke(mode: .erase, radiusPx: 2, softness: 0,
                                 points: [StrokePoint(x: 5, y: 5), StrokePoint(x: 5, y: 5)])
        let out = StrokeLayer.replay(strokes: [stroke], base: base, w: 10, h: 10)
        XCTAssertEqual(out[5 * 10 + 5], 0)
        XCTAssertEqual(out[0], 1)
    }

    func testStroke_orderRestoreThenErase_eraseWins() {
        let base = [Float](repeating: 0, count: 10 * 10)
        let restore = BrushStroke(mode: .restore, radiusPx: 2, softness: 0,
                                  points: [StrokePoint(x: 5, y: 5), StrokePoint(x: 5, y: 5)])
        let erase = BrushStroke(mode: .erase, radiusPx: 2, softness: 0,
                                points: [StrokePoint(x: 5, y: 5), StrokePoint(x: 5, y: 5)])
        let out = StrokeLayer.replay(strokes: [restore, erase], base: base, w: 10, h: 10)
        XCTAssertEqual(out[5 * 10 + 5], 0, "后描边覆盖先描边")
    }

    func testStroke_inputNotMutated_emptyCopy() {
        let base = [Float](repeating: 0.5, count: 4)
        let out = StrokeLayer.replay(strokes: [], base: base, w: 2, h: 2)
        XCTAssertEqual(out, base)
        let stroke = BrushStroke(mode: .erase, radiusPx: 1, softness: 0,
                                 points: [StrokePoint(x: 1, y: 1), StrokePoint(x: 1, y: 1)])
        _ = StrokeLayer.replay(strokes: [stroke], base: base, w: 2, h: 2)
        XCTAssertEqual(base, [Float](repeating: 0.5, count: 4), "replay 不得修改输入")
    }

    func testStroke_softBrush_fadesTowardEdge() {
        let base = [Float](repeating: 0, count: 21)
        let stroke = BrushStroke(mode: .restore, radiusPx: 10, softness: 1,
                                 points: [StrokePoint(x: 10, y: 0), StrokePoint(x: 10, y: 0)])
        let out = StrokeLayer.replay(strokes: [stroke], base: base, w: 21, h: 1)
        XCTAssertEqual(out[10], 1)                        // 中心全权
        XCTAssertGreaterThan(out[10], out[5])             // 边缘权重衰减
        XCTAssertGreaterThan(out[5], 0)
    }

    // MARK: - BackgroundComposer

    func testCompose_alphaEndpointsAndBlend() {
        // 1 像素：bg 蓝，fg 红
        let pixels: [UInt8] = [255, 0, 0, 255]
        let blue = (r: 0x43 as UInt8, g: 0x8E as UInt8, b: 0xDB as UInt8)
        let full = BackgroundComposer.composeOnColor(pixels: pixels, alpha: [1], bgColor: blue)
        XCTAssertEqual(Array(full[0..<4]), [255, 0, 0, 255])
        let empty = BackgroundComposer.composeOnColor(pixels: pixels, alpha: [0], bgColor: blue)
        XCTAssertEqual(Array(empty[0..<4]), [0x43, 0x8E, 0xDB, 255])
        let half = BackgroundComposer.composeOnColor(pixels: pixels, alpha: [0.5], bgColor: blue)
        XCTAssertEqual(half[0], 161)   // round(67×0.5 + 255×0.5) = 161
        XCTAssertEqual(half[1], 71)    // round(142×0.5)
        XCTAssertEqual(half[2], 110)   // round(219×0.5) = 109.5 → 110（away from zero）
        XCTAssertEqual(half[3], 255)   // 输出不透明
    }

    // MARK: - EdgeParams 契约

    func testEdgeParams_defaultsAndBounds() {
        XCTAssertEqual(EdgeParams.defaultValue.contrast, 2.5)
        XCTAssertEqual(EdgeParams.defaultValue.shrinkExpandPx, 0)
        XCTAssertEqual(EdgeParams.defaultValue.featherRadiusPx, 0)
        XCTAssertEqual(EdgeParams.minContrast, 1.0)
        XCTAssertEqual(EdgeParams.maxContrast, 4.0)
        XCTAssertEqual(EdgeParams.maxShrinkExpandPx, 20)
        XCTAssertEqual(EdgeParams.maxFeatherPx, 20)
    }
}

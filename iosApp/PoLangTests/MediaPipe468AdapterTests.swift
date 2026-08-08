import XCTest
@testable import PoLang

/// MediaPipe468Adapter 单元测试
///
/// 测试策略（对标 Android MediaPipe468AdapterTest.kt）：
/// 1. 前置摄像头 X 镜像（核心 bug 场景）
/// 2. 后置摄像头不镜像
/// 3. 中心 X=0.5 两种方向都不变
/// 4. 前置左右翻转
/// 5. 点数不足返回 nil
final class MediaPipe468AdapterTests: XCTestCase {

    // 构造 468 个相同坐标的 fake landmarks
    private func fakeLandmarks(centerX: Float, centerY: Float) -> [MediaPipe468Adapter.Landmark] {
        (0..<468).map { _ in MediaPipe468Adapter.Landmark(x: centerX, y: centerY) }
    }

    // ================================================================
    // X 镜像逻辑测试（核心 bug 场景）
    // ================================================================

    func testFrontCameraMirrorsXCoordinate() {
        let landmarks = fakeLandmarks(centerX: 0.3, centerY: 0.4)
        let result = MediaPipe468Adapter.map(landmarks, isFrontCamera: true)
        XCTAssertNotNil(result)
        XCTAssertEqual(result!.count, 106)

        // 106 点中第 0 点（轮廓 M0）对应 MediaPipe 127
        // 原始 x=0.3，前置镜像后应为 1-0.3=0.7
        XCTAssertEqual(result![0].x, 0.7, accuracy: 0.001, "Front camera should mirror X: 0.3 -> 0.7")
        // Y 坐标不应被镜像
        XCTAssertEqual(result![0].y, 0.4, accuracy: 0.001, "Y should not be mirrored")
    }

    func testBackCameraDoesNotMirrorX() {
        let landmarks = fakeLandmarks(centerX: 0.3, centerY: 0.4)
        let result = MediaPipe468Adapter.map(landmarks, isFrontCamera: false)
        XCTAssertNotNil(result)
        XCTAssertEqual(result![0].x, 0.3, accuracy: 0.001, "Back camera should not mirror X")
        XCTAssertEqual(result![0].y, 0.4, accuracy: 0.001, "Y should remain unchanged")
    }

    func testCenterXRemainsHalfForBothLensFacing() {
        let landmarks = fakeLandmarks(centerX: 0.5, centerY: 0.5)
        let frontResult = MediaPipe468Adapter.map(landmarks, isFrontCamera: true)!
        XCTAssertEqual(frontResult[0].x, 0.5, accuracy: 0.001, "Center X should remain 0.5 for front")
        let backResult = MediaPipe468Adapter.map(landmarks, isFrontCamera: false)!
        XCTAssertEqual(backResult[0].x, 0.5, accuracy: 0.001, "Center X should remain 0.5 for back")
    }

    func testFrontCameraLeftSideBecomesRightSide() {
        let landmarks = fakeLandmarks(centerX: 0.2, centerY: 0.5)
        let result = MediaPipe468Adapter.map(landmarks, isFrontCamera: true)!
        XCTAssertEqual(result![0].x, 0.8, accuracy: 0.001, "Left side should become right side")
    }

    // ================================================================
    // 错误处理测试
    // ================================================================

    func testInsufficientLandmarksReturnsNil() {
        let landmarks = (0..<10).map { _ in MediaPipe468Adapter.Landmark(x: 0, y: 0) }
        let result = MediaPipe468Adapter.map(landmarks, isFrontCamera: true)
        XCTAssertNil(result, "Should fail with insufficient landmarks")
    }

    // ================================================================
    // 结构验证
    // ================================================================

    func testNonContourMappingHas73Entries() {
        XCTAssertEqual(MediaPipe468Adapter.nonContourMapping.count, 73, "NON_CONTOUR_MAPPING must have exactly 73 entries")
    }

    func testResultAlwaysHas106Points() {
        let landmarks = fakeLandmarks(centerX: 0.5, centerY: 0.5)
        let result = MediaPipe468Adapter.map(landmarks)!
        XCTAssertEqual(result.count, 106)
    }
}

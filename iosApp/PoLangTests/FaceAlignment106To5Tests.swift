import XCTest
@testable import PoLang

/// 守护 FaceAlignment.convert106ToLandmarks5 与 Android 双端一致。
/// 索引定义(统一 106 序):左眼=52-57+72-73, 右眼=58-63+75-76, 鼻尖=49, 左嘴角=84, 右嘴角=94。
final class FaceAlignment106To5Tests: XCTestCase {

    func testConvert106To5_matchesArcFaceOrder() {
        // 构造统一 106 序扁平数组(212 float,x,y 交错,归一化 [0,1]),默认 0.1/0.2
        var lm106 = [Float](repeating: 0, count: 212)
        for i in 0..<106 { lm106[i * 2] = 0.1; lm106[i * 2 + 1] = 0.2 }
        func set(_ idx: Int, _ x: Float, _ y: Float) { lm106[idx * 2] = x; lm106[idx * 2 + 1] = y }

        for i in [52, 53, 54, 55, 56, 57, 72, 73] { set(i, 0.30, 0.40) }  // 左眼(8 点均值)
        for i in [58, 59, 60, 61, 62, 63, 75, 76] { set(i, 0.70, 0.40) }  // 右眼(8 点均值)
        set(49, 0.50, 0.60)  // 鼻尖
        set(84, 0.35, 0.80)  // 左嘴角
        set(94, 0.65, 0.80)  // 右嘴角

        let lm5 = FaceAlignment.convert106ToLandmarks5(landmarks106: lm106, width: 100, height: 200)

        // [左眼x,y, 右眼x,y, 鼻尖x,y, 左嘴x,y, 右嘴x,y](像素坐标 = 归一化×width/height)
        let expected: [Float] = [30, 80, 70, 80, 50, 120, 35, 160, 65, 160]
        XCTAssertEqual(lm5.count, 10)
        for i in 0..<10 {
            XCTAssertEqual(lm5[i], expected[i], accuracy: 0.001, "index \(i): got \(lm5[i]), want \(expected[i])")
        }
    }
}

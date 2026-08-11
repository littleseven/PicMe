import XCTest
@testable import PoLang

final class ScanEtaEstimatorTests: XCTestCase {
    func testColdStartDefault() {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [])
        XCTAssertEqual(eta.estimateMillis(remaining: 10), 8_000)
    }
    func testMedianOfWindow() {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [100, 200, 300, 400, 10_000])
        XCTAssertEqual(eta.perItemMillis(), 300)
        XCTAssertEqual(eta.estimateMillis(remaining: 4), 1_200)
    }
    func testAnomalyFiltering() {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [200, 300, 1_900_000])
        XCTAssertEqual(eta.perItemMillis(), 250)
    }
    func testWindowCapsAt20() {
        let many = Array(repeating: 500, count: 30) + [9_000]
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: many)
        XCTAssertEqual(eta.perItemMillis(), 500)
    }
    func testEvenCountMedianIsAverageOfTwoMiddle() {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: [100, 200, 300, 400])
        XCTAssertEqual(eta.perItemMillis(), 250)
    }
}

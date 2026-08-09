import XCTest
@testable import PoLang

final class GalleryPermissionStoreTests: XCTestCase {
    func testMapAllBranches() {
        typealias S = GalleryAccessState
        XCTAssertEqual(S.map(status: .authorized, level: .readWrite), .full)
        XCTAssertEqual(S.map(status: .limited, level: .readWrite), .limited)
        XCTAssertEqual(S.map(status: .authorized, level: .addOnly), .addOnly)
        XCTAssertEqual(S.map(status: .notDetermined, level: .readWrite), .notDetermined)
        XCTAssertEqual(S.map(status: .denied, level: .readWrite), .denied)
        XCTAssertEqual(S.map(status: .restricted, level: .addOnly), .denied)
        XCTAssertEqual(S.map(status: .limited, level: .addOnly), .denied)
    }

    /// 🟡-4 修复后的 AddOnly 复合检测打表
    func testAddOnlyFallback() {
        typealias S = GalleryAccessState
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .denied, addOnlyAuthorized: true), .addOnly)
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .notDetermined, addOnlyAuthorized: true), .addOnly)
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .restricted, addOnlyAuthorized: true), .addOnly)
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .denied, addOnlyAuthorized: false), .denied)
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .authorized, addOnlyAuthorized: true), .full,
                       "readWrite 已授权时不受 addOnly 影响")
        XCTAssertEqual(S.mapWithAddOnlyFallback(rwStatus: .limited, addOnlyAuthorized: false), .limited)
    }
}

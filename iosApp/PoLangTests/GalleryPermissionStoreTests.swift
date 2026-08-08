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
    }
}

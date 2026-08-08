import XCTest
@testable import PoLang
import SharedKit

final class KotlinBridgeTests: XCTestCase {
    func testSmokeIdsAreIncreasing() {
        let (a, b) = KotlinBridge.smokeIds()
        XCTAssertGreaterThan(b, a, "AgentIdGenerator 连续调用应递增")
    }

    /// 冒烟补：AccessState 四态 data object 导出与相等性（相册权限状态机依赖）
    func testAccessStateSingletons() {
        XCTAssertTrue(AccessStateFull.shared.isEqual(AccessStateFull.shared))
        XCTAssertFalse(AccessStateFull.shared.isEqual(AccessStateLimited.shared))
        XCTAssertNotNil(AccessStateDenied.shared)
        XCTAssertNotNil(AccessStateAddOnly.shared)
    }
}

import XCTest
@testable import PoLang

final class ScanTaskPlannerTests: XCTestCase {
    func testIncrementalSkipsCovered() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2, 3, 4],
            pass1CoveredMediaIds: Set([2, 4]),
            mode: .incremental
        )
        XCTAssertEqual(plan, [1, 3])
    }
    func testFullScansAll() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2, 3],
            pass1CoveredMediaIds: Set([1, 2]),
            mode: .full
        )
        XCTAssertEqual(plan, [1, 2, 3])
    }
    func testIncrementalEmptyWhenAllCovered() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [1, 2],
            pass1CoveredMediaIds: Set([1, 2]),
            mode: .incremental
        )
        XCTAssertTrue(plan.isEmpty)
    }
    func testPreservesInputOrder() {
        let plan = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: [5, 3, 1, 4],
            pass1CoveredMediaIds: Set([3]),
            mode: .incremental
        )
        XCTAssertEqual(plan, [5, 1, 4])
    }
}

import XCTest
@testable import PoLang

final class ScanSessionStateTests: XCTestCase {
    func testIdleCanStart() {
        XCTAssertEqual(ScanSessionState.idle.transition(.start), .running)
    }
    func testRunningCanPauseCancelComplete() {
        XCTAssertEqual(ScanSessionState.running.transition(.pause), .pausing)
        XCTAssertEqual(ScanSessionState.running.transition(.cancel), .cancelling)
        XCTAssertEqual(ScanSessionState.running.transition(.complete), .completed)
    }
    func testPausingReachesPaused() {
        XCTAssertEqual(ScanSessionState.pausing.transition(.pauseAcknowledged), .paused)
    }
    func testPausedResumesToRunning() {
        XCTAssertEqual(ScanSessionState.paused.transition(.resume), .running)
    }
    func testCancellingReachesCancelled() {
        XCTAssertEqual(ScanSessionState.cancelling.transition(.cancelAcknowledged), .cancelled)
    }
    func testTerminalStatesAreSticky() {
        for ev in ScanSessionEvent.allCases {
            XCTAssertNil(ScanSessionState.cancelled.transition(ev),
                         "cancelled 不应响应 \(ev)")
            XCTAssertNil(ScanSessionState.completed.transition(ev),
                         "completed 不应响应 \(ev)")
        }
    }
    func testIllegalTransitionsReturnNil() {
        XCTAssertNil(ScanSessionState.idle.transition(.pause))
        XCTAssertNil(ScanSessionState.paused.transition(.pause))
    }
    func testIsTerminal() {
        XCTAssertTrue(ScanSessionState.completed.isTerminal)
        XCTAssertTrue(ScanSessionState.cancelled.isTerminal)
        XCTAssertFalse(ScanSessionState.running.isTerminal)
    }
    func testLocalizedStateKey() {
        XCTAssertEqual(ScanSessionState.idle.localizationKey, "scan_state_idle")
        XCTAssertEqual(ScanSessionState.running.localizationKey, "scan_state_running")
        XCTAssertEqual(ScanSessionState.pausing.localizationKey, "scan_state_pausing")
        XCTAssertEqual(ScanSessionState.paused.localizationKey, "scan_state_paused")
        XCTAssertEqual(ScanSessionState.cancelling.localizationKey, "scan_state_cancelling")
        XCTAssertEqual(ScanSessionState.completed.localizationKey, "scan_state_completed")
        XCTAssertEqual(ScanSessionState.cancelled.localizationKey, "scan_state_cancelled")
    }
}

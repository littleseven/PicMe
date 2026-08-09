import XCTest
@testable import PoLang
import SharedKit

/// 桩 bridge：固定两条相邻日期的数据（均取 UTC 正午，任意时区下分组边界稳定）。
private final class StubBridge: NSObject, IosMediaRepositoryBridge {
    func currentAccessState() -> AccessState { AccessStateFull.shared }
    func fetchAllMedia() -> [IosMediaItem] {
        [
            IosMediaItem(localIdentifier: "L-1", mediaType: "PHOTO",
                         captureDateMs: 1_786_046_400_000, durationMs: nil,
                         fileName: "IMG_0001.jpg"),  // 2026-08-07 12:00 UTC
            IosMediaItem(localIdentifier: "L-2", mediaType: "PHOTO",
                         captureDateMs: 1_785_960_000_000, durationMs: nil,
                         fileName: "IMG_0002.jpg")   // 2026-08-06 12:00 UTC
        ]
    }
    func requestReadWriteAuthorization() {}
    func addChangeListener(listener: @escaping () -> Void) {}
    func removeChangeListener() {}
    func deleteMedia(localIdentifiers: [String]) -> Bool { true }
}

@MainActor
final class GalleryViewModelTests: XCTestCase {
    func testGroupingSortsDescAndGroupsByDay() async throws {
        let vm = GalleryViewModel(repository: IosMediaRepository(bridge: StubBridge()))
        vm.start()
        defer { vm.stop() }
        // 轮询等 Flow 首帧（🟡-7：固定 sleep 在慢 CI 抖动），上限 5s
        for _ in 0..<50 where vm.groups.isEmpty {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertEqual(vm.groups.count, 2)
        XCTAssertGreaterThan(vm.groups[0].id, vm.groups[1].id, "分组按日期降序")
        XCTAssertEqual(vm.groups[0].items.count + vm.groups[1].items.count, 2)
        XCTAssertFalse(vm.isLoading)
    }
}

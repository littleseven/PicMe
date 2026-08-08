import Foundation
import Photos
import PhotosUI
import Combine
import UIKit

/// 相册权限 UI 态（spec §4.2 四态 + notDetermined；映射 shared AccessState，UI 层用本 Swift 枚举）。
/// Limited 是一等公民：仅显已选 + 常驻「管理可访问照片」，非降级提示。
enum GalleryAccessState: Equatable {
    case full, limited, addOnly, denied, notDetermined

    /// 纯函数，单测直接打表（PHAuthorizationStatus 不可构造，用自定义输入枚举解耦）。
    static func map(status: AuthStatusInput, level: AuthLevelInput) -> GalleryAccessState {
        switch (status, level) {
        case (.authorized, .readWrite): return .full
        case (.limited, .readWrite): return .limited
        case (.authorized, .addOnly): return .addOnly
        case (.notDetermined, _): return .notDetermined
        // (.limited, .addOnly) 组合系统不会产生，按 denied 兜底
        case (.denied, _), (.restricted, _), (.limited, .addOnly): return .denied
        }
    }
}

enum AuthStatusInput { case authorized, limited, denied, restricted, notDetermined }
enum AuthLevelInput { case readWrite, addOnly }

@MainActor
final class GalleryPermissionStore: ObservableObject {
    @Published private(set) var state: GalleryAccessState = .notDetermined

    func refresh() {
        let raw = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let status: AuthStatusInput
        switch raw {
        case .authorized: status = .authorized
        case .limited: status = .limited
        case .denied: status = .denied
        case .restricted: status = .restricted
        default: status = .notDetermined
        }
        state = GalleryAccessState.map(status: status, level: .readWrite)
        DebugOverlayState.shared.set("gallery.permission", "\(state)")
    }

    func requestAccess() async {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        refresh()
    }

    func presentLimitedLibraryPicker(from vc: UIViewController) {
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: vc)
    }
}

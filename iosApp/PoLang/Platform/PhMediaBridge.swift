import Foundation
import Photos
import SharedKit

/// IosMediaRepositoryBridge 的 Photos framework 实现。
///
/// SharedBridge 铁律（kmp-ios-interop skill）：本类所有方法绝不抛异常跨边界——
/// 失败一律用 false / 空集合表达（Kotlin 异常逃逸到 Swift 会 signal 6 崩溃）。
@objc final class PhMediaBridge: NSObject, IosMediaRepositoryBridge {
    private var changeListener: (() -> Void)?

    override init() {
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    deinit {
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
    }

    func currentAccessState() -> AccessState {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return AccessStateFull.shared
        case .limited: return AccessStateLimited.shared
        case .denied, .restricted, .notDetermined:
            // AddOnly 一等检测（🟡-4 修复，此前四态退化三态）：
            // readWrite 未授权但 addOnly 已授权 → AddOnly
            if PHPhotoLibrary.authorizationStatus(for: .addOnly) == .authorized {
                return AccessStateAddOnly.shared
            }
            return AccessStateDenied.shared
        @unknown default: return AccessStateDenied.shared
        }
    }

    /// creationDate 降序，与 Android MediaStore 排序对齐（S5 双端一致）。
    /// 谓词过滤 image/video——audio 资产不得误入 PHOTO（🟡-5 修复）。
    func fetchAllMedia() -> [IosMediaItem] {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.predicate = NSPredicate(
            format: "mediaType == %d OR mediaType == %d",
            PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        let result = PHAsset.fetchAssets(with: opts)
        var items: [IosMediaItem] = []
        items.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            // fileName 取 PHAssetResource 原始文件名，对齐 Android DISPLAY_NAME（S5）
            let fileName = PHAssetResource.assetResources(for: asset).first?.originalFilename
                ?? asset.localIdentifier
            items.append(IosMediaItem(
                localIdentifier: asset.localIdentifier,
                mediaType: asset.mediaType == .video ? "VIDEO" : "PHOTO",
                captureDateMs: Int64((asset.creationDate?.timeIntervalSince1970 ?? 0) * 1000),
                durationMs: asset.mediaType == .video
                    ? KotlinLong(longLong: Int64(asset.duration * 1000))
                    : nil,
                fileName: fileName
            ))
        }
        return items
    }

    func requestReadWriteAuthorization() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] _ in
            DispatchQueue.main.async { self?.changeListener?() }
        }
    }

    func addChangeListener(listener: @escaping () -> Void) {
        self.changeListener = listener
    }

    func removeChangeListener() {
        self.changeListener = nil
    }

    /// iOS 删除走 PHAssetChangeRequest（系统弹确认窗），免 Android 11+ IntentSender 授权队列。
    func deleteMedia(localIdentifiers: [String]) -> Bool {
        guard !localIdentifiers.isEmpty else { return false }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: localIdentifiers, options: nil)
        guard assets.count > 0 else { return false }
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(assets)
        }, completionHandler: { _, _ in })
        return true
    }

    /// 收藏/取消收藏（PHAssetChangeRequest 改 isFavorite，无系统确认窗）。
    func setFavorite(localIdentifier: String, favorite: Bool) -> Bool {
        guard !localIdentifier.isEmpty else { return false }
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard assets.count > 0 else { return false }
        PHPhotoLibrary.shared().performChanges({
            assets.enumerateObjects { asset, _, _ in
                let req = PHAssetChangeRequest(for: asset)
                req.isFavorite = favorite
            }
        }, completionHandler: { _, _ in })
        return true
    }
}

extension PhMediaBridge: PHPhotoLibraryChangeObserver {
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        DispatchQueue.main.async { [weak self] in self?.changeListener?() }
    }
}

import Foundation
import Photos
import UIKit

/// PHCachingImageManager 缩略图加载；键 = PHAsset.localIdentifier。
/// 缩略图是纯平台行为，presentation 层自治（spec S1），不经 shared。
/// [PRIVACY] isNetworkAccessAllowed = false：iCloud 未下载素材不触发网络拉取，媒体 100% 端侧。
@MainActor
final class ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let manager = PHCachingImageManager()

    /// highQuality = true 走 .highQualityFormat（单次高清回调，大图浏览用）——
    /// 否则大图复用 opportunistic 首帧会永久模糊（🟡-8）。
    func thumbnail(for localIdentifier: String, size: CGSize,
                   highQuality: Bool = false) async -> UIImage? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = result.firstObject else { return nil }
        return await withCheckedContinuation { cont in
            let opts = PHImageRequestOptions()
            opts.deliveryMode = highQuality ? .highQualityFormat : .opportunistic
            opts.isNetworkAccessAllowed = false
            var resumed = false
            manager.requestImage(for: asset, targetSize: size, contentMode: .aspectFill,
                                 options: opts) { image, _ in
                // opportunistic 可能回调两次（先低清后高清），只取第一次非 nil；
                // highQualityFormat 单次回调，天然满足
                guard !resumed, let image else { return }
                resumed = true
                cont.resume(returning: image)
            }
        }
    }

    /// 预热窗口（Task 11 性能调优点）：滚动时对可见区 ±2 屏 identifiers 调本方法。
    func startCaching(identifiers: [String], size: CGSize) {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: identifiers, options: nil)
        manager.startCachingImages(for: assets.objects(at: IndexSet(integersIn: 0..<assets.count)),
                                   targetSize: size, contentMode: .aspectFill, options: nil)
    }
}

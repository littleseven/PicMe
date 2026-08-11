import UIKit
import Photos

/// 扫描循环用的非主线程图片加载。
///
/// `ThumbnailLoader` 是 `@MainActor`，后台批扫每张图 hop 主线程不可接受；
/// 本加载器在调用线程同步请求图片（须在后台 Task 调用）。
///
/// 请求长边 ~1024，覆盖 Pass1 检测(640) + 嵌入对齐裁剪 + MobileCLIP 编码所需，不拉全分辨率。
enum ScanImageLoader {
    private static let maxPixel = 1024

    /// 根据 localIdentifier 加载图片（阻塞当前线程，须在后台 Task 调）。
    static func load(localIdentifier: String) -> UIImage? {
        let assets = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil)
        guard let asset = assets.firstObject else { return nil }
        let opts = PHImageRequestOptions()
        opts.isNetworkAccessAllowed = false
        opts.deliveryMode = .highQualityFormat
        opts.isSynchronous = true
        var result: UIImage?
        let side = maxPixel
        _ = PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: side, height: side),
            contentMode: .aspectFit,
            options: opts
        ) { img, _ in result = img }
        return result
    }
}

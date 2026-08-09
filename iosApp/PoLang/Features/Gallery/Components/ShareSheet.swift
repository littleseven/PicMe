import SwiftUI
import UIKit

/// 分享 payload（Identifiable 以驱动 sheet(item:)）
struct SharePayload: Identifiable {
    let id = UUID()
    let images: [UIImage]
}

/// UIActivityViewController 桥（系统分享面板）
struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

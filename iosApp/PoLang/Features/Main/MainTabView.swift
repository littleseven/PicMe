import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            GalleryPlaceholderView()
                .tabItem { Label(String(localized: "Gallery"), systemImage: "photo.on.rectangle") }
            CameraPreviewView()
                .tabItem { Label(String(localized: "Camera"), systemImage: "camera") }
        }
        .accessibilityIdentifier("mainTabView")
        .overlay(alignment: .topLeading) { DebugOverlayView() }
    }
}

#Preview {
    MainTabView()
}

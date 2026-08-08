import SwiftUI

struct MainTabView: View {
    @EnvironmentObject private var container: AppContainer

    var body: some View {
        TabView {
            GalleryGridView(repository: container.mediaRepository)
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
        .environmentObject(AppContainer.shared)
}

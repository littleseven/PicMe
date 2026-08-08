import SwiftUI
import MetalKit

struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var fpsTimer: Timer?

    var body: some View {
        ZStack {
            if authorized {
                MetalViewRepresentable(controller: controller, params: container.beautyParams)
            } else {
                VStack(spacing: 12) {
                    Text("需要相机权限")
                    Button("去设置开启") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .accessibilityIdentifier("camera_denied")
            }
        }
        .task {
            authorized = await controller.checkAuthorizationAndStart()
            DebugOverlayState.shared.set("camera.auth", authorized ? "granted" : "denied")
        }
        .onDisappear { controller.stop() }
        .accessibilityIdentifier("camera_preview")
    }
}

private struct MetalViewRepresentable: UIViewRepresentable {
    let controller: CaptureSessionController
    let params: BeautyRenderer.Params

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        view.colorPixelFormat = .bgra8Unorm
        context.coordinator.renderer = view.device.flatMap { BeautyRenderer(device: $0) }
        context.coordinator.controller = controller
        context.coordinator.renderer?.params = params
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {
        // params 变化时同步到 renderer（Slider 拖动触发）
        context.coordinator.renderer?.params = params
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: BeautyRenderer?
        var controller: CaptureSessionController?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let pb = controller?.readBuffer() else { return }
            renderer?.draw(pixelBuffer: pb, in: view)
            frames += 1
            if Date().timeIntervalSince(lastFpsTick) >= 1.0 {
                DebugOverlayState.shared.set("camera.fps", "\(frames)")
                frames = 0
                lastFpsTick = Date()
            }
        }
    }
}

import SwiftUI
import MetalKit

struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var faceService = FaceLandmarkService()
    @State private var showBeautyPanel = false
    @State private var showFilterSelector = false
    /// 🔴3: 共享 renderer 引用——makeUIView 回调赋值，ShutterButton 闭包可读
    @State private var sharedRenderer: BeautyRenderer?

    var body: some View {
        ZStack {
            if authorized {
                cameraContent
            } else {
                VStack(spacing: 12) {
                    Text(String(localized: "Camera Permission Required"))
                    Button(String(localized: "Open Settings")) {
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
            if authorized {
                photoController.attach(to: controller.session)
                // 🔴1: 接线人脸检测——帧回调投递给 FaceLandmarkService
                controller.onFrame = { [faceService] pixelBuffer, ts in
                    faceService.enqueue(pixelBuffer: pixelBuffer, timestampMs: ts)
                }
            }
        }
        .onDisappear { controller.stop() }
        .accessibilityIdentifier("camera_preview")
    }

    @ViewBuilder
    private var cameraContent: some View {
        ZStack(alignment: .bottom) {
            MetalViewRepresentable(controller: controller, params: container.beautyParams,
                                   faceService: faceService, onRendererReady: { renderer in
                sharedRenderer = renderer
            })
            .overlay {
                CameraGesturesView(controller: controller)
                    .allowsHitTesting(true)
            }

            VStack(spacing: 12) {
                if showFilterSelector {
                    FilterSelectorView(selectedFilter: $container.beautyParams.colorFilter)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                if showBeautyPanel {
                    BeautyPanelView(params: $container.beautyParams)
                        .padding(.horizontal)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                HStack {
                    Button {
                        withAnimation { showBeautyPanel.toggle() }
                    } label: {
                        Image(systemName: "face.smiling")
                            .font(.system(size: 22))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("camera_beauty_toggle")

                    Spacer()

                    // 🔴3: 快门使用 sharedRenderer（非 nil 的 BeautyRenderer 引用）
                    ShutterButton {
                        guard let renderer = sharedRenderer else { return }
                        let flow = CaptureFlow(photoController: photoController, renderer: renderer)
                        flow.captureAndSave()
                    }

                    Spacer()

                    Button {
                        withAnimation { showFilterSelector.toggle() }
                    } label: {
                        Image(systemName: "camera.filters")
                            .font(.system(size: 22))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityIdentifier("camera_filter_toggle")
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
        }
    }
}

private struct MetalViewRepresentable: UIViewRepresentable {
    let controller: CaptureSessionController
    let params: BeautyRenderer.Params
    let faceService: FaceLandmarkService
    let onRendererReady: (BeautyRenderer) -> Void

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        view.device = MTLCreateSystemDefaultDevice()
        view.delegate = context.coordinator
        view.enableSetNeedsDisplay = false
        view.isPaused = false
        view.colorPixelFormat = .bgra8Unorm
        if let device = view.device {
            let renderer = BeautyRenderer(device: device)
            context.coordinator.renderer = renderer
            if let renderer { onRendererReady(renderer) }  // 🔴3: 回传 renderer 给父 View
        }
        context.coordinator.controller = controller
        context.coordinator.faceService = faceService
        context.coordinator.renderer?.params = params
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {
        context.coordinator.renderer?.params = params
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MTKViewDelegate {
        var renderer: BeautyRenderer?
        var controller: CaptureSessionController?
        var faceService: FaceLandmarkService?
        private var frames = 0
        private var lastFpsTick = Date()

        func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

        func draw(in view: MTKView) {
            guard let pb = controller?.readBuffer() else { return }
            // 🔴1: 帧同步——从 FaceLandmarkService 取最新 106 点 → 写入 BeautyRenderer
            if let fs = faceService {
                let tsMs = Int(Date().timeIntervalSince1970 * 1000)
                if let points = fs.latestWithinWindow(currentTimestampMs: tsMs) {
                    renderer?.updateFacePoints(points, hasFace: true)
                } else {
                    renderer?.updateFacePoints([], hasFace: false)
                }
            }
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

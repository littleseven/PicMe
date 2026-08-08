import SwiftUI
import MetalKit

struct CameraPreviewView: View {
    @EnvironmentObject private var container: AppContainer
    @State private var authorized = false
    @State private var controller = CaptureSessionController()
    @State private var photoController = PhotoCaptureController()
    @State private var showBeautyPanel = false
    @State private var showFilterSelector = false

    var body: some View {
        ZStack {
            if authorized {
                cameraContent
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
            if authorized {
                photoController.attach(to: controller.session)
            }
        }
        .onDisappear { controller.stop() }
        .accessibilityIdentifier("camera_preview")
    }

    @ViewBuilder
    private var cameraContent: some View {
        ZStack(alignment: .bottom) {
            // 预览 + 手势层
            MetalViewRepresentable(controller: controller, params: container.beautyParams)
                .overlay {
                    CameraGesturesView(controller: controller)
                        .allowsHitTesting(true)
                }

            // 底部控制栏
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

                // 快门 + 工具栏
                HStack {
                    // 美颜面板开关
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

                    // 快门按钮（Task 18）
                    ShutterButton {
                        let coordinator = metalCoordinator
                        let flow = CaptureFlow(
                            photoController: photoController,
                            renderer: coordinator?.renderer
                        )
                        flow.captureAndSave()
                    }

                    Spacer()

                    // 滤镜面板开关（Task 17）
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

    // 取 MetalViewRepresentable 的 Coordinator 里的 renderer（供拍照流程用）
    @State private var metalCoordinator: MetalViewRepresentable.Coordinator?
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
        // params 变化时同步到 renderer（Slider 拖动 / Filter 切换触发）
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

import Foundation
import AVFoundation

/// 相机采集（spike main.mm startCapture 的 Swift 版）：
/// 720p、YUV bi-planar、丢弃迟到帧、串行队列、Portrait 方向。
final class CaptureSessionController: NSObject {
    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "polang.camera.capture")

    private(set) var currentPixelBuffer: CVPixelBuffer?
    private let bufferLock = NSLock()
    private(set) var frameCount: Int = 0

    var onFirstFrame: (() -> Void)?
    /// 🔴1: 帧回调——投递给 FaceLandmarkService
    var onFrame: ((CVPixelBuffer, Int) -> Void)?

    func checkAuthorizationAndStart() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            start(); return true
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted { start() }
            return granted
        default:
            return false
        }
    }

    private func start() {
        queue.async { [self] in
            session.beginConfiguration()
            session.sessionPreset = .hd1280x720
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input), session.canAddOutput(videoOutput) else {
                session.commitConfiguration()
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("camera.error", "session config failed")
                }
                return
            }
            session.addInput(input)
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: queue)
            session.addOutput(videoOutput)
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait
            }
            session.commitConfiguration()
            session.startRunning()
        }
    }

    func stop() { queue.async { [self] in session.stopRunning() } }

    fileprivate func swapBuffer(_ pb: CVPixelBuffer, timestampMs: Int) {
        bufferLock.lock()
        currentPixelBuffer = pb
        bufferLock.unlock()
        frameCount += 1
        if frameCount == 1 { DispatchQueue.main.async { self.onFirstFrame?() } }
        // 🔴1: 帧回调——投递给人脸检测
        onFrame?(pb, timestampMs)
    }

    func readBuffer() -> CVPixelBuffer? {
        bufferLock.lock(); defer { bufferLock.unlock() }
        return currentPixelBuffer
    }

    // MARK: - 手势控制（Task 19）

    func focus(at point: CGPoint) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9: lockForConfiguration 失败时不能裸写 setter
            do {
                try device.lockForConfiguration()
            } catch {
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("camera.focus", "lock failed")
                }
                return
            }
            defer { device.unlockForConfiguration() }
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = point
                device.focusMode = .autoFocus
            }
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = point
                device.exposureMode = .autoExpose
            }
            let focusStr = String(format: "%.2f, %.2f", point.x, point.y)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.focus", focusStr)
            }
        }
    }

    func setZoom(_ factor: CGFloat) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9
            guard (try? device.lockForConfiguration()) != nil else { return }
            defer { device.unlockForConfiguration() }
            device.videoZoomFactor = max(1.0, min(factor, device.activeFormat.videoMaxZoomFactor))
            let zoomStr = String(format: "%.1f", device.videoZoomFactor)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.zoom", zoomStr)
            }
        }
    }

    func setExposureBias(_ bias: Float) {
        queue.async { [self] in
            guard let device = (session.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            // 🔴9
            guard (try? device.lockForConfiguration()) != nil else { return }
            defer { device.unlockForConfiguration() }
            device.setExposureTargetBias(bias)
            let expStr = String(format: "%.2f", bias)
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.exposure", expStr)
            }
        }
    }
}

extension CaptureSessionController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let tsMs = Int(CMTimeGetSeconds(ts) * 1000)
        swapBuffer(pb, timestampMs: tsMs)
    }
}

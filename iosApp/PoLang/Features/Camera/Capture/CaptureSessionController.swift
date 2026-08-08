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
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        print("[PoLang] camera.auth status=\(status.rawValue)")
        switch status {
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
            // 🔴 翻转摄像头支持
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: currentPosition),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input), session.canAddOutput(videoOutput) else {
                session.commitConfiguration()
                DispatchQueue.main.async {
                    DebugOverlayState.shared.set("camera.error", "session config failed")
                }
                return
            }
            // 移除旧 input（翻转时）
            session.inputs.forEach { session.removeInput($0) }
            session.addInput(input)
            currentDeviceInput = input
            videoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: queue)
            if !session.outputs.contains(videoOutput) {
                session.addOutput(videoOutput)
            }
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait
                if currentPosition == .front {
                    conn.isVideoMirrored = true
                } else {
                    conn.isVideoMirrored = false
                }
            }
            session.commitConfiguration()
            session.startRunning()
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.running", "yes")
                DebugOverlayState.shared.set("camera.position", self.currentPosition == .front ? "front" : "back")
            }
        }
    }

    /// 当前摄像头方向
    private var currentPosition: AVCaptureDevice.Position = .back
    private var currentDeviceInput: AVCaptureDeviceInput?

    /// 翻转摄像头
    func flipCamera() {
        currentPosition = currentPosition == .back ? .front : .back
        faceServiceIsFrontCamera?(currentPosition == .front)
        // 重启 session
        queue.async { [self] in
            session.stopRunning()
            session.beginConfiguration()
            session.inputs.forEach { session.removeInput($0) }
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                       for: .video, position: currentPosition),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                return
            }
            session.addInput(input)
            currentDeviceInput = input
            if let conn = videoOutput.connection(with: .video) {
                conn.videoOrientation = .portrait
                conn.isVideoMirrored = currentPosition == .front
            }
            session.commitConfiguration()
            session.startRunning()
            DispatchQueue.main.async {
                DebugOverlayState.shared.set("camera.position", self.currentPosition == .front ? "front" : "back")
            }
        }
    }

    /// 前置摄像头状态回调（供 FaceLandmarkService 同步 isFrontCamera）
    var faceServiceIsFrontCamera: ((Bool) -> Void)?

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

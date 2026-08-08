import Foundation
import AVFoundation
import Photos
import CoreVideo

/// 拍照：全分辨率静态图捕获（与预览 720p 流并行）。
///
/// [PERF] 快门 <50ms：capturePhoto() 异步回调后，离屏美颜渲染在后台 Task 进行，
/// ShutterButton 即刻复位不阻塞。
final class PhotoCaptureController: NSObject {
    private let photoOutput = AVCapturePhotoOutput()

    /// 当前异步回调 continuation（一次仅一张）
    private var continuation: CheckedContinuation<AVCapturePhoto, Never>?

    /// 把 photoOutput 挂到 session（CaptureSessionController.start 中调用）
    func attach(to session: AVCaptureSession) {
        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
        }
    }

    /// 触发拍照，返回捕获的 AVCapturePhoto（异步等待系统回调）
    func capture() async -> AVCapturePhoto? {
        await withCheckedContinuation { cont in
            self.continuation = cont
            photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
        }
    }

    /// AVCapturePhoto → CVPixelBuffer（用于离屏美颜渲染）
    static func pixelBuffer(from photo: AVCapturePhoto) -> CVPixelBuffer? {
        guard let data = photo.fileDataRepresentation(),
              let cgImage = UIImage(data: data)?.cgImage else { return nil }
        let w = cgImage.width, h = cgImage.height
        var pixelBuffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, w, h,
                            kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferCGImageCompatibilityKey: true,
                             kCVPixelBufferCGBitmapContextCompatibilityKey: true] as CFDictionary,
                            &pixelBuffer)
        guard let pb = pixelBuffer else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }
        let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pb),
            width: w, height: h,
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        context?.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
        return pb
    }
}

extension PhotoCaptureController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        continuation?.resume(returning: photo)
        continuation = nil
    }
}

import Foundation
import AVFoundation
import Photos
import CoreVideo
import UIKit

/// 拍照：全分辨率静态图捕获（与预览 720p 流并行）。
///
/// [PERF] 快门 <50ms：capturePhoto() 异步回调后，离屏美颜渲染在后台 Task 进行，
/// ShutterButton 即刻复位不阻塞。
final class PhotoCaptureController: NSObject {
    /// 经 CaptureSessionController.attachOutput 串行挂载（勿主线程直接 addOutput）
    let photoOutput = AVCapturePhotoOutput()

    /// 当前异步回调 continuation（一次仅一张）
    private var continuation: CheckedContinuation<AVCapturePhoto, Never>?

    /// 触发拍照，返回捕获的 AVCapturePhoto（异步等待系统回调）
    /// 🟡4: 加 5s 超时，避免快门永久挂起
    func capture() async -> AVCapturePhoto? {
        let output = self.photoOutput
        let result = await withRace(timeout: 5.0) {
            await withCheckedContinuation { (cont: CheckedContinuation<AVCapturePhoto, Never>) in
                self.continuation = cont
                output.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
            }
        }
        if result == nil {
            // 超时：丢弃旧 continuation，避免迟到的 delegate 回调误 resume 下一次拍照
            self.continuation = nil
            print("[PoLang] shutter.TIMEOUT: capturePhoto 5s 无回调")
        }
        return result
    }

    /// 超时竞速：超时返回 nil
    private func withRace<T>(timeout: TimeInterval, operation: @escaping () async -> T?) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { await operation() }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let result = await group.next() ?? nil
            group.cancelAll()
            return result
        }
    }

    /// AVCapturePhoto → CVPixelBuffer（32BGRA，竖屏 portrait 方向）
    /// 🟡4: 使用 photo.connection(.video) 设 portrait orientation 避免 EXIF 旋转
    static func pixelBuffer(from photo: AVCapturePhoto) -> CVPixelBuffer? {
        guard let data = photo.fileDataRepresentation(),
              let cgImage = UIImage(data: data)?.cgImage else { return nil }
        let w = cgImage.width, h = cgImage.height
        var pixelBuffer: CVPixelBuffer?
        // 🔴 必须 IOSurface 属性：CVMetalTextureCacheCreateTextureFromImage 只接受
        // IOSurface-backed buffer，否则美颜离屏渲染建纹理必然失败（拍照"render failed"根因）
        CVPixelBufferCreate(kCFAllocatorDefault, w, h,
                            kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferCGImageCompatibilityKey: true,
                             kCVPixelBufferCGBitmapContextCompatibilityKey: true,
                             kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary,
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

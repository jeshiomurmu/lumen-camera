import AVFoundation
import ImageIO
import Photos
import UniformTypeIdentifiers
import UIKit

/// Saves to the Photos library and encodes processed images with metadata.
enum PhotoLibrary {
    static func requestAccess() async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited
    }

    /// Saves a processed image (HEIC/JPEG data) with an optional RAW (DNG) companion,
    /// which Photos shows as a RAW + HEIC pair.
    static func save(photo: Data, uti: UTType, raw: Data? = nil) async throws {
        guard await requestAccess() else { throw CameraError.captureFailed("Allow Lumen to add photos in Settings › Privacy › Photos") }
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let o = PHAssetResourceCreationOptions()
            o.uniformTypeIdentifier = uti.identifier
            req.addResource(with: .photo, data: photo, options: o)
            if let raw {
                let ro = PHAssetResourceCreationOptions()
                ro.uniformTypeIdentifier = "com.adobe.raw-image"
                req.addResource(with: .alternatePhoto, data: raw, options: ro)
            }
        }
    }

    static func saveVideo(_ url: URL) async throws {
        guard await requestAccess() else { throw CameraError.captureFailed("Allow Lumen to add videos in Settings › Privacy › Photos") }
        try await PHPhotoLibrary.shared().performChanges {
            let o = PHAssetResourceCreationOptions()
            o.shouldMoveFile = true  // ProRes files can be many GB: move, don't copy
            PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: url, options: o)
        }
    }

    /// HEIC (JPEG fallback) with orientation, EXIF and TIFF metadata.
    static func encode(_ image: CGImage, orientation: UInt32, exif: [String: Any], tiff: [String: Any], quality: Double) -> (Data, UTType)? {
        for type in [UTType.heic, UTType.jpeg] {
            let data = NSMutableData()
            guard let dest = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { continue }
            let props: [String: Any] = [
                kCGImagePropertyOrientation as String: orientation,
                kCGImageDestinationLossyCompressionQuality as String: quality,
                kCGImagePropertyExifDictionary as String: exif,
                kCGImagePropertyTIFFDictionary as String: tiff,
            ]
            CGImageDestinationAddImage(dest, image, props as CFDictionary)
            if CGImageDestinationFinalize(dest) { return (data as Data, type) }
        }
        return nil
    }

    /// First frame of a recorded movie, for the gallery thumbnail.
    static func videoThumbnail(_ url: URL) async -> UIImage? {
        let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 512, height: 512)
        guard let cg = try? await gen.image(at: .zero).image else { return nil }
        return UIImage(cgImage: cg)
    }
}

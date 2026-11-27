import AVFoundation
import SwiftUI
import UIKit

/// UIKit view backed by AVCaptureVideoPreviewLayer, with tap-to-focus, pinch
/// zoom and horizontal swipes (mode change), like the stock camera.
final class PreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    var onTap: ((CGPoint) -> Void)?
    var onPinch: ((CGFloat) -> Void)?
    var onSwipe: ((Int) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        let left = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
        left.direction = .left
        let right = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
        right.direction = .right
        [tap, pinch, left, right].forEach { addGestureRecognizer($0) }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func tapped(_ g: UITapGestureRecognizer) { onTap?(g.location(in: self)) }

    @objc private func pinched(_ g: UIPinchGestureRecognizer) {
        guard g.state == .changed || g.state == .ended else { return }
        onPinch?(g.scale)
        g.scale = 1
    }

    @objc private func swiped(_ g: UISwipeGestureRecognizer) { onSwipe?(g.direction == .left ? 1 : -1) }
}

struct CameraPreview: UIViewRepresentable {
    let service: CameraService
    var onTap: (CGPoint) -> Void
    var onPinch: (CGFloat) -> Void
    var onSwipe: (Int) -> Void

    func makeUIView(context: Context) -> PreviewUIView {
        let v = PreviewUIView()
        service.attach(previewLayer: v.previewLayer)
        return v
    }

    func updateUIView(_ v: PreviewUIView, context: Context) {
        v.onTap = onTap
        v.onPinch = onPinch
        v.onSwipe = onSwipe
    }
}

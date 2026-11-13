import UIKit
import simd

/// Přechody obrazovek: afinní transformace skládané přes simd (měřítko × posun) a pružinová křivka.
enum Transitions {
    static func crossfade(_ view: UIView, duration: CFTimeInterval) {
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        let start = CACurrentMediaTime()
        view.alpha = 0
        let link = DisplayLinkProxy { link in
            let t = Float(min(1, (CACurrentMediaTime() - start) / duration)), s = ChartMath.spring(t)
            // simd 3×3 homogenní matice: měřítko 0.96→1 a posun 12 pt → 0
            let scale = simd_float3x3(diagonal: SIMD3(0.96 + 0.04 * s, 0.96 + 0.04 * s, 1))
            var move = matrix_identity_float3x3; move.columns.2 = SIMD3(0, 12 * (1 - s), 1)
            let m = move * scale
            view.transform = CGAffineTransform(a: CGFloat(m.columns.0.x), b: CGFloat(m.columns.0.y), c: CGFloat(m.columns.1.x),
                                               d: CGFloat(m.columns.1.y), tx: CGFloat(m.columns.2.x), ty: CGFloat(m.columns.2.y))
            view.alpha = CGFloat(s)
            if t >= 1 { view.transform = .identity; view.alpha = 1; link.invalidate() }
        }
        link.start()
    }
}

/// CADisplayLink s blokem (bez retain cyklu přes target/selector).
final class DisplayLinkProxy: NSObject {
    private var link: CADisplayLink?
    private let tick: (DisplayLinkProxy) -> Void
    init(_ tick: @escaping (DisplayLinkProxy) -> Void) { self.tick = tick }
    func start() { let l = CADisplayLink(target: self, selector: #selector(step)); l.add(to: .main, forMode: .common); link = l }
    func invalidate() { link?.invalidate(); link = nil }
    @objc private func step() { tick(self) }
}

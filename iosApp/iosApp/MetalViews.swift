import UIKit
import Metal
import QuartzCore
import CoreImage
import simd

/// Sdílený Metal kontext: MTLDevice, fronta příkazů, knihovna (Xcode kompiluje Shaders.metal → default.metallib).
enum GPU {
    static let device: MTLDevice? = MTLCreateSystemDefaultDevice()
    static let queue: MTLCommandQueue? = device?.makeCommandQueue()
    static let library: MTLLibrary? = device?.makeDefaultLibrary()

    static func pipeline(_ v: String, _ f: String, blending: Bool = false) -> MTLRenderPipelineState? {
        guard let d = device, let lib = library else { return nil }
        let p = MTLRenderPipelineDescriptor()
        p.vertexFunction = lib.makeFunction(name: v); p.fragmentFunction = lib.makeFunction(name: f)
        p.colorAttachments[0].pixelFormat = .bgra8Unorm
        if blending, let c = p.colorAttachments[0] {
            c.isBlendingEnabled = true
            c.sourceRGBBlendFactor = .sourceAlpha; c.destinationRGBBlendFactor = .oneMinusSourceAlpha
            c.sourceAlphaBlendFactor = .one; c.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        }
        return try? d.makeRenderPipelineState(descriptor: p)
    }
}

/// Renderer kreslí do připraveného enkodéru; vrací true, pokud potřebuje další snímky (animace).
protocol MetalRenderer: AnyObject {
    func encode(_ enc: MTLRenderCommandEncoder, viewport: SIMD2<Float>, scale: Float, time: Float) -> Bool
}

/// Nízkoúrovňový UIView s CAMetalLayer: vlastní smyčka CADisplayLink → drawable → command buffer.
/// Běží jen když renderer chce další snímky (šetří baterii); `setNeedsRender()` vyžádá snímek.
final class MetalCanvasView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }
    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer } // swiftlint:disable:this force_cast
    weak var renderer: MetalRenderer?
    var clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
    private var link: CADisplayLink?
    private let t0 = CACurrentMediaTime()

    init(renderer: MetalRenderer?) {
        self.renderer = renderer
        super.init(frame: .zero)
        metalLayer.device = GPU.device
        metalLayer.pixelFormat = .bgra8Unorm
        metalLayer.framebufferOnly = true
        metalLayer.isOpaque = false
        isOpaque = false
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let s = window?.screen.scale ?? UIScreen.main.scale
        metalLayer.contentsScale = s
        metalLayer.drawableSize = CGSize(width: bounds.width * s, height: bounds.height * s)
        setNeedsRender()
    }
    override func didMoveToWindow() { super.didMoveToWindow(); if window == nil { stop() } else { setNeedsRender() } }

    func setNeedsRender() {
        guard link == nil, window != nil, GPU.device != nil else { return }
        let l = CADisplayLink(target: WeakTarget(self), selector: #selector(WeakTarget.tick))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 60)
        l.add(to: .main, forMode: .common); link = l
    }
    private func stop() { link?.invalidate(); link = nil }

    fileprivate func frame() {
        guard let renderer, metalLayer.drawableSize.width > 0, let drawable = metalLayer.nextDrawable(),
              let cb = GPU.queue?.makeCommandBuffer() else { stop(); return }
        let rp = MTLRenderPassDescriptor()
        rp.colorAttachments[0].texture = drawable.texture
        rp.colorAttachments[0].loadAction = .clear; rp.colorAttachments[0].storeAction = .store
        rp.colorAttachments[0].clearColor = clearColor
        guard let enc = cb.makeRenderCommandEncoder(descriptor: rp) else { stop(); return }
        let vp = SIMD2<Float>(Float(bounds.width), Float(bounds.height))
        let more = renderer.encode(enc, viewport: vp, scale: Float(metalLayer.contentsScale), time: Float(CACurrentMediaTime() - t0))
        enc.endEncoding(); cb.present(drawable); cb.commit()
        if !more || UIAccessibility.isReduceMotionEnabled { stop() }
    }

    /// CADisplayLink drží target silně – prostředník se slabou referencí brání retain cyklu.
    private final class WeakTarget: NSObject {
        weak var view: MetalCanvasView?
        init(_ v: MetalCanvasView) { view = v }
        @objc func tick() { view?.frame() }
    }
}

enum QR {
    static func cgImage(_ s: String) -> CGImage? {
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        f.setValue(Data(s.utf8), forKey: "inputMessage"); f.setValue("M", forKey: "inputCorrectionLevel")
        guard let out = f.outputImage else { return nil }
        return CIContext().createCGImage(out, from: out.extent) // 1 px = 1 modul; zvětšení na GPU (nearest)
    }
}

// MARK: - Pozadí obrazovky (gradient + SDF karty)

struct SceneCard { var rect: SIMD4<Float>; var radius: Float; var elevation: Float; var pad = SIMD2<Float>(0, 0) }
struct SceneUniforms { var viewport: SIMD2<Float>; var time: Float; var cardCount: UInt32; var top: SIMD4<Float>; var bottom: SIMD4<Float>; var card: SIMD4<Float> }

/// Kreslí celé pozadí obrazovky; karty jsou obdélníky UIKit prvků (text/ovládání leží nad nimi).
final class SceneRenderer: MetalRenderer {
    private let pipeline = GPU.pipeline("scene_vertex", "scene_fragment")
    var cards: [SceneCard] = []
    var animated = true

    func encode(_ enc: MTLRenderCommandEncoder, viewport: SIMD2<Float>, scale: Float, time: Float) -> Bool {
        guard let pipeline else { return false }
        let dark = UITraitCollection.current.userInterfaceStyle == .dark
        var u = SceneUniforms(viewport: viewport, time: time, cardCount: UInt32(min(cards.count, 16)),
                              top: dark ? SIMD4(0.05, 0.07, 0.12, 1) : SIMD4(0.93, 0.95, 1.0, 1),
                              bottom: dark ? SIMD4(0.01, 0.02, 0.05, 1) : SIMD4(0.98, 0.98, 1.0, 1),
                              card: dark ? SIMD4(0.11, 0.13, 0.18, 1) : SIMD4(1, 1, 1, 1))
        var cs = cards.prefix(16).map { $0 }; if cs.isEmpty { cs = [SceneCard(rect: .zero, radius: 0, elevation: 0)] }
        enc.setRenderPipelineState(pipeline)
        enc.setFragmentBytes(&u, length: MemoryLayout<SceneUniforms>.stride, index: 0)
        enc.setFragmentBytes(&cs, length: MemoryLayout<SceneCard>.stride * cs.count, index: 1)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        return animated
    }
}

/// Kořenový view každé obrazovky: Metal pozadí + průhledný UIKit obsah (jen text/ovládání/přístupnost).
final class MetalSceneView: UIView {
    let renderer = SceneRenderer()
    private lazy var canvas = MetalCanvasView(renderer: renderer)
    /// UIKit prvky, pod které se kreslí karty (rámce se přepočítají při layoutu).
    var cardViews: [UIView] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground          // fallback bez Metalu
        if GPU.device != nil { backgroundColor = .clear; insertSubview(canvas, at: 0) }
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        canvas.frame = bounds; sendSubviewToBack(canvas)
        renderer.cards = cardViews.map {
            let r = $0.convert($0.bounds, to: self).insetBy(dx: -12, dy: -12)
            return SceneCard(rect: SIMD4(Float(r.minX), Float(r.minY), Float(r.width), Float(r.height)), radius: 16, elevation: 1)
        }
        canvas.setNeedsRender()
    }
    override func traitCollectionDidChange(_ previous: UITraitCollection?) { super.traitCollectionDidChange(previous); canvas.setNeedsRender() }
}

// MARK: - QR (textura, nearest-neighbour)

final class QRRenderer: MetalRenderer {
    private let pipeline = GPU.pipeline("tex_vertex", "tex_fragment")
    var texture: MTLTexture?
    func encode(_ enc: MTLRenderCommandEncoder, viewport: SIMD2<Float>, scale: Float, time: Float) -> Bool {
        guard let pipeline, let texture else { return false }
        enc.setRenderPipelineState(pipeline); enc.setFragmentTexture(texture, index: 0)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        return false
    }
}

/// QR kód na GPU. Textura se nahrává přímo přes MTLTexture.replace (bez MetalKit).
final class MetalTextureView: UIView {
    private let qr = QRRenderer()
    private lazy var canvas = MetalCanvasView(renderer: qr)
    private let fallback = UIImageView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        if GPU.device != nil { canvas.clearColor = MTLClearColor(red: 1, green: 1, blue: 1, alpha: 1); addSubview(canvas) }
        else { fallback.layer.magnificationFilter = .nearest; addSubview(fallback) }
        isAccessibilityElement = true; accessibilityLabel = "QR kód adresy"
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() { super.layoutSubviews(); canvas.frame = bounds; fallback.frame = bounds }

    func setImage(_ img: CGImage) {
        guard let d = GPU.device else { fallback.image = UIImage(cgImage: img); return }
        let w = img.width, h = img.height
        var rgba = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: w, height: h, mipmapped: false)
        desc.usage = .shaderRead
        guard let t = d.makeTexture(descriptor: desc) else { return }
        t.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, withBytes: rgba, bytesPerRow: w * 4)
        qr.texture = t; canvas.setNeedsRender()
    }
}

// MARK: - Graf zůstatku

/// Uniformy – rozložení odpovídá `ChartUniforms` v Shaders.metal.
struct ChartUniforms {
    var transform: simd_float4x4
    var lineColor: SIMD4<Float>
    var fillTop: SIMD4<Float>
    var fillBottom: SIMD4<Float>
    var progress: Float
}

/// Graf na GPU: simd ortografická matice data → clip, animace = simd_mix bodů + interpolace matic s easingem.
final class ChartRenderer: MetalRenderer {
    private let fill = GPU.pipeline("chart_vertex", "chart_fill", blending: true)
    private let line = GPU.pipeline("chart_vertex", "chart_line", blending: true)
    private var from: [SIMD2<Float>] = [], to: [SIMD2<Float>] = []
    private var fromM = matrix_identity_float4x4, toM = matrix_identity_float4x4
    private var start: CFTimeInterval = 0
    private let duration: CFTimeInterval = 0.6

    func setSeries(_ values: [Float]) {
        #if RUST_CORE
        rust = nil                                     // nová data z CPU cesty mají přednost; MTLBuffer uvolní Metal
        #endif
        let v = values.isEmpty ? [0, 0] : (values.count == 1 ? [values[0], values[0]] : values)
        let pts = ChartMath.resample(v, count: 64)
        from = to.isEmpty ? pts.map { SIMD2($0.x, pts.map(\.y).min() ?? 0) } : current()
        fromM = to.isEmpty ? ChartMath.fit(pts) : currentMatrix()
        to = pts; toM = ChartMath.fit(pts); start = CACurrentMediaTime()
    }

    private func t() -> Float { Float(min(1, (CACurrentMediaTime() - start) / duration)) }
    private func current() -> [SIMD2<Float>] { let e = ChartMath.easeInOutCubic(t()); return zip(from, to).map { simd_mix($0, $1, SIMD2(repeating: e)) } }
    private func currentMatrix() -> simd_float4x4 { ChartMath.mix(fromM, toM, ChartMath.easeInOutCubic(t())) }

    #if RUST_CORE
    /// Vrcholy z Rustu (zero-copy MTLBuffer, formát ELFALL-GPU-CHART-v1: n bodů lineStrip, pak 2n výplň).
    /// Buffer se jen čte; nahrazení na hlavním vlákně – starý uvolní Metal po dokončení snímků.
    private var rust: (buf: MTLBuffer, n: Int)?
    private var rustStart: CFTimeInterval = 0
    private let dot = GPU.pipeline("chart_dot_vertex", "chart_dot_fragment", blending: true)
    func setRustVertices(_ buf: MTLBuffer, points n: Int) {
        // délka bufferu je capacity (stránky) ≥ 3n·8 B – shader nečte za 3n vrcholů
        guard n >= 2, buf.length >= 3 * n * MemoryLayout<SIMD2<Float>>.stride else { rust = nil; return }
        rust = (buf, n); rustStart = CACurrentMediaTime()
    }
    private func encodeRust(_ enc: MTLRenderCommandEncoder, _ r: (buf: MTLBuffer, n: Int), _ fill: MTLRenderPipelineState, _ line: MTLRenderPipelineState) {
        let tint = SIMD4<Float>(0.2, 0.55, 1.0, 1)
        let p = ChartMath.easeOutCubic(Float(min(1, (CACurrentMediaTime() - rustStart) / duration)))   // odkrytí po Face ID
        var u = ChartUniforms(transform: ChartMath.unitFit(), lineColor: tint,
                              fillTop: SIMD4(tint.x, tint.y, tint.z, 0.35), fillBottom: SIMD4(tint.x, tint.y, tint.z, 0), progress: p)
        let stride = MemoryLayout<SIMD2<Float>>.stride
        enc.setRenderPipelineState(fill)
        enc.setVertexBuffer(r.buf, offset: r.n * stride, index: 0)
        enc.setVertexBytes(&u, length: MemoryLayout<ChartUniforms>.stride, index: 1)
        enc.setFragmentBytes(&u, length: MemoryLayout<ChartUniforms>.stride, index: 1)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 2 * r.n)
        enc.setRenderPipelineState(line)
        enc.setVertexBuffer(r.buf, offset: 0, index: 0)
        enc.drawPrimitives(type: .lineStrip, vertexStart: 0, vertexCount: r.n)
        if let dot, p >= 1 {
            var last = UInt32(r.n - 1)
            enc.setRenderPipelineState(dot)
            enc.setVertexBytes(&last, length: MemoryLayout<UInt32>.stride, index: 2)
            enc.drawPrimitives(type: .point, vertexStart: 0, vertexCount: 1)
        }
    }
    private var rustAnimating: Bool { CACurrentMediaTime() - rustStart < duration + 0.05 }
    #endif

    func encode(_ enc: MTLRenderCommandEncoder, viewport: SIMD2<Float>, scale: Float, time: Float) -> Bool {
        #if RUST_CORE
        if let fill, let line, let r = rust { encodeRust(enc, r, fill, line); return rustAnimating }
        #endif
        guard let fill, let line, !to.isEmpty else { return false }
        let pts = current(), m = currentMatrix(), base = ChartMath.unfit(m).y
        var strip: [SIMD2<Float>] = []; strip.reserveCapacity(pts.count * 2)
        for p in pts { strip.append(p); strip.append(SIMD2(p.x, base)) }      // výplň: křivka/základna
        let tint = SIMD4<Float>(0.2, 0.55, 1.0, 1)
        var u = ChartUniforms(transform: m, lineColor: tint, fillTop: SIMD4(tint.x, tint.y, tint.z, 0.35),
                              fillBottom: SIMD4(tint.x, tint.y, tint.z, 0), progress: ChartMath.easeOutCubic(t()))
        // ≤ 4 KB → setVertexBytes (bez alokace MTLBuffer každý snímek)
        enc.setRenderPipelineState(fill)
        enc.setVertexBytes(&strip, length: MemoryLayout<SIMD2<Float>>.stride * strip.count, index: 0)
        enc.setVertexBytes(&u, length: MemoryLayout<ChartUniforms>.stride, index: 1)
        enc.setFragmentBytes(&u, length: MemoryLayout<ChartUniforms>.stride, index: 1)
        enc.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: strip.count)
        var lp = pts
        enc.setRenderPipelineState(line)
        enc.setVertexBytes(&lp, length: MemoryLayout<SIMD2<Float>>.stride * lp.count, index: 0)
        enc.drawPrimitives(type: .lineStrip, vertexStart: 0, vertexCount: lp.count)
        return t() < 1
    }
}

final class MetalChartView: UIView {
    private let chart = ChartRenderer()
    private lazy var canvas = MetalCanvasView(renderer: chart)
    override init(frame: CGRect) {
        super.init(frame: frame); addSubview(canvas)
        isAccessibilityElement = true; accessibilityLabel = "Graf zůstatku"; accessibilityTraits = .image
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() { super.layoutSubviews(); canvas.frame = bounds.insetBy(dx: 12, dy: 12) }
    #if RUST_CORE
    func setRustVertices(_ buf: MTLBuffer, points: Int) { chart.setRustVertices(buf, points: points); canvas.setNeedsRender() }
    #endif
    func setSeries(_ values: [Float]) {
        chart.setSeries(values); canvas.setNeedsRender()
        accessibilityValue = values.last.map { String(format: "%.8f BTC", $0) }
    }
}

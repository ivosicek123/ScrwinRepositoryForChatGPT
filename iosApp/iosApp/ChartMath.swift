import simd

/// Čistá matematika grafů a animací (simd) – bez UIKit/Metal, aby šla testovat XCTestem.
enum ChartMath {
    /// Rovnoměrné převzorkování řady na `count` bodů (x ∈ [0,1]), lineární interpolace.
    static func resample(_ v: [Float], count: Int) -> [SIMD2<Float>] {
        precondition(v.count >= 2 && count >= 2)
        return (0..<count).map { i in
            let x = Float(i) / Float(count - 1), f = x * Float(v.count - 1)
            let i0 = min(Int(f), v.count - 2), w = f - Float(i0)
            return SIMD2(x, simd_mix(v[i0], v[i0 + 1], w))
        }
    }

    /// Ortografická matice: obdélník dat (s 8% okrajem ve y) → clip prostor [-1,1]².
    static func fit(_ pts: [SIMD2<Float>]) -> simd_float4x4 {
        let ys = pts.map(\.y)
        var lo = ys.min() ?? 0, hi = ys.max() ?? 1
        if hi - lo < 1e-9 { lo -= 1; hi += 1 }
        let pad = (hi - lo) * 0.08
        return ortho(left: 0, right: 1, bottom: lo - pad, top: hi + pad)
    }

    static func ortho(left l: Float, right r: Float, bottom b: Float, top t: Float) -> simd_float4x4 {
        simd_float4x4(columns: (
            SIMD4(2 / (r - l), 0, 0, 0),
            SIMD4(0, 2 / (t - b), 0, 0),
            SIMD4(0, 0, 1, 0),
            SIMD4(-(r + l) / (r - l), -(t + b) / (t - b), 0, 1)))
    }

    /// Datová souřadnice levého dolního rohu (inverze matice aplikovaná na clip (-1,-1)).
    static func unfit(_ m: simd_float4x4) -> SIMD2<Float> {
        let p = m.inverse * SIMD4<Float>(-1, -1, 0, 1); return SIMD2(p.x, p.y)
    }

    static func mix(_ a: simd_float4x4, _ b: simd_float4x4, _ t: Float) -> simd_float4x4 {
        let w = SIMD4<Float>(repeating: t)
        return simd_float4x4(columns: (simd_mix(a.columns.0, b.columns.0, w), simd_mix(a.columns.1, b.columns.1, w),
                                       simd_mix(a.columns.2, b.columns.2, w), simd_mix(a.columns.3, b.columns.3, w)))
    }

    /// Matice pro vrcholy normalizované Rustem do [0,1]² (ELFALL-GPU-CHART-v1) – stejný 8% okraj jako `fit`.
    static func unitFit() -> simd_float4x4 { ortho(left: 0, right: 1, bottom: -0.08, top: 1.08) }

    /// Počet bodů z velikosti dat Rust bufferu (n bodů lineStrip + 2n výplň, float2) – nil, pokud nesedí.
    static func rustPointCount(bytes: Int) -> Int? {
        let per = 3 * MemoryLayout<SIMD2<Float>>.stride
        guard bytes > 0, bytes % per == 0, bytes / per >= 2 else { return nil }
        return bytes / per
    }

    static func easeInOutCubic(_ t: Float) -> Float { t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2 }
    static func easeOutCubic(_ t: Float) -> Float { 1 - pow(1 - t, 3) }

    /// Kritické tlumení pružiny (pro přechody): x(t) = 1 - (1 + ωt)e^{-ωt}.
    static func spring(_ t: Float, omega: Float = 12) -> Float { 1 - (1 + omega * t) * exp(-omega * t) }
}

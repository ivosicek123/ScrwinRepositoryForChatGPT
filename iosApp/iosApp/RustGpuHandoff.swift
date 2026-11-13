#if RUST_CORE
import Foundation
#if canImport(elfall_coreFFI)
import elfall_coreFFI
#endif

/// Vlastnictví zero-copy bufferu z Rustu (core/src/gpu.rs, C ABI `ElfallGpuBuffer`).
///
/// Pravidla životnosti:
/// - paměť vlastní Rust; Swift ji nikdy neuvolňuje přes free/Data, jen přes `elfall_gpu_buffer_free`;
/// - `take()` předá ukazatel + délku + deallocator **právě jednou** (do `makeBuffer(bytesNoCopy:)`);
///   od té chvíle buffer žije, dokud ho drží MTLBuffer / command buffery, a pak Metal zavolá deallocator;
/// - pokud se nepředá (chyba, `makeBuffer` vrátí nil), uvolní ho `deinit` / `releaseUnconsumed()`.
/// Tento typ nepoužívá Metal, takže se testuje i Swiftem na Linuxu (core/swift-harness).
final class RustGpuAllocation {
    let pointer: UnsafeMutableRawPointer
    /// Platná data v bajtech.
    let length: Int
    /// Násobek stránky – délka pro `makeBuffer(bytesNoCopy:length:)`.
    let capacity: Int
    private var owned = true
    private let lock = NSLock()

    enum Failure: Error { case core(Int32), empty }

    private init(_ raw: ElfallGpuBuffer) throws {
        guard let p = raw.ptr, raw.capacity > 0 else { throw Failure.empty }
        pointer = UnsafeMutableRawPointer(p); length = Int(raw.len); capacity = Int(raw.capacity)
    }

    /// Vault (Keychain klíč) → Rust dešifruje + zpracuje → stránkově zarovnané vrcholy grafu.
    static func chart(key: [UInt8], field: String, sealed: String, points: UInt32 = 64) throws -> RustGpuAllocation {
        var out = ElfallGpuBuffer(ptr: nil, len: 0, capacity: 0)
        let f = Array(field.utf8), s = Array(sealed.utf8)
        let rc = key.withUnsafeBufferPointer { k in f.withUnsafeBufferPointer { fp in s.withUnsafeBufferPointer { sp in
            elfall_gpu_chart_from_sealed(k.baseAddress, k.count, fp.baseAddress, fp.count, sp.baseAddress, sp.count, points, &out)
        } } }
        guard rc == 0 else { throw Failure.core(rc) }
        return try RustGpuAllocation(out)
    }

    static func chart(values: [Float], points: UInt32 = 64) throws -> RustGpuAllocation {
        var out = ElfallGpuBuffer(ptr: nil, len: 0, capacity: 0)
        let rc = values.withUnsafeBufferPointer { elfall_gpu_chart_from_values($0.baseAddress, $0.count, points, &out) }
        guard rc == 0 else { throw Failure.core(rc) }
        return try RustGpuAllocation(out)
    }

    /// Deallocator pro Metal. Zachytí jen ukazatel a capacity, ne `self` – MTLBuffer může přežít tento objekt.
    static let deallocator: (UnsafeMutableRawPointer, Int) -> Void = { p, len in
        let rc = elfall_gpu_buffer_free(p, len)
        assert(rc == 0, "elfall_gpu_buffer_free selhal: \(rc)")
    }

    /// Předá vlastnictví (jednou). Druhé volání vrátí nil.
    func take() -> (pointer: UnsafeMutableRawPointer, length: Int, deallocator: (UnsafeMutableRawPointer, Int) -> Void)? {
        lock.lock(); defer { lock.unlock() }
        guard owned else { return nil }
        owned = false
        return (pointer, capacity, RustGpuAllocation.deallocator)
    }

    /// Uvolní, pokud se buffer nepředal.
    func releaseUnconsumed() {
        lock.lock(); defer { lock.unlock() }
        guard owned else { return }
        owned = false
        _ = elfall_gpu_buffer_free(pointer, capacity)
    }

    deinit { releaseUnconsumed() }
}
#endif

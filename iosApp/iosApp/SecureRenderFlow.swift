#if RUST_CORE
import Foundation
import LocalAuthentication
import Metal

/// Datový tok: Face ID + Secure Enclave autorizuje → Rust jádro dešifruje a zpracuje data →
/// zero-copy předání (Rust stránkově zarovnaný buffer → MTLBuffer bytesNoCopy) → Metal vykreslí.
enum SecureRenderFlow {
    enum Failure: Error { case biometry(Error?), enclave, noKey, core(Error), metal }

    /// Vše kromě vykreslení běží mimo hlavní vlákno; `completion` na hlavním vlákně.
    static func showSealedChart(field: String, sealed: String, in view: MetalChartView,
                                signer: SecureEnclaveSigner = SecureEnclaveSigner(),
                                completion: @escaping (Result<Void, Failure>) -> Void) {
        let ctx = LAContext()
        ctx.localizedReason = "Odemknout data grafu"
        ctx.touchIDAuthenticationAllowableReuseDuration = 0
        // 1) Face ID (biometrie, bez fallbacku na kód – klíč v SE má .biometryCurrentSet)
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Odemknout data grafu") { ok, err in
            guard ok else { return DispatchQueue.main.async { completion(.failure(.biometry(err))) } }
            // 2) Secure Enclave: podpis čerstvé výzvy tímtéž LAContextem → doklad, že autorizace proběhla na tomto zařízení.
            var nonce = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, nonce.count, &nonce) == errSecSuccess,
                  signer.sign(Data(nonce), context: ctx) != nil else {
                return DispatchQueue.main.async { completion(.failure(.enclave)) }
            }
            // 3) Rust: dešifrování vaultu (XChaCha20-Poly1305, libsodium) + zpracování do stránkově zarovnaného bufferu.
            guard var key = SodiumVault.shared.dataKey() else { return DispatchQueue.main.async { completion(.failure(.noKey)) } }
            let alloc: RustGpuAllocation
            do { alloc = try RustGpuAllocation.chart(key: key, field: field, sealed: sealed) }
            catch { key.withUnsafeMutableBufferPointer { $0.initialize(repeating: 0) }
                    return DispatchQueue.main.async { completion(.failure(.core(error))) } }
            key.withUnsafeMutableBufferPointer { $0.initialize(repeating: 0) }
            DispatchQueue.main.async {
                // 4) Zero-copy hand-off + 5) render
                guard let dev = GPU.device, let buf = makeNoCopyBuffer(dev, alloc) else { return completion(.failure(.metal)) }
                guard let n = ChartMath.rustPointCount(bytes: alloc.length) else { return completion(.failure(.metal)) }
                view.setRustVertices(buf, points: n)
                completion(.success(()))
            }
        }
    }

    /// MTLBuffer nad pamětí Rustu bez kopie. `.storageModeShared` (unified memory na iOS).
    /// Životnost: MTLBuffer drží paměť; command buffery (retainedReferences = výchozí) drží MTLBuffer,
    /// dokud GPU nedokončí; poslední release zavolá deallocator → `elfall_gpu_buffer_free`.
    static func makeNoCopyBuffer(_ device: MTLDevice, _ alloc: RustGpuAllocation) -> MTLBuffer? {
        guard let h = alloc.take() else { return nil }
        guard let b = device.makeBuffer(bytesNoCopy: h.pointer, length: h.length, options: [.storageModeShared], deallocator: h.deallocator) else {
            h.deallocator(h.pointer, h.length) // Metal vlastnictví nepřevzal
            return nil
        }
        b.label = "rust-gpu-chart"
        return b
    }
}
#endif

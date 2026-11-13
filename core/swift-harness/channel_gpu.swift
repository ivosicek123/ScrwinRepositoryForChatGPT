import Foundation
#if canImport(elfall_coreFFI)
import elfall_coreFFI
#endif

/// Swift na Linuxu: šifrovaný kanál přes UniFFI + zero-copy buffer přes C ABI (bez Metalu – simuluje
/// `makeBuffer(bytesNoCopy:)`: převezme ukazatel jednou a později zavolá deallocator).
func channelAndGpuTests() {
    // ---- kanál ELFALL-CH-v1 ----
    let srv = try! channelKeypair()
    let cli = try! SecureChannel.client(serverPk: srv.public)
    let s = try! SecureChannel.server(serverSecret: srv.secret, clientPk: try! cli.publicKey())
    try! s.acceptHeader(header: try! cli.header()); try! cli.acceptHeader(header: try! s.header())
    check(try! cli.binding() == (try! s.binding()) && (try! cli.binding()).count == 32, "kanál: shodný binding (výzva pro Secure Enclave)")
    let token = Data("short-lived-device-bound-token".utf8)
    let c1 = try! cli.seal(msg: token, last: false)
    var bad = c1; bad[3] ^= 1
    check((try? s.open(ciphertext: bad)) == nil, "kanál: změněná zpráva odmítnuta")
    let m = try! s.open(ciphertext: c1)
    check(m.data == token && !m.last, "kanál: token klient→server")
    check((try? s.open(ciphertext: c1)) == nil, "kanál: přehrání odmítnuto")
    let fin = try! s.open(ciphertext: try! cli.seal(msg: Data("bye".utf8), last: true))
    check(fin.last && (try? cli.seal(msg: Data(), last: false)) == nil, "kanál: TAG_FINAL uzavře směr")
    let mitm = try! channelKeypair()
    let m2 = try! SecureChannel.server(serverSecret: mitm.secret, clientPk: try! cli.publicKey())
    let cli2 = try! SecureChannel.client(serverPk: srv.public)
    let m3 = try! SecureChannel.server(serverSecret: mitm.secret, clientPk: try! cli2.publicKey())
    try! m3.acceptHeader(header: try! cli2.header())
    check((try? m3.open(ciphertext: try! cli2.seal(msg: token, last: false))) == nil, "kanál: MITM s jiným klíčem nepřečte token")
    _ = m2

    // ---- zero-copy GPU buffer ----
    let page = elfall_gpu_page_size()
    let live0 = elfall_gpu_live_allocations()
    let key = [UInt8](repeating: 0x42, count: 32)
    let sealed = try! vaultSeal(key: Data(key), field: "chart", plaintext: "10,20,15")
    let a = try! RustGpuAllocation.chart(key: key, field: "chart", sealed: sealed, points: 3)
    check(Int(bitPattern: a.pointer) % page == 0 && a.capacity % page == 0, "gpu: stránkově zarovnaný ukazatel i délka (page \(page))")
    let pts = a.pointer.bindMemory(to: SIMD2<Float>.self, capacity: 9)
    check(pts[1] == SIMD2(0.5, 1) && pts[2] == SIMD2(1, 0.5) && pts[6] == SIMD2(0.5, 0), "gpu: rozložení ELFALL-GPU-CHART-v1 čitelné jako SIMD2<Float>")
    check((try? RustGpuAllocation.chart(key: key, field: "jine", sealed: sealed)) == nil, "gpu: jiné pole vaultu odmítnuto")
    // simulace makeBuffer(bytesNoCopy:deallocator:)
    var held: (UnsafeMutableRawPointer, Int, (UnsafeMutableRawPointer, Int) -> Void)? = a.take().map { ($0.pointer, $0.length, $0.deallocator) }
    check(held != nil && a.take() == nil, "gpu: vlastnictví předáno právě jednou")
    a.releaseUnconsumed() // nesmí uvolnit – vlastní ho „MTLBuffer“
    check(elfall_gpu_live_allocations() == live0 + 1, "gpu: po předání žije do deallocatoru")
    if let (p, n, dealloc) = held { dealloc(p, n); check(elfall_gpu_buffer_free(p, n) < 0, "gpu: dvojí free odmítnuto Rustem") }
    held = nil
    check(elfall_gpu_live_allocations() == live0, "gpu: deallocator uvolnil")
    do { _ = try! RustGpuAllocation.chart(values: [1, 2, 3]) }  // nepředaná alokace → deinit uvolní
    check(elfall_gpu_live_allocations() == live0, "gpu: nepředaný buffer uvolněn v deinit")

    // vypůjčená paměť volajícího (jako MTLBuffer.contents() alokovaný Metalem) – Rust nic nevlastní
    let need = elfall_gpu_chart_bytes(8)
    let mem = UnsafeMutableRawPointer.allocate(byteCount: need + 16, alignment: 16); defer { mem.deallocate() }
    mem.initializeMemory(as: UInt8.self, repeating: 0xAA, count: need + 16)
    let w = [Float(1), 2].withUnsafeBufferPointer { elfall_gpu_chart_write($0.baseAddress, $0.count, 8, mem.assumingMemoryBound(to: UInt8.self), need + 16) }
    check(w == need && mem.load(fromByteOffset: need, as: UInt8.self) == 0xAA, "gpu: zápis do vypůjčené paměti volajícího, nic za koncem")
    check(elfall_gpu_chart_write(nil, 0, 8, mem.assumingMemoryBound(to: UInt8.self), need - 1) < 0, "gpu: malý cíl odmítnut")
}

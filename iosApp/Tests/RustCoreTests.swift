import XCTest
@testable import Eleanor

final class ChartMathTests: XCTestCase {
    func testUnitFitAndRustPointCount() {
        let m = ChartMath.unitFit()
        for p in [SIMD2<Float>(0, 0), SIMD2(1, 1)] { let c = m * SIMD4(p.x, p.y, 0, 1); XCTAssert(abs(c.x) <= 1.0001 && abs(c.y) < 1) }
        XCTAssertEqual(ChartMath.rustPointCount(bytes: 64 * 24), 64)
        XCTAssertNil(ChartMath.rustPointCount(bytes: 25)); XCTAssertNil(ChartMath.rustPointCount(bytes: 24))
    }
    func testResampleEndpoints() {
        let p = ChartMath.resample([0, 10], count: 5)
        XCTAssertEqual(p.first!.y, 0); XCTAssertEqual(p.last!.y, 10); XCTAssertEqual(p[2].y, 5, accuracy: 1e-5)
    }
    func testFitMapsRangeIntoClip() {
        let pts = ChartMath.resample([1, 3], count: 2), m = ChartMath.fit(pts)
        for p in pts { let c = m * SIMD4(p.x, p.y, 0, 1); XCTAssert(abs(c.x) <= 1.0001 && abs(c.y) <= 1.0001) }
    }
    func testEasing() {
        XCTAssertEqual(ChartMath.easeInOutCubic(0), 0); XCTAssertEqual(ChartMath.easeInOutCubic(1), 1)
        XCTAssertEqual(ChartMath.spring(0), 0); XCTAssertGreaterThan(ChartMath.spring(1), 0.99)
    }
}

#if RUST_CORE
import Sodium

/// Kompatibilita Rust jádra se swift-sodium oběma směry (stejně jako Android RustCoreTest).
final class RustCoreInteropTests: XCTestCase {
    let key = Data((0..<32).map { UInt8($0) })
    let sodium = Sodium()

    func testRustSealsSodiumOpens() throws {
        let s = try vaultSeal(key: key, field: "payout_address", plaintext: "GABC")
        let raw = Bytes(Data(base64Encoded: String(s.dropFirst(4)))!)
        let m = sodium.aead.xchacha20poly1305ietf.decrypt(nonceAndAuthenticatedCipherText: raw, secretKey: Bytes(key), additionalData: Bytes("elfall/v1/payout_address".utf8))
        XCTAssertEqual(m.flatMap { String(bytes: $0, encoding: .utf8) }, "GABC")
    }
    func testSodiumSealsRustOpens() throws {
        let r: Bytes = sodium.aead.xchacha20poly1305ietf.encrypt(message: Bytes("GXYZ".utf8), secretKey: Bytes(key), additionalData: Bytes("elfall/v1/stellar_account".utf8))!
        let s = "sv1:" + Data(r).base64EncodedString()
        XCTAssertEqual(try vaultOpen(key: key, field: "stellar_account", sealed: s), "GXYZ")
        XCTAssertThrowsError(try vaultOpen(key: key, field: "payout_address", sealed: s))
    }
    func testFixedVectorFromRust() throws {
        XCTAssertEqual(try vaultOpen(key: Data(repeating: 0x42, count: 32), field: "payout",
                                     sealed: "sv1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXy73GpIPkgWFy7/n/ePZi7kBBt5WPl9s="), "GABCDEF")
    }
    func testEd25519AndArgon2Interop() throws {
        let seed = Data(repeating: 9, count: 32), msg = Data("tx".utf8)
        let pk = try ed25519PublicKey(secret: seed), sig = try ed25519SignFramed(secret: seed, msg: msg)
        XCTAssertTrue(sodium.sign.verify(message: Bytes(try signingInput(msg: msg)), publicKey: Bytes(pk), signature: Bytes(sig)))
        let h = try pinHash(pin: "2468")
        XCTAssertTrue(sodium.pwHash.strVerify(hash: h, passwd: Bytes("2468".utf8)))
    }
}

import Metal
#if canImport(elfall_coreFFI)
import elfall_coreFFI
#endif
final class RustGpuZeroCopyTests: XCTestCase {
    func testNoCopyBufferSharesRustMemoryAndFreesOnRelease() throws {
        guard let dev = MTLCreateSystemDefaultDevice() else { throw XCTSkip("bez Metal zařízení") }
        let live = elfall_gpu_live_allocations()
        let a = try RustGpuAllocation.chart(values: [1, 3, 2], points: 64)
        let ptr = a.pointer
        var buf: MTLBuffer? = SecureRenderFlow.makeNoCopyBuffer(dev, a)
        XCTAssertEqual(buf?.contents(), ptr, "žádná kopie")
        XCTAssertEqual(buf?.length, a.capacity)
        XCTAssertNil(a.take(), "vlastnictví už má Metal")
        buf = nil
        XCTAssertEqual(elfall_gpu_live_allocations(), live, "deallocator uvolnil Rust paměť")
    }
}
#endif

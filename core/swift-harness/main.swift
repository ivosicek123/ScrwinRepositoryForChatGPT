import Foundation
func check(_ c: Bool, _ m: String) { print((c ? "OK   " : "FAIL ") + m); if !c { exit(1) } }
let key = Data(repeating: 0x42, count: 32)
check(try! vaultOpen(key: key, field: "payout", sealed: "sv1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXy73GpIPkgWFy7/n/ePZi7kBBt5WPl9s=") == "GABCDEF", "pevný vektor (Rust→Swift)")
let s = try! vaultSeal(key: key, field: "a", plaintext: "x")
check(try! vaultOpen(key: key, field: "a", sealed: s) == "x", "roundtrip")
check((try? vaultOpen(key: key, field: "b", sealed: s)) == nil, "jiné pole odmítnuto")
let f = try! signingInput(msg: Data("abc".utf8))
check(f.prefix(14) == Data("ELFALL-SIG-v1\0".utf8) && f.count == 21, "rámování")
let seed = Data(repeating: 9, count: 32)
let pk = try! ed25519PublicKey(secret: seed), sig = try! ed25519SignFramed(secret: seed, msg: Data("tx".utf8))
check(try! ed25519VerifyFramed(public: pk, msg: Data("tx".utf8), sig: sig), "Ed25519 verify")
check(pinVerify(pin: "1", phc: try! pinHash(pin: "1")), "Argon2id")
channelAndGpuTests()

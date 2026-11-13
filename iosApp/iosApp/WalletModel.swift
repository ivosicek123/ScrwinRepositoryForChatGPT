import Foundation
import Shared

@MainActor
final class WalletModel: ObservableObject {
    let facade = EleanorFacade(network: BtcNetwork.testnet)   // standalone testnet, direct mempool.space
    @Published var unlocked = false
    @Published var hasSeed = KeychainSeedStore.hasSeed()
    @Published var newMnemonic: [String]? = nil
    @Published var addressIndex = Int(UserDefaults.standard.integer(forKey: "addrIndex"))
    @Published var balance: String = "…"
    @Published var error: String? = nil
    @Published var history: [HistoryItem] = []
    @Published var preview: FeePreview? = nil
    @Published var lastTxid: String? = nil
    @Published var snapshot: NetworkSnapshot? = nil
    @Published var infoMainnet = false

    var address: String { unlocked ? facade.receiveAddress(index: Int32(addressIndex)) : "" }

    func create() {
        let words = facade.generateMnemonic()
        do { try KeychainSeedStore.save(words); try facade.open(words: words); newMnemonic = words; hasSeed = true; unlocked = true; refresh() }
        catch { self.error = "\(error)" }
    }

    func restore(_ text: String) {
        let words = text.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        do { try facade.open(words: words); try KeychainSeedStore.save(words); hasSeed = true; unlocked = true; refresh() }
        catch { self.error = "Neplatná fráze / Invalid phrase" }
    }

    func unlock() {
        do {
            guard let words = try KeychainSeedStore.load(reason: "Odemknout Eleanor") else { return }
            try facade.open(words: words); unlocked = true; refresh()
        } catch { self.error = "\(error)" }
    }

    func lock() { facade.close(); unlocked = false; newMnemonic = nil }

    func nextAddress() { addressIndex = min(addressIndex + 1, 19); UserDefaults.standard.set(addressIndex, forKey: "addrIndex") }

    func refresh() {
        Task {
            do { let s = try await facade.balanceSats(addressCount: Int32(addressIndex + 1)); balance = facade.formatSats(sats: s.int64Value) }
            catch { self.error = "Balance: \(error.localizedDescription)" }
        }
    }

    func loadHistory() {
        Task {
            do { history = try await facade.history(addressCount: Int32(addressIndex + 1)) }
            catch { self.error = "History: \(error.localizedDescription)" }
        }
    }

    func previewTransfer(to: String, amount: String) {
        let sats = facade.parseBtcAmount(input: amount)
        guard sats > 0 else { error = "Neplatná částka"; return }
        if let e = facade.validate(address: to) { error = e; return }
        Task {
            do { preview = try await facade.previewTransfer(destination: to, amountSats: sats) }
            catch { self.error = error.localizedDescription }
        }
    }

    func send() {
        guard let p = preview else { return }
        Task {
            // Face ID gate before signing: re-read seed from Keychain (prompts biometrics)
            do {
                guard let _ = try KeychainSeedStore.load(reason: "Potvrdit převod / Confirm transfer") else { return }
                lastTxid = try await facade.send(preview: p); preview = nil; refresh()
            } catch { self.error = error.localizedDescription }
        }
    }

    func loadNetwork() {
        Task {
            do { snapshot = try await facade.networkSnapshot(infoNetwork: infoMainnet ? BtcNetwork.mainnet : BtcNetwork.testnet) }
            catch { self.error = "Network: \(error.localizedDescription)" }
        }
    }
}

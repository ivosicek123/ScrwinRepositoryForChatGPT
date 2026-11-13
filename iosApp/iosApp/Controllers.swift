import UIKit
import Combine
import Shared

/// Kořen: přepíná onboarding ↔ hlavní taby podle `WalletModel.unlocked`, zobrazuje chyby.
@MainActor
final class RootViewController: UINavigationController {
    let model: WalletModel
    private var bag = Set<AnyCancellable>()

    init(model: WalletModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) není podporován") }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationBar.prefersLargeTitles = true
        model.$unlocked.removeDuplicates().sink { [weak self] u in self?.show(unlocked: u) }.store(in: &bag)
        model.$error.compactMap { $0 }.sink { [weak self] e in self?.alert(e) }.store(in: &bag)
    }

    private func show(unlocked: Bool) {
        let vc: UIViewController = unlocked ? MainTabController(model: model) : OnboardingViewController(model: model)
        vc.title = "Eleanor · testnet"
        if unlocked {
            vc.navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Zamknout", primaryAction: UIAction { [weak self] _ in self?.model.lock() })
        }
        // přechod animovaný Metal/simd křivkou (viz Transitions.swift)
        setViewControllers([vc], animated: false)
        Transitions.crossfade(view, duration: 0.35)
    }

    private func alert(_ msg: String) {
        let a = UIAlertController(title: nil, message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in self?.model.error = nil })
        present(a, animated: true)
    }
}

// MARK: - Onboarding
@MainActor
final class OnboardingViewController: UIViewController {
    override func loadView() { view = MetalSceneView() } // Metal pozadí, UIKit jen text/ovládání
    let model: WalletModel
    init(model: WalletModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }

    private let phrase = UITextView()

    override func viewDidLoad() {
        super.viewDidLoad()
        let stack = UIStackView.vertical(spacing: 12)
        if model.hasSeed {
            stack.addArrangedSubview(.filled("Odemknout (Face ID)") { [weak self] in self?.model.unlock() })
        } else {
            stack.addArrangedSubview(.filled("Nová peněženka / Create wallet") { [weak self] in self?.model.create() })
            stack.addArrangedSubview(UILabel.caption("Obnovit z 12 slov"))
            phrase.autocorrectionType = .no; phrase.autocapitalizationType = .none
            phrase.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
            phrase.layer.cornerRadius = 8; phrase.heightAnchor.constraint(equalToConstant: 96).isActive = true
            stack.addArrangedSubview(phrase)
            stack.addArrangedSubview(.plain("Obnovit / Restore") { [weak self] in self?.model.restore(self?.phrase.text ?? "") })
        }
        if BankIdFlags.enabled {
            stack.addArrangedSubview(.plain("Ověřit přes Bank iD") {}) // TODO(bankid): BankIdVerifier.start(KYC)
            stack.addArrangedSubview(UILabel.caption("Testovací prostředí (sandbox) – nepoužívejte skutečné údaje."))
        }
        stack.addArrangedSubview(UILabel.caption("© 2026 Ivo Faltýn — autor, vývojář a vlastník projektu ELFALL / Fallcoin / Eleanor (GitHub @ivosicek123)"))
        view.pin(stack)
        (view as? MetalSceneView)?.cardViews = [stack]   // SDF karta pod obsahem
    }
}

// MARK: - Taby
@MainActor
final class MainTabController: UITabBarController {
    init(model: WalletModel) {
        super.init(nibName: nil, bundle: nil)
        viewControllers = [
            ReceiveViewController(model: model).tab("Příjem", "qrcode"),
            TransferViewController(model: model).tab("Převod", "arrow.up.right"),
            HistoryViewController(model: model).tab("Historie", "clock"),
            NetworkViewController(model: model).tab("Síť", "network"),
        ]
    }
    required init?(coder: NSCoder) { fatalError() }
}

// MARK: - Příjem (QR přes Metal)
@MainActor
final class ReceiveViewController: UIViewController {
    override func loadView() { view = MetalSceneView() } // Metal pozadí, UIKit jen text/ovládání
    let model: WalletModel
    private var bag = Set<AnyCancellable>()
    private let qr = MetalTextureView()
    private let balance = UILabel(), index = UILabel.caption(""), address = UILabel(), mnemonic = UILabel()
    init(model: WalletModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        balance.font = .preferredFont(forTextStyle: .headline)
        address.font = .monospacedSystemFont(ofSize: 13, weight: .regular); address.numberOfLines = 0
        mnemonic.numberOfLines = 0; mnemonic.font = .monospacedSystemFont(ofSize: 15, weight: .medium)
        qr.widthAnchor.constraint(equalToConstant: 240).isActive = true
        qr.heightAnchor.constraint(equalToConstant: 240).isActive = true
        let stack = UIStackView.vertical(spacing: 12, alignment: .center)
        [mnemonic, balance, index, address, qr].forEach(stack.addArrangedSubview)
        let buttons = UIStackView(arrangedSubviews: [
            .plain("Nová adresa") { [weak self] in self?.model.nextAddress(); self?.render() },
            .plain("Obnovit") { [weak self] in self?.model.refresh() },
        ])
        buttons.spacing = 16
        stack.addArrangedSubview(buttons)
        view.pin(stack)
        (view as? MetalSceneView)?.cardViews = [stack]   // SDF karta pod obsahem
        model.$balance.sink { [weak self] b in self?.balance.text = "Zůstatek: \(b) tBTC" }.store(in: &bag)
        model.$newMnemonic.sink { [weak self] w in self?.mnemonic.text = w.map { "Zapište si 12 slov na papír:\n" + $0.joined(separator: " ") } }.store(in: &bag)
        render()
    }

    private func render() {
        index.text = "Adresa #\(model.addressIndex)"
        address.text = model.address
        if let img = QR.cgImage(model.facade.bip21(address: model.address)) { qr.setImage(img) } // nearest-neighbour na GPU
    }
}

// MARK: - Převod
@MainActor
final class TransferViewController: UIViewController {
    override func loadView() { view = MetalSceneView() } // Metal pozadí, UIKit jen text/ovládání
    let model: WalletModel
    private var bag = Set<AnyCancellable>()
    private let to = UITextField(), amount = UITextField(), msg = UILabel.caption(""), preview = UILabel()
    private let confirm = UIStackView.vertical(spacing: 8)
    init(model: WalletModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        to.placeholder = "Adresa tb1…"; to.autocorrectionType = .no; to.autocapitalizationType = .none; to.borderStyle = .roundedRect
        amount.placeholder = "Částka (BTC)"; amount.keyboardType = .decimalPad; amount.borderStyle = .roundedRect
        to.addAction(UIAction { [weak self] _ in self?.validate() }, for: .editingChanged)
        preview.numberOfLines = 0
        let stack = UIStackView.vertical(spacing: 12)
        [UIButton.plain("Skenovat QR") { [weak self] in self?.scan() }, to, msg, amount,
         .filled("Náhled poplatku") { [weak self] in self?.model.previewTransfer(to: self?.to.text ?? "", amount: self?.amount.text ?? "") },
         preview, confirm].forEach(stack.addArrangedSubview)
        if BankIdFlags.enabled { confirm.addArrangedSubview(.plain("Potvrdit výběr přes Bank iD") {}) }
        confirm.addArrangedSubview(.filled("Potvrdit Face ID a odeslat") { [weak self] in self?.model.send() })
        view.pin(stack)
        (view as? MetalSceneView)?.cardViews = [stack]   // SDF karta pod obsahem
        model.$preview.sink { [weak self] p in
            guard let self else { return }
            self.confirm.isHidden = p == nil
            self.preview.text = p.map { "Komu: \($0.destination)\nČástka: \(self.model.facade.formatSats(sats: $0.amountSats)) BTC\nPoplatek: \($0.feeSats) sats (\($0.feeRateSatPerVb) sat/vB)" }
        }.store(in: &bag)
        model.$lastTxid.compactMap { $0 }.sink { [weak self] t in self?.preview.text = "Odesláno: \(t.prefix(12))…" }.store(in: &bag)
    }

    private func validate() {
        let t = to.text ?? ""
        msg.text = t.isEmpty ? nil : model.facade.validate(address: t)
        msg.textColor = .systemRed
    }

    private func scan() {
        let s = QRScannerViewController { [weak self] code in
            guard let self else { return }
            self.dismiss(animated: true)
            guard let r = self.model.facade.parseScanned(raw: code) else { self.model.error = "Neznámý QR"; return }
            let parts = r.dropFirst(4).split(separator: "|").map(String.init)
            if r.hasPrefix("btc:"), let a = parts.first {
                self.to.text = a
                if parts.count > 1, parts[1] != "-", let sats = Int64(parts[1]) { self.amount.text = self.model.facade.formatSats(sats: sats) }
                self.validate()
            } else { self.model.error = "Stellar adresa — podepište v LOBSTR / Freighter (SEP-7)" }
        }
        present(s, animated: true)
    }
}

// MARK: - Historie (+ graf zůstatku na GPU)
@MainActor
final class HistoryViewController: UITableViewController {
    let model: WalletModel
    private var bag = Set<AnyCancellable>()
    private let chart = MetalChartView(frame: CGRect(x: 0, y: 0, width: 320, height: 180))
    init(model: WalletModel) { self.model = model; super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "c")
        tableView.tableHeaderView = chart
        tableView.backgroundView = MetalSceneView()          // pozadí kreslí Metal
        refreshControl = UIRefreshControl()
        refreshControl?.addAction(UIAction { [weak self] _ in self?.model.loadHistory() }, for: .valueChanged)
        model.$history.sink { [weak self] h in
            guard let self else { return }
            self.refreshControl?.endRefreshing(); self.tableView.reloadData()
            // kumulativní zůstatek (od nejstarší) → animovaný přechod grafu
            var acc: Int64 = 0
            let series = h.reversed().map { item -> Float in acc += item.netSats; return Float(acc) / 1e8 }
            #if RUST_CORE
            // Face ID + Secure Enclave → Rust dešifruje → zero-copy MTLBuffer → Metal (jednou za zobrazení)
            if !self.secureShown, !series.isEmpty,
               let sealed = RustCoreVault.shared.seal(field: "chart", series.map { String($0) }.joined(separator: ",")) {
                self.secureShown = true
                SecureRenderFlow.showSealedChart(field: "chart", sealed: sealed, in: self.chart) { [weak self] r in
                    if case .failure = r { self?.chart.setSeries(series) }   // fallback: CPU cesta bez Rustu
                }
                return
            }
            #endif
            self.chart.setSeries(series)
        }.store(in: &bag)
    }
    #if RUST_CORE
    private var secureShown = false
    #endif
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        #if RUST_CORE
        secureShown = false
        #endif
        model.loadHistory()
    }

    override func tableView(_ t: UITableView, numberOfRowsInSection s: Int) -> Int { model.history.count }
    override func tableView(_ t: UITableView, cellForRowAt ip: IndexPath) -> UITableViewCell {
        let c = t.dequeueReusableCell(withIdentifier: "c", for: ip), h = model.history[ip.row]
        var cfg = UIListContentConfiguration.subtitleCell()
        cfg.text = "\(h.netSats > 0 ? "+" : "")\(h.amountText) tBTC"
        cfg.secondaryText = h.confirmations > 0 ? "\(h.confirmations) potvrzení" : "nepotvrzeno"
        c.contentConfiguration = cfg
        return c
    }
    override func tableView(_ t: UITableView, didSelectRowAt ip: IndexPath) {
        t.deselectRow(at: ip, animated: true)
        if let u = URL(string: model.history[ip.row].explorerUrl), u.scheme == "https" { UIApplication.shared.open(u) }
    }
}

// MARK: - Síť
@MainActor
final class NetworkViewController: UIViewController {
    override func loadView() { view = MetalSceneView() } // Metal pozadí, UIKit jen text/ovládání
    let model: WalletModel
    private var bag = Set<AnyCancellable>()
    private let info = UILabel(), mainnet = UISwitch()
    init(model: WalletModel) { self.model = model; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        info.numberOfLines = 0; info.font = .preferredFont(forTextStyle: .body)
        mainnet.addAction(UIAction { [weak self] _ in guard let self else { return }; self.model.infoMainnet = self.mainnet.isOn; self.model.loadNetwork() }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [UILabel.caption("Mainnet data (jen zobrazení)"), mainnet]); row.spacing = 8
        let stack = UIStackView.vertical(spacing: 12)
        [row, info].forEach(stack.addArrangedSubview)
        view.pin(stack)
        (view as? MetalSceneView)?.cardViews = [stack]   // SDF karta pod obsahem
        model.$snapshot.sink { [weak self] s in
            guard let s else { self?.info.text = "Načítám…"; return }
            var t = "Poplatky sat/vB: \(s.fees.fastestFee) · \(s.fees.halfHourFee) · \(s.fees.hourFee)\nMempool: \(s.mempool.count) tx\nVýška bloku: \(s.tipHeight)"
            if let usd = s.prices?.usd { t += "\nBTC: \(Int(usd.doubleValue)) USD (mainnet trh)" }
            for b in s.recentBlocks { t += "\n#\(b.height) · \(b.txCount) tx" }
            self?.info.text = t
        }.store(in: &bag)
        if model.snapshot == nil { model.loadNetwork() }
    }
}

/// Bank iD feature flag – výchozí VYPNUTO. Zrcadlí IdentityFlags.bankIdEnabled ze shared modulu.
enum BankIdFlags { static let enabled = false }

// MARK: - Pomocníci UIKit
extension UIViewController {
    func tab(_ title: String, _ symbol: String) -> UIViewController {
        tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: symbol), tag: 0); return self
    }
}
extension UIView {
    func pin(_ content: UIView) {
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false
        content.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll); scroll.addSubview(content)
        let g = safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: g.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: g.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: g.topAnchor), scroll.bottomAnchor.constraint(equalTo: g.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            content.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            content.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            content.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -20),
            content.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
        ])
    }
}
extension UIStackView {
    static func vertical(spacing: CGFloat, alignment: Alignment = .fill) -> UIStackView {
        let s = UIStackView(); s.axis = .vertical; s.spacing = spacing; s.alignment = alignment; return s
    }
}
extension UIButton {
    static func filled(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        UIButton(configuration: .filled(), primaryAction: UIAction(title: title) { _ in action() })
    }
    static func plain(_ title: String, _ action: @escaping () -> Void) -> UIButton {
        UIButton(configuration: .tinted(), primaryAction: UIAction(title: title) { _ in action() })
    }
}
extension UILabel {
    static func caption(_ t: String) -> UILabel {
        let l = UILabel(); l.text = t; l.font = .preferredFont(forTextStyle: .caption1); l.numberOfLines = 0; return l
    }
}

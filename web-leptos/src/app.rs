use crate::logic::{self, DestCheck, HelpAnswer, Net};
use leptos::prelude::*;
use leptos::task::spawn_local;
use serde::de::DeserializeOwned;
use wasm_bindgen::JsCast;
use wasm_bindgen_futures::JsFuture;

fn storage() -> Option<web_sys::Storage> { web_sys::window()?.local_storage().ok()? }
fn sget(k: &str) -> Option<String> { storage()?.get_item(k).ok()? }
fn sset(k: &str, v: &str) {
    debug_assert!(logic::STORAGE_KEYS.contains(&k));
    if let Some(s) = storage() { let _ = s.set_item(k, v); }
}

async fn get_json<T: DeserializeOwned>(url: String) -> Result<T, String> {
    if !logic::allowed_url(&url) { return Err("URL mimo povolené API".into()); }
    let init = web_sys::RequestInit::new();
    init.set_method("GET");
    init.set_mode(web_sys::RequestMode::Cors);
    init.set_credentials(web_sys::RequestCredentials::Omit);
    let req = web_sys::Request::new_with_str_and_init(&url, &init).map_err(|_| "request")?;
    let w = web_sys::window().ok_or("window")?;
    let resp: web_sys::Response = JsFuture::from(w.fetch_with_request(&req)).await.map_err(|_| "síť")?.dyn_into().map_err(|_| "resp")?;
    if !resp.ok() { return Err(format!("HTTP {}", resp.status())); }
    let text = JsFuture::from(resp.text().map_err(|_| "text")?).await.map_err(|_| "text")?;
    serde_json::from_str(&text.as_string().unwrap_or_default()).map_err(|e| e.to_string())
}

#[derive(Clone, Copy, PartialEq)]
enum View { Home, Receive, Transfer, History, Network, Help, Settings, Security }

#[component]
fn App() -> impl IntoView {
    let (view, set_view) = signal(View::Home);
    let (watch, set_watch) = signal(sget("eleanor.watch").and_then(|s| logic::validate_watch(&s).ok()));
    let go = move |v: View| move |_| set_view.set(v);
    view! {
        <header>
            <h1>"Eleanor " <span class="badge">"testnet"</span></h1>
            <p class="muted">"Jen pro čtení. Žádné klíče ani seed v prohlížeči."</p>
        </header>
        <main>
            {move || match view.get() {
                View::Home => view! { <Home watch=watch go=set_view/> }.into_any(),
                View::Receive => view! { <Receive watch=watch set_watch=set_watch/> }.into_any(),
                View::Transfer => view! { <Transfer/> }.into_any(),
                View::History => view! { <History watch=watch/> }.into_any(),
                View::Network => view! { <Network/> }.into_any(),
                View::Help => view! { <Help/> }.into_any(),
                View::Settings => view! { <Settings go=set_view/> }.into_any(),
                View::Security => view! { <Security go=set_view/> }.into_any(),
            }}
        </main>
        <nav>
            <button on:click=go(View::Home)>"🏠"<span>"Domů"</span></button>
            <button on:click=go(View::Receive)>"📥"<span>"Přijmout"</span></button>
            <button on:click=go(View::History)>"🧾"<span>"Historie"</span></button>
            <button on:click=go(View::Network)>"🌐"<span>"Síť"</span></button>
        </nav>
    }
}

#[component]
fn Home(watch: ReadSignal<Option<String>>, go: WriteSignal<View>) -> impl IntoView {
    let (bal, set_bal) = signal(String::from("–"));
    Effect::new(move |_| {
        if let Some(a) = watch.get() {
            spawn_local(async move {
                let r = get_json::<logic::AddrInfo>(format!("{}/address/{a}", logic::API_TESTNET)).await;
                set_bal.set(r.map(|i| logic::balance_text(&i)).unwrap_or_else(|_| "Nedostupné".into()));
            });
        }
    });
    let tile = move |v: View, icon: &'static str, t: &'static str, s: &'static str| view! {
        <button on:click=move |_| go.set(v)>{icon}<b>{t}</b><span>{s}</span></button>
    };
    view! {
        <section class="view active">
            <div class="card balance"><div class="muted">"Sledovaná adresa"</div><div>{bal}</div>
                <div class="muted small">{move || watch.get().map(|a| logic::short(&a)).unwrap_or_else(|| "Nastavte adresu v Přijmout".into())}</div></div>
            <div class="grid">
                {tile(View::Receive, "📥", "Přijmout", "Adresa a QR")}
                {tile(View::Transfer, "📤", "Převést", "Kontrola adresy")}
                {tile(View::History, "🧾", "Historie", "Transakce")}
                {tile(View::Network, "🌐", "Síť", "Poplatky a bloky")}
                {tile(View::Help, "💬", "Pomocník", "Vysvětlení pojmů")}
                {tile(View::Settings, "⚙️", "Nastavení", "Síť, podmínky")}
            </div>
        </section>
    }
}

#[component]
fn Qr(text: String) -> impl IntoView {
    logic::qr_path(&format!("bitcoin:{text}")).map(|(n, d)| view! {
        <svg class="qr" viewBox=format!("0 0 {n} {n}") role="img" aria-label="QR adresy">
            <rect width=n height=n fill="#fff"/><path d=d fill="#000"/>
        </svg>
    })
}

#[component]
fn Receive(watch: ReadSignal<Option<String>>, set_watch: WriteSignal<Option<String>>) -> impl IntoView {
    let (input, set_input) = signal(watch.get_untracked().unwrap_or_default());
    let (err, set_err) = signal(String::new());
    let save = move |_| match logic::validate_watch(&input.get()) {
        Ok(a) => { sset("eleanor.watch", &a); set_err.set(String::new()); set_watch.set(Some(a)); }
        Err(e) => set_err.set(e),
    };
    view! {
        <section class="view active">
            <h2>"Přijmout"</h2>
            <label>"Vaše adresa (z aplikace Eleanor)"
                <input placeholder="tb1q…" autocomplete="off" autocapitalize="off" spellcheck="false"
                    prop:value=input on:input=move |ev| set_input.set(event_target_value(&ev))/></label>
            <p class="err">{err}</p>
            <button class="primary" on:click=save>"Uložit a zobrazit QR"</button>
            {move || watch.get().map(|a| view! { <Qr text=a/> })}
        </section>
    }
}

#[component]
fn Transfer() -> impl IntoView {
    let (dest, set_dest) = signal(String::new());
    let (vb, set_vb) = signal(String::from("141"));
    let fees = LocalResource::new(|| get_json::<logic::Fees>(format!("{}/v1/fees/recommended", logic::API_TESTNET)));
    view! {
        <section class="view active">
            <h2>"Převést"</h2>
            <p class="muted">"Podepisování probíhá jen v mobilní aplikaci Eleanor (biometrie). Zde si můžete ověřit adresu a odhadnout poplatek."</p>
            <label>"Cílová adresa"<input placeholder="tb1q… nebo bitcoin:…" autocapitalize="off" spellcheck="false"
                on:input=move |ev| set_dest.set(event_target_value(&ev))/></label>
            <p class="small">{move || match logic::check_destination(&dest.get()) {
                DestCheck::Empty => String::new(),
                DestCheck::Ok { address, amount } => format!("✅ Platná testnet adresa {}{}", logic::short(&address), amount.map(|a| format!(" · částka {a} BTC")).unwrap_or_default()),
                DestCheck::Err(e) => format!("❌ {e}"),
            }}</p>
            <label>"Velikost transakce (vB)"<input type="number" prop:value=vb on:input=move |ev| set_vb.set(event_target_value(&ev))/></label>
            <div class="card">{move || match fees.get() {
                None => "Načítám poplatky…".to_string(),
                Some(Err(_)) => "Poplatky nejsou dostupné.".to_string(),
                Some(Ok(f)) => [("Rychle", f.fastest), ("~30 min", f.half_hour), ("~1 h", f.hour)].iter()
                    .map(|(l, r)| format!("{l}: {}", logic::fee_estimate(&vb.get(), *r).unwrap_or_else(|| "–".into()))).collect::<Vec<_>>().join(" · "),
            }}</div>
        </section>
    }
}

#[component]
fn History(watch: ReadSignal<Option<String>>) -> impl IntoView {
    let txs = LocalResource::new(move || { let a = watch.get(); async move {
        match a { Some(a) => get_json::<Vec<logic::Tx>>(format!("{}/address/{a}/txs", logic::API_TESTNET)).await.map(Some), None => Ok(None) }
    }});
    view! {
        <section class="view active"><h2>"Historie"</h2>
        {move || match txs.get() {
            None => view! { <div class="empty">"Načítám…"</div> }.into_any(),
            Some(Ok(None)) => view! { <div class="empty">"Nejprve nastavte adresu v Přijmout."</div> }.into_any(),
            Some(Err(_)) => view! { <div class="empty">"Historie není dostupná."</div> }.into_any(),
            Some(Ok(Some(v))) if v.is_empty() => view! { <div class="empty">"Žádné transakce."</div> }.into_any(),
            Some(Ok(Some(v))) => v.into_iter().take(25).map(|t| {
                let st = if t.status.confirmed { format!("potvrzeno #{}", t.status.block_height.unwrap_or(0)) } else { "nepotvrzeno".into() };
                view! { <div class="row"><a href=logic::tx_link(&t.txid) rel="noopener noreferrer" target="_blank">{logic::short(&t.txid)}</a><span class="small muted">{st}</span></div> }
            }).collect_view().into_any(),
        }}
        </section>
    }
}

#[derive(serde::Deserialize, Clone)]
struct Mempool { count: u64, vsize: u64 }
#[derive(serde::Deserialize, Clone)]
struct Block { height: u64, tx_count: u64 }

#[component]
fn Network() -> impl IntoView {
    let (net, set_net) = signal(Net::Testnet);
    let data = LocalResource::new(move || { let b = net.get().api(); async move {
        let f = get_json::<logic::Fees>(format!("{b}/v1/fees/recommended")).await?;
        let m = get_json::<Mempool>(format!("{b}/mempool")).await?;
        let bl = get_json::<Vec<Block>>(format!("{b}/v1/blocks")).await?;
        Ok::<_, String>((f, m, bl))
    }});
    view! {
        <section class="view active"><h2>"Síť"</h2>
        <div class="seg">
            <button class:on=move || net.get() == Net::Testnet on:click=move |_| set_net.set(Net::Testnet)>"testnet"</button>
            <button class:on=move || net.get() == Net::Mainnet on:click=move |_| set_net.set(Net::Mainnet)>"mainnet (jen zobrazení)"</button>
        </div>
        {move || match data.get() {
            None => view! { <div class="empty">"Načítám…"</div> }.into_any(),
            Some(Err(_)) => view! { <div class="empty">"Síťová data nejsou dostupná."</div> }.into_any(),
            Some(Ok((f, m, bl))) => view! {
                <div class="card">
                    <div class="row"><span>"Poplatky (sat/vB)"</span><b>{format!("{} · {} · {}", f.fastest, f.half_hour, f.hour)}</b></div>
                    <div class="row"><span>"Mempool"</span><b>{format!("{} tx · {:.1} MvB", m.count, m.vsize as f64 / 1e6)}</b></div>
                    <div class="row"><span>"Výška bloku"</span><b>{bl.first().map(|b| b.height).unwrap_or(0)}</b></div>
                </div>
                <h3>"Poslední bloky"</h3>
                {bl.into_iter().take(6).map(|b| view! { <div class="row"><span>{format!("#{}", b.height)}</span><span class="small muted">{format!("{} tx", b.tx_count)}</span></div> }).collect_view()}
            }.into_any(),
        }}
        </section>
    }
}

#[component]
fn Help() -> impl IntoView {
    let (q, set_q) = signal(String::new());
    view! {
        <section class="view active"><h2>"Pomocník"</h2>
        <p class="muted">"Offline slovníček. Asistent nikdy nepodepisuje ani nevidí klíče."</p>
        <input placeholder="Např. poplatek, mempool, seed…" on:input=move |ev| set_q.set(event_target_value(&ev))/>
        {move || match logic::help(&q.get()) {
            HelpAnswer::None => ().into_any(),
            HelpAnswer::Warning => view! { <div class="card err">"⚠️ Seed ani klíče nikdy nikam nezadávejte. Tohle je typický podvod."</div> }.into_any(),
            HelpAnswer::NotFound => view! { <div class="empty">"Nenalezeno."</div> }.into_any(),
            HelpAnswer::Hits(h) => h.into_iter().map(|(k, v)| view! { <div class="card"><b>{k}</b><br/>{v}</div> }).collect_view().into_any(),
        }}
        </section>
    }
}

#[component]
fn Settings(go: WriteSignal<View>) -> impl IntoView {
    let clear = move |_| {
        if let Some(s) = storage() { for k in logic::STORAGE_KEYS { let _ = s.remove_item(k); } }
        if let Some(w) = web_sys::window() { let _ = w.location().reload(); }
    };
    view! {
        <section class="view active"><h2>"Nastavení"</h2>
        <div class="card"><p>"Výchozí síť: " <b>"testnet"</b> ". Mainnet jen pro zobrazení dat."</p>
            <p class="small">"Data: veřejné API mempool.space. Adresa je uložena jen v tomto prohlížeči (localStorage)."</p>
            <button on:click=clear>"Smazat uložená data"</button></div>
        <crate::secure_login::SecureLogin/>
        <div class="card"><button on:click=move |_| go.set(View::Security)>"🛡️ Bezpečnostní kontrola"</button></div>
        <div class="card small"><b>"O aplikaci:"</b> " © 2026 Ivo Faltýn — autor, vývojář a vlastník projektu ELFALL / Fallcoin / Eleanor (GitHub "
            <a href="https://github.com/ivosicek123" rel="noopener noreferrer">"@ivosicek123"</a> ")."</div>
        <div class="card small"><b>"Instalace na plochu iPhonu:"</b> " Safari → Sdílet → „Přidat na plochu“."</div>
        </section>
    }
}

#[component]
fn Security(go: WriteSignal<View>) -> impl IntoView {
    let (state, set_state) = signal(logic::parse_checklist(sget("elfall.securityChecklist").as_deref()));
    view! {
        <section class="view active"><h2>"Bezpečnostní kontrola"</h2>
        <p class="muted">"Kontrolní seznam pro zařízení a účty. Stav se ukládá jen v tomto prohlížeči."</p>
        <ol class="card">
        {logic::SECURITY_ITEMS.iter().enumerate().map(|(i, (t, d))| view! {
            <li><label><input type="checkbox" prop:checked=move || state.get()[i] on:change=move |ev| {
                let on = event_target_checked(&ev);
                set_state.update(|s| s[i] = on);
                sset("elfall.securityChecklist", &serde_json::to_string(&state.get_untracked()).unwrap_or_default());
            }/> " " <b>{*t}</b><br/><span class="small">{*d}</span></label></li>
        }).collect_view()}
        </ol>
        <p class="small">{move || format!("Splněno {} z {}.", state.get().iter().filter(|b| **b).count(), logic::SECURITY_ITEMS.len())}</p>
        <button on:click=move |_| go.set(View::Settings)>"Zpět do nastavení"</button>
        </section>
    }
}

pub fn mount() {
    leptos::mount::mount_to_body(App);
    if let Some(w) = web_sys::window() { let _ = w.navigator().service_worker().register("sw.js"); }
}

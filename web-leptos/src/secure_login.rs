//! SecureLogin: registrace a přihlášení passkey přes `navigator.credentials` (web-sys).
//! Tok: POST /passkey/*/options (server vydá challenge) → kontrola politiky v Rustu →
//! create()/get() v autentizátoru (biometrie) → POST /passkey/*/verify (ověří backend).
//! Žádné tajemství v prohlížeči; session drží backend (HttpOnly cookie), ne JS/localStorage.
use crate::passkey::{self, LoginBody, RegisterBody};
use js_sys::{Array, Object, Reflect, Uint8Array};
use leptos::prelude::*;
use leptos::task::spawn_local;
use wasm_bindgen::{JsCast, JsValue};
use wasm_bindgen_futures::JsFuture;

/// Backend je na stejném originu (connect-src 'self'); reverzní proxy směruje /passkey/*.
const BASE: &str = "/passkey";

fn set(o: &Object, k: &str, v: impl Into<JsValue>) { let _ = Reflect::set(o, &k.into(), &v.into()); }
fn bytes(b: &[u8]) -> JsValue { Uint8Array::from(b).buffer().into() }
fn buf(v: &JsValue, k: &str) -> Result<Vec<u8>, String> {
    let x = Reflect::get(v, &k.into()).map_err(|_| format!("chybí {k}"))?;
    if x.is_null() || x.is_undefined() { return Err(format!("chybí {k}")); }
    Ok(Uint8Array::new(&x).to_vec())
}
fn creds(list: &[Vec<u8>]) -> Array {
    list.iter().map(|id| { let o = Object::new(); set(&o, "type", "public-key"); set(&o, "id", bytes(id)); JsValue::from(o) }).collect()
}

async fn post(path: &str, body: String) -> Result<String, String> {
    let init = web_sys::RequestInit::new();
    init.set_method("POST");
    init.set_credentials(web_sys::RequestCredentials::SameOrigin);
    init.set_body(&JsValue::from_str(&body));
    let h = web_sys::Headers::new().map_err(|_| "headers")?;
    h.set("Content-Type", "application/json").map_err(|_| "headers")?;
    init.set_headers(&h);
    let req = web_sys::Request::new_with_str_and_init(&format!("{BASE}{path}"), &init).map_err(|_| "request")?;
    let w = web_sys::window().ok_or("window")?;
    let r: web_sys::Response = JsFuture::from(w.fetch_with_request(&req)).await.map_err(|_| "síť")?.dyn_into().map_err(|_| "resp")?;
    if !r.ok() { return Err(format!("backend {}", r.status())); }
    Ok(JsFuture::from(r.text().map_err(|_| "text")?).await.map_err(|_| "text")?.as_string().unwrap_or_default())
}

fn host() -> Result<String, String> { web_sys::window().ok_or("window")?.location().hostname().map_err(|_| "host".into()) }
fn container() -> Result<web_sys::CredentialsContainer, String> { Ok(web_sys::window().ok_or("window")?.navigator().credentials()) }

async fn register(user: String) -> Result<String, String> {
    let raw = post("/register/options", serde_json::json!({ "user": user }).to_string()).await?;
    let o: passkey::CreationJson = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
    let c = passkey::creation(&o, &host()?)?;
    let pk = Object::new();
    set(&pk, "challenge", bytes(&c.challenge));
    let rp = Object::new(); set(&rp, "id", c.rp_id.as_str()); set(&rp, "name", c.rp_name.as_str()); set(&pk, "rp", rp);
    let u = Object::new(); set(&u, "id", bytes(&c.user_id)); set(&u, "name", c.user_name.as_str()); set(&u, "displayName", c.display_name.as_str()); set(&pk, "user", u);
    let params: Array = c.algs.iter().map(|a| { let p = Object::new(); set(&p, "type", "public-key"); set(&p, "alg", *a as f64); JsValue::from(p) }).collect();
    set(&pk, "pubKeyCredParams", params);
    set(&pk, "timeout", c.timeout);
    set(&pk, "attestation", "none");
    let sel = Object::new(); set(&sel, "residentKey", "required"); set(&sel, "userVerification", "required"); set(&pk, "authenticatorSelection", sel);
    set(&pk, "excludeCredentials", creds(&c.exclude));
    let opts = web_sys::CredentialCreationOptions::new(); set(opts.as_ref(), "publicKey", pk);
    let cred = JsFuture::from(container()?.create_with_options(&opts).map_err(|_| "create")?).await.map_err(|_| "Registrace zrušena")?;
    let resp = Reflect::get(&cred, &"response".into()).map_err(|_| "response")?;
    let body = RegisterBody { challenge: o.challenge, attestation_object: passkey::to_b64u(&buf(&resp, "attestationObject")?), client_data_json: passkey::to_b64u(&buf(&resp, "clientDataJSON")?) };
    post("/register/verify", serde_json::to_string(&body).map_err(|e| e.to_string())?).await?;
    Ok("Passkey zaregistrován".into())
}

async fn login(user: Option<String>) -> Result<String, String> {
    let req = user.map(|u| serde_json::json!({ "user": u })).unwrap_or_else(|| serde_json::json!({}));
    let o: passkey::RequestJson = serde_json::from_str(&post("/login/options", req.to_string()).await?).map_err(|e| e.to_string())?;
    let r = passkey::request(&o, &host()?)?;
    let pk = Object::new();
    set(&pk, "challenge", bytes(&r.challenge)); set(&pk, "rpId", r.rp_id.as_str()); set(&pk, "timeout", r.timeout);
    set(&pk, "userVerification", "required"); set(&pk, "allowCredentials", creds(&r.allow));
    let opts = web_sys::CredentialRequestOptions::new(); set(opts.as_ref(), "publicKey", pk);
    let cred = JsFuture::from(container()?.get_with_options(&opts).map_err(|_| "get")?).await.map_err(|_| "Přihlášení zrušeno")?;
    let resp = Reflect::get(&cred, &"response".into()).map_err(|_| "response")?;
    let body = LoginBody {
        challenge: o.challenge, credential_id: passkey::to_b64u(&buf(&cred, "rawId")?),
        authenticator_data: passkey::to_b64u(&buf(&resp, "authenticatorData")?), client_data_json: passkey::to_b64u(&buf(&resp, "clientDataJSON")?),
        signature: passkey::to_b64u(&buf(&resp, "signature")?), user_handle: buf(&resp, "userHandle").ok().map(|b| passkey::to_b64u(&b)),
    };
    let out = post("/login/verify", serde_json::to_string(&body).map_err(|e| e.to_string())?).await?;
    let v: serde_json::Value = serde_json::from_str(&out).map_err(|e| e.to_string())?;
    Ok(format!("Přihlášen: {}", v.get("user").and_then(|u| u.as_str()).unwrap_or("?")))
}

#[component]
pub fn SecureLogin() -> impl IntoView {
    let (user, set_user) = signal(String::new());
    let (msg, set_msg) = signal(String::new());
    let (busy, set_busy) = signal(false);
    let run = move |f: bool| {
        set_busy.set(true);
        let u = user.get_untracked();
        spawn_local(async move {
            let r = if f { match passkey::valid_user(&u) { Ok(u) => register(u).await, Err(e) => Err(e) } }
                    else { login(passkey::valid_user(&u).ok()).await };
            // text se vkládá jako textový uzel (Leptos escapuje), nikdy jako HTML
            set_msg.set(r.unwrap_or_else(|e| format!("Chyba: {e}")));
            set_busy.set(false);
        });
    };
    view! {
        <div class="card">
            <p><b>"Přihlášení passkey"</b></p>
            <label>"Jméno (volitelné pro přihlášení)"<input autocomplete="username webauthn" prop:value=user on:input=move |ev| set_user.set(event_target_value(&ev))/></label>
            <button class="primary" prop:disabled=busy on:click=move |_| run(false)>"Přihlásit passkey"</button>
            <button prop:disabled=busy on:click=move |_| run(true)>"Zaregistrovat passkey"</button>
            <p class="small">{msg}</p>
            <p class="small muted">"Klíč zůstává v zařízení (biometrie). Prohlížeč neukládá žádné tajemství."</p>
        </div>
    }
}

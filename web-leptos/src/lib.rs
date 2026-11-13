//! Eleanor PWA v Leptos (CSR, bez SSR serveru). Jen čtení: žádné klíče, seed ani tokeny v prohlížeči.
//! Validace a formátování z `elfall-core`. Síť jen na veřejné API povolené v CSP (`logic::allowed_url`).
#![deny(unsafe_code)]
pub mod logic;
pub mod passkey;
#[cfg(target_arch = "wasm32")]
mod secure_login;

#[cfg(target_arch = "wasm32")]
mod app;

#[cfg(target_arch = "wasm32")]
#[wasm_bindgen::prelude::wasm_bindgen(start)]
pub fn start() { app::mount(); }

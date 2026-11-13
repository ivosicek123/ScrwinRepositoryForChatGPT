//! Zero-copy předání dat Rust → Metal.
//!
//! Rust alokuje **stránkově zarovnaný** buffer (délka zaokrouhlená na násobek stránky –
//! požadavek `MTLDevice.makeBuffer(bytesNoCopy:length:options:deallocator:)`), zapíše do něj
//! zpracovaná data a předá vlastnictví přes C ABI (`ElfallGpuBuffer`: ptr + len + capacity).
//! Uvolní se **jen** `elfall_gpu_buffer_free(ptr, capacity)` – ve Swiftu z `deallocator`
//! MTLBufferu, tj. až Metal (a všechny command buffery) buffer pustí. Registr živých alokací
//! odmítne dvojí uvolnění i cizí ukazatel (vrátí chybu místo UB). Paměť se před uvolněním nuluje.
//!
//! Formát grafu (`ELFALL-GPU-CHART-v1`): `n` bodů `float2` (x ∈ [0,1], y ∈ [0,1]) pro lineStrip,
//! hned za nimi `2n` vrcholů triangleStrip výplně (bod, (x, 0)). Stride 8 B = `SIMD2<Float>`.
#![allow(unsafe_code)]
use crate::{vault, CoreError};
use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::collections::BTreeMap;
use std::sync::Mutex;

static LIVE: Mutex<BTreeMap<usize, usize>> = Mutex::new(BTreeMap::new());

/// Max. počet bodů grafu (ochrana před obří alokací).
pub const MAX_POINTS: usize = 4096;
/// Min. stránka: iOS/arm64 má 16 KiB; větší hodnota je vždy násobkem 4 KiB.
const MIN_PAGE: usize = 16 * 1024;

#[cfg(not(miri))]
extern "C" {
    fn getpagesize() -> std::os::raw::c_int;
}

#[cfg(miri)]
pub fn page_size() -> usize { MIN_PAGE } // Miri: bez FFI

#[cfg(not(miri))]
pub fn page_size() -> usize {
    // SAFETY: getpagesize nemá vstupy ani vedlejší účinky (POSIX, Linux i Darwin).
    let p = unsafe { getpagesize() };
    let p = if p > 0 { p as usize } else { MIN_PAGE };
    p.max(MIN_PAGE)
}

/// Vlastněný buffer na straně Rustu; `into_raw` předá vlastnictví přes C ABI.
pub struct PageBuffer { ptr: *mut u8, len: usize, cap: usize }

// SAFETY: ukazatel je výhradně vlastněn tímto objektem (žádné sdílené aliasy), přesun mezi vlákny je bezpečný.
unsafe impl Send for PageBuffer {}

impl PageBuffer {
    pub fn new(len: usize) -> Result<Self, CoreError> {
        let page = page_size();
        if len == 0 { return Err(CoreError::InvalidLength); }
        let cap = len.checked_add(page - 1).ok_or(CoreError::InvalidLength)? / page * page;
        let layout = Layout::from_size_align(cap, page).map_err(|_| CoreError::InvalidLength)?;
        // SAFETY: layout má nenulovou velikost (len > 0, cap ≥ page) a platné zarovnání (mocnina 2).
        let ptr = unsafe { alloc_zeroed(layout) };
        if ptr.is_null() { return Err(CoreError::Rng); }
        LIVE.lock().map_err(|_| CoreError::Rng)?.insert(ptr as usize, cap);
        Ok(Self { ptr, len, cap })
    }
    pub fn len(&self) -> usize { self.len }
    pub fn is_empty(&self) -> bool { self.len == 0 }
    pub fn capacity(&self) -> usize { self.cap }
    pub fn as_ptr(&self) -> *const u8 { self.ptr }
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        // SAFETY: ptr je živá alokace ≥ len bajtů vlastněná self; &mut self zaručuje výlučný přístup.
        unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
    }
    pub fn as_slice(&self) -> &[u8] {
        // SAFETY: ptr je živá alokace ≥ len bajtů; výpůjčka je svázána s &self.
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }
    pub fn into_raw(self) -> ElfallGpuBuffer {
        let r = ElfallGpuBuffer { ptr: self.ptr, len: self.len, capacity: self.cap };
        std::mem::forget(self);
        r
    }
}

impl Drop for PageBuffer {
    fn drop(&mut self) { let _ = free_raw(self.ptr, self.cap); }
}

fn free_raw(ptr: *mut u8, cap: usize) -> Result<(), CoreError> {
    let mut live = LIVE.lock().map_err(|_| CoreError::Rng)?;
    match live.get(&(ptr as usize)) {
        Some(&c) if c == cap => {}
        _ => return Err(CoreError::InvalidKey), // cizí ukazatel, špatná délka nebo dvojí free
    }
    live.remove(&(ptr as usize));
    drop(live);
    let page = page_size();
    // SAFETY: (ptr, cap) byl v registru → pochází z `PageBuffer::new` s Layout(cap, page) a ještě nebyl uvolněn;
    // z registru je odstraněn před dealloc, takže dvojí uvolnění není možné.
    unsafe {
        wipe(std::slice::from_raw_parts_mut(ptr, cap));
        dealloc(ptr, Layout::from_size_align_unchecked(cap, page));
    }
    Ok(())
}

#[cfg(not(miri))]
fn wipe(b: &mut [u8]) { crate::sodium::memzero(b) }
#[cfg(miri)]
fn wipe(b: &mut [u8]) { zeroize::Zeroize::zeroize(b) } // Miri neumí FFI do libsodium

pub fn live_allocations() -> usize { LIVE.lock().map(|l| l.len()).unwrap_or(usize::MAX) }

/// Bajtů potřebných pro graf s `points` body (n lineStrip + 2n výplň, float2).
pub fn chart_bytes(points: usize) -> Result<usize, CoreError> {
    if !(2..=MAX_POINTS).contains(&points) { return Err(CoreError::InvalidLength); }
    Ok(points * 3 * 8)
}

/// Zpracování do **vypůjčeného** cíle (`&mut [u8]` žije jen po dobu volání) – bezpečný Rust.
/// Převzorkování na `points` bodů a normalizace do [0,1]². `out` musí mít přesně `chart_bytes(points)`
/// nebo víc (zbytek se nechá být).
pub fn write_chart(values: &[f32], points: usize, out: &mut [u8]) -> Result<usize, CoreError> {
    let need = chart_bytes(points)?;
    let out = out.get_mut(..need).ok_or(CoreError::InvalidLength)?;
    if values.iter().any(|v| !v.is_finite()) { return Err(CoreError::BadFrame); }
    let v: Vec<f32> = match values.len() { 0 => vec![0.0, 0.0], 1 => vec![values[0], values[0]], _ => values.to_vec() };
    let (lo, hi) = v.iter().fold((f32::INFINITY, f32::NEG_INFINITY), |(a, b), &x| (a.min(x), b.max(x)));
    let flat = hi - lo <= 1e-9;
    let mut put = |i: usize, x: f32, y: f32| {
        let c = &mut out[i * 8..i * 8 + 8];
        c[..4].copy_from_slice(&x.to_ne_bytes());
        c[4..].copy_from_slice(&y.to_ne_bytes());
    };
    for i in 0..points {
        let x = i as f32 / (points - 1) as f32;
        let t = x * (v.len() - 1) as f32;
        let k = (t.floor() as usize).min(v.len() - 2);
        let y = v[k] + (v[k + 1] - v[k]) * (t - k as f32);
        let y = if flat { 0.5 } else { (y - lo) / (hi - lo) };
        put(i, x, y);
        put(points + 2 * i, x, y);
        put(points + 2 * i + 1, x, 0.0);
    }
    Ok(need)
}

/// Zpracování do nového Rust-vlastněného stránkově zarovnaného bufferu.
pub fn chart_vertices(values: &[f32], points: usize) -> Result<PageBuffer, CoreError> {
    let mut buf = PageBuffer::new(chart_bytes(points)?)?;
    write_chart(values, points, buf.as_mut_slice())?;
    Ok(buf)
}

/// Vypůjčený pohled na data (C ABI): platí jen dokud žije `&'a PageBuffer` – kompilátor brání
/// použití po uvolnění v Rustu; přes C ABI to hlídá registr (`elfall_gpu_buffer_free`).
#[repr(C)]
pub struct GpuSlice<'a> { pub ptr: *const u8, pub len: usize, _borrow: std::marker::PhantomData<&'a [u8]> }

impl PageBuffer {
    pub fn view(&self) -> GpuSlice<'_> { GpuSlice { ptr: self.ptr, len: self.len, _borrow: std::marker::PhantomData } }
}

impl<'a> GpuSlice<'a> {
    pub fn as_slice(&self) -> &'a [u8] {
        // SAFETY: vzniká jen z `PageBuffer::view`; `'a` je svázáno s výpůjčkou bufferu, ptr/len platné.
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }
}

/// Vault (`sv1:`, pole jako AD) → čísla oddělená čárkou → vrcholy. Plaintext se po použití nuluje.
pub fn chart_from_sealed(key: &[u8], field: &str, sealed: &str, points: usize) -> Result<PageBuffer, CoreError> {
    let pt = vault::open(key, field, sealed)?;
    let vals: Result<Vec<f32>, _> = pt.split(',').filter(|s| !s.trim().is_empty()).map(|s| s.trim().parse::<f32>()).collect();
    let vals = zeroize::Zeroizing::new(vals.map_err(|_| CoreError::BadFrame)?);
    chart_vertices(&vals, points)
}

// ---------------- C ABI ----------------
#[repr(C)]
pub struct ElfallGpuBuffer { pub ptr: *mut u8, pub len: usize, pub capacity: usize }

const EMPTY: ElfallGpuBuffer = ElfallGpuBuffer { ptr: std::ptr::null_mut(), len: 0, capacity: 0 };

fn code(e: CoreError) -> i32 { -(1 + e as i32) }

unsafe fn bytes<'a>(p: *const u8, n: usize) -> Result<&'a [u8], CoreError> {
    if n == 0 { return Ok(&[]); }
    if p.is_null() { return Err(CoreError::InvalidLength); }
    Ok(std::slice::from_raw_parts(p, n))
}

fn finish(out: *mut ElfallGpuBuffer, r: Result<PageBuffer, CoreError>) -> i32 {
    if out.is_null() { return code(CoreError::InvalidLength); } // r se zahodí a uvolní
    match r {
        // SAFETY: invarianty modulu (viz hlavička) – ukazatele z živých Rust bufferů, délky ověřené typem/kontrolou výše.
        Ok(b) => {
            // SAFETY: viz níže – out není null a je zapisovatelný.
            unsafe { out.write(b.into_raw()) };
            0
        }
        Err(e) => {
            // SAFETY: out není null (kontrola výše) a podle kontraktu C ABI ukazuje na zapisovatelný ElfallGpuBuffer.
            unsafe { out.write(EMPTY) };
            code(e)
        }
    }
}

#[no_mangle]
pub extern "C" fn elfall_gpu_page_size() -> usize { page_size() }

/// # Safety
/// `values` ukazuje na `n` f32 (nebo n = 0), `out` na zapisovatelný `ElfallGpuBuffer`.
#[no_mangle]
pub unsafe extern "C" fn elfall_gpu_chart_from_values(values: *const f32, n: usize, points: u32, out: *mut ElfallGpuBuffer) -> i32 {
    let r = (|| {
        let v: &[f32] = if n == 0 { &[] } else if values.is_null() { return Err(CoreError::InvalidLength) } else { std::slice::from_raw_parts(values, n) };
        chart_vertices(v, points as usize)
    })();
    finish(out, r)
}

/// # Safety
/// Ukazatele platné pro uvedené délky; `field`/`sealed` UTF-8; `out` zapisovatelný.
#[no_mangle]
pub unsafe extern "C" fn elfall_gpu_chart_from_sealed(key: *const u8, key_len: usize, field: *const u8, field_len: usize,
    sealed: *const u8, sealed_len: usize, points: u32, out: *mut ElfallGpuBuffer) -> i32 {
    let r = (|| {
        let k = bytes(key, key_len)?;
        let f = std::str::from_utf8(bytes(field, field_len)?).map_err(|_| CoreError::BadFrame)?;
        let s = std::str::from_utf8(bytes(sealed, sealed_len)?).map_err(|_| CoreError::BadFrame)?;
        chart_from_sealed(k, f, s, points as usize)
    })();
    finish(out, r)
}

/// Jediný způsob uvolnění. 0 = ok; záporné = neznámý ukazatel / špatná capacity / dvojí free (nic se neuvolní).
///
/// # Safety
/// `ptr` musí pocházet z `ElfallGpuBuffer` této knihovny a po úspěchu se už nesmí použít.
#[no_mangle]
pub unsafe extern "C" fn elfall_gpu_buffer_free(ptr: *mut std::os::raw::c_void, capacity: usize) -> i32 {
    if ptr.is_null() { return 0; }
    match free_raw(ptr.cast(), capacity) { Ok(()) => 0, Err(e) => code(e) }
}

/// Zápis do **paměti volajícího** (např. `MTLBuffer.contents()` alokovaný Metalem): Rust si ji jen
/// vypůjčí na dobu volání (`&mut [u8]`), nic si neuloží. Vrací počet zapsaných bajtů, nebo záporný kód.
///
/// # Safety
/// `out` ukazuje na `out_len` zapisovatelných bajtů, které po dobu volání nikdo jiný nečte ani nepíše
/// (GPU je nesmí používat – volající čeká na dokončení command bufferu nebo používá nový buffer).
#[no_mangle]
pub unsafe extern "C" fn elfall_gpu_chart_write(values: *const f32, n: usize, points: u32, out: *mut u8, out_len: usize) -> isize {
    if out.is_null() { return code(CoreError::InvalidLength) as isize; }
    let v: &[f32] = if n == 0 { &[] } else if values.is_null() { return code(CoreError::InvalidLength) as isize } else { std::slice::from_raw_parts(values, n) };
    let o = std::slice::from_raw_parts_mut(out, out_len);
    match write_chart(v, points as usize, o) { Ok(w) => w as isize, Err(e) => code(e) as isize }
}

#[no_mangle]
pub extern "C" fn elfall_gpu_chart_bytes(points: u32) -> usize { chart_bytes(points as usize).unwrap_or(0) }

#[no_mangle]
pub extern "C" fn elfall_gpu_live_allocations() -> usize { live_allocations() }

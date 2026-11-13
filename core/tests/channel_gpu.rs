#![allow(clippy::undocumented_unsafe_blocks)] // testy záměrně volají C ABI s neplatnými vstupy
static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());
use elfall_core::{channel::{self, Channel, Keypair}, gpu, vault, CoreError};

fn pair() -> (Channel, Channel) {
    let srv = Keypair::generate().unwrap();
    let cli = Keypair::generate().unwrap();
    let mut c = Channel::client(&cli, &srv.public).unwrap();
    let mut s = Channel::server(&srv, &cli.public).unwrap();
    let (hc, hs) = (c.header(), s.header());
    s.accept_header(&hc).unwrap();
    c.accept_header(&hs).unwrap();
    (c, s)
}

#[test]
fn channel_roundtrip_both_directions_and_binding() {
    let (mut c, mut s) = pair();
    assert_eq!(c.binding(), s.binding());
    let token = b"eyJ...short-lived-device-bound";
    let (m, last) = s.open(&c.seal(token, false).unwrap()).unwrap();
    assert_eq!(&m[..], token); assert!(!last);
    let (m, last) = c.open(&s.seal(b"ok", true).unwrap()).unwrap();
    assert_eq!(&m[..], b"ok"); assert!(last);
    assert_eq!(c.open(&[0u8; 40]).unwrap_err(), CoreError::BadFrame, "po FINAL nic dalšího");
    assert_eq!(s.seal(b"x", false).unwrap_err(), CoreError::BadFrame, "odchozí směr uzavřen");
}

#[test]
fn channel_rejects_tamper_replay_reorder() {
    let (mut c, mut s) = pair();
    let a = c.seal(b"a", false).unwrap();
    let b = c.seal(b"b", false).unwrap();
    let mut t = a.clone(); t[5] ^= 1;
    assert_eq!(s.open(&t).unwrap_err(), CoreError::AuthFailed);
    assert_eq!(s.open(&b).unwrap_err(), CoreError::AuthFailed, "přeskupení");
    assert_eq!(&s.open(&a).unwrap().0[..], b"a");
    assert_eq!(s.open(&a).unwrap_err(), CoreError::AuthFailed, "přehrání");
    assert_eq!(&s.open(&b).unwrap().0[..], b"b");
}

#[test]
fn channel_mitm_wrong_server_key_fails() {
    let real = Keypair::generate().unwrap();
    let mitm = Keypair::generate().unwrap();
    let cli = Keypair::generate().unwrap();
    let mut c = Channel::client(&cli, &real.public).unwrap(); // připnutý pravý klíč
    let mut m = Channel::server(&mitm, &cli.public).unwrap();
    m.accept_header(&c.header()).unwrap();
    assert_eq!(m.open(&c.seal(b"token", false).unwrap()).unwrap_err(), CoreError::AuthFailed);
    assert_ne!(c.binding(), channel::binding(&cli.public, &mitm.public).unwrap());
}

#[test]
fn channel_input_validation() {
    let k = Keypair::generate().unwrap();
    assert!(Channel::client(&k, &[1u8; 31]).is_err());
    assert!(Channel::client(&k, &[0u8; 32]).is_err(), "low-order klíč odmítnut");
    let (mut c, _) = pair();
    assert_eq!(c.accept_header(&[0u8; 24]).unwrap_err(), CoreError::BadFrame, "hlavička jen jednou");
    assert_eq!(c.seal(&vec![0u8; channel::MAX_MESSAGE + 1], false).unwrap_err(), CoreError::MessageTooLong);
    let kp2 = Keypair::from_secret(&k.secret[..]).unwrap();
    assert_eq!(kp2.public, k.public);
}

#[test]
fn gpu_buffer_is_page_aligned_and_freed_once() {
    let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let page = gpu::page_size();
    assert!(page >= 16384 && page.is_power_of_two());
    let before = gpu::live_allocations();
    let b = gpu::chart_vertices(&[1.0, 3.0, 2.0], 64).unwrap();
    assert_eq!(b.as_ptr() as usize % page, 0);
    assert_eq!(b.capacity() % page, 0);
    assert_eq!(b.len(), 64 * 3 * 8);
    let raw = b.into_raw();
    unsafe {
        assert_eq!(gpu::elfall_gpu_buffer_free(raw.ptr.cast(), raw.capacity + 1), -(1 + CoreError::InvalidKey as i32), "špatná délka");
        assert_eq!(gpu::elfall_gpu_buffer_free(raw.ptr.cast(), raw.capacity), 0);
        assert!(gpu::elfall_gpu_buffer_free(raw.ptr.cast(), raw.capacity) < 0, "dvojí free odmítnuto");
        let mut x = 0u8;
        assert!(gpu::elfall_gpu_buffer_free((&mut x as *mut u8).cast(), page) < 0, "cizí ukazatel");
    }
    assert_eq!(gpu::live_allocations(), before);
}

fn f2(b: &[u8], i: usize) -> (f32, f32) {
    (f32::from_ne_bytes(b[i * 8..i * 8 + 4].try_into().unwrap()), f32::from_ne_bytes(b[i * 8 + 4..i * 8 + 8].try_into().unwrap()))
}

#[test]
fn gpu_chart_layout_from_sealed_vault() {
    let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let key = [0x42u8; 32];
    let sealed = vault::seal(&key, "chart", "10, 20,15").unwrap();
    let b = gpu::chart_from_sealed(&key, "chart", &sealed, 3).unwrap();
    let s = b.as_slice();
    assert_eq!(f2(s, 0), (0.0, 0.0));
    assert_eq!(f2(s, 1), (0.5, 1.0));
    assert_eq!(f2(s, 2), (1.0, 0.5));
    assert_eq!(f2(s, 3), (0.0, 0.0)); assert_eq!(f2(s, 4), (0.0, 0.0));
    assert_eq!(f2(s, 5), (0.5, 1.0)); assert_eq!(f2(s, 6), (0.5, 0.0));
    assert_eq!(gpu::chart_from_sealed(&key, "jine", &sealed, 3).err(), Some(CoreError::AuthFailed));
    assert_eq!(gpu::chart_from_sealed(&[1u8; 32], "chart", &sealed, 3).err(), Some(CoreError::AuthFailed));
    let bad = vault::seal(&key, "chart", "1,abc").unwrap();
    assert_eq!(gpu::chart_from_sealed(&key, "chart", &bad, 3).err(), Some(CoreError::BadFrame));
}

#[test]
fn gpu_c_abi_errors_leave_empty_out() {
    let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let mut out = gpu::ElfallGpuBuffer { ptr: std::ptr::dangling_mut::<u8>(), len: 1, capacity: 1 };
    let v = [f32::NAN];
    let r = unsafe { gpu::elfall_gpu_chart_from_values(v.as_ptr(), 1, 8, &mut out) };
    assert!(r < 0); assert!(out.ptr.is_null()); assert_eq!(out.capacity, 0);
    assert!(unsafe { gpu::elfall_gpu_chart_from_values(v.as_ptr(), 1, 1, &mut out) } < 0, "points < 2");
    assert!(unsafe { gpu::elfall_gpu_chart_from_values(std::ptr::null(), 0, 4, std::ptr::null_mut()) } < 0);
    let r = unsafe { gpu::elfall_gpu_chart_from_values(std::ptr::null(), 0, 4, &mut out) };
    assert_eq!(r, 0);
    assert_eq!(unsafe { gpu::elfall_gpu_buffer_free(out.ptr.cast(), out.capacity) }, 0);
}

#[test]
fn gpu_write_into_borrowed_caller_memory() {
    let _g = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
    let need = gpu::chart_bytes(4).unwrap();
    let mut mem = vec![0xAAu8; need + 8];
    assert_eq!(gpu::write_chart(&[1.0, 2.0], 4, &mut mem).unwrap(), need);
    assert_eq!(&mem[need..], &[0xAA; 8], "za koncem se nepíše");
    let mut small = vec![0u8; need - 1];
    assert_eq!(gpu::write_chart(&[1.0], 4, &mut small).err(), Some(CoreError::InvalidLength));
    let r = unsafe { gpu::elfall_gpu_chart_write([1.0f32, 2.0].as_ptr(), 2, 4, mem.as_mut_ptr(), mem.len()) };
    assert_eq!(r as usize, need);
    assert!(unsafe { gpu::elfall_gpu_chart_write(std::ptr::null(), 0, 4, std::ptr::null_mut(), 0) } < 0);
    assert_eq!(gpu::elfall_gpu_chart_bytes(1), 0);
    let b = gpu::chart_vertices(&[3.0, 1.0], 4).unwrap();
    let v = b.view();
    assert_eq!(v.as_slice(), b.as_slice());
    assert_eq!(v.len, need);
}

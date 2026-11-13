//! Testy paměťové bezpečnosti GPU bufferů pro Miri (`cargo +nightly miri test --test miri_gpu`).
//! Nevolají libsodium (FFI), jen alokaci/zápis/uvolnění stránkově zarovnaných bufferů.
use elfall_core::gpu;

#[test]
fn page_buffer_alloc_write_drop() {
    let before = gpu::live_allocations();
    {
        let mut b = gpu::PageBuffer::new(100).unwrap();
        assert_eq!(b.len(), 100);
        assert_eq!(b.as_ptr() as usize % gpu::page_size(), 0);
        assert!(b.capacity() >= 100 && b.capacity().is_multiple_of(gpu::page_size()));
        b.as_mut_slice().iter_mut().enumerate().for_each(|(i, x)| *x = i as u8);
        assert_eq!(b.as_slice()[99], 99);
    }
    assert_eq!(gpu::live_allocations(), before);
    chart_into_borrowed_slice_bounds();
    assert_eq!(gpu::live_allocations(), before);
}

// volá se z jediného testu: registr LIVE je globální, paralelní testy by počty rozhodily
fn chart_into_borrowed_slice_bounds() {
    let vals = [1.0f32, 2.0, 3.0, 2.5];
    let n = gpu::chart_bytes(4).unwrap();
    let mut out = vec![0u8; n];
    assert_eq!(gpu::write_chart(&vals, 4, &mut out).unwrap(), n);
    let mut short = vec![0u8; n - 1];
    assert!(gpu::write_chart(&vals, 4, &mut short).is_err());
    let b = gpu::chart_vertices(&vals, 4).unwrap();
    assert!(b.len() >= n);
}

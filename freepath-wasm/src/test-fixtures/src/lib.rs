use std::cell::RefCell;

thread_local! {
    static OUTPUT: RefCell<Vec<u8>> = RefCell::new(Vec::new());
}

#[no_mangle]
pub extern "C" fn wasm_alloc(len: i32) -> i32 {
    let mut buf = vec![0u8; len as usize];
    let ptr = buf.as_mut_ptr() as i32;
    std::mem::forget(buf);
    ptr
}

#[no_mangle]
pub extern "C" fn wasm_dealloc(ptr: i32, len: i32) {
    unsafe { let _ = Vec::from_raw_parts(ptr as *mut u8, len as usize, len as usize); }
}

#[no_mangle]
pub extern "C" fn wasm_result_ptr() -> i32 {
    OUTPUT.with(|o| o.borrow().as_ptr() as i32)
}

/// Copies input bytes verbatim to the output buffer. Returns output byte length.
#[no_mangle]
pub extern "C" fn echo(ptr: i32, len: i32) -> i32 {
    let input = unsafe { std::slice::from_raw_parts(ptr as *const u8, len as usize) };
    OUTPUT.with(|o| *o.borrow_mut() = input.to_vec());
    len
}

/// Returns JSON {"reversed":"<input reversed>"} — used to test non-trivial output.
#[no_mangle]
pub extern "C" fn reverse(ptr: i32, len: i32) -> i32 {
    let input = unsafe { std::slice::from_raw_parts(ptr as *const u8, len as usize) };
    let s = String::from_utf8_lossy(input);
    let reversed: String = s.chars().rev().collect();
    let out = format!("{{\"reversed\":\"{}\"}}", reversed);
    let out_bytes = out.into_bytes();
    let out_len = out_bytes.len() as i32;
    OUTPUT.with(|o| *o.borrow_mut() = out_bytes);
    out_len
}

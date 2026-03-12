fn main() {
    let crate_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    let out_path = std::path::PathBuf::from(&crate_dir)
        .join("../../src/nativeInterop/cinterop/freepath_libp2p.h");

    if let Ok(bindings) = cbindgen::Builder::new()
        .with_crate(&crate_dir)
        .with_config(cbindgen::Config::from_file("cbindgen.toml").unwrap_or_default())
        .with_include_guard("FREEPATH_LIBP2P_H")
        .generate()
    {
        let mut buf = Vec::new();
        bindings.write(&mut buf);
        let header = String::from_utf8(buf).expect("cbindgen produced non-UTF-8");

        // Wrap JNI declarations in #ifndef __APPLE__ guards.
        let header = header.replace(
            "\njint JNI_OnLoad(",
            "\n#ifndef __APPLE__\n\njint JNI_OnLoad(",
        );
        let header = header.replace(
            "\n#endif  /* FREEPATH_LIBP2P_H */",
            "\n#endif  /* !__APPLE__ */\n\n#endif  /* FREEPATH_LIBP2P_H */",
        );

        std::fs::write(out_path, header).expect("failed to write header");
    }
}

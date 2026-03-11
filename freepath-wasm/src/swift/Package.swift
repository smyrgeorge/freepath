// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Wasm3Bridge",
    platforms: [.iOS(.v14), .macOS(.v13)],
    products: [
        .library(name: "Wasm3Bridge", type: .static, targets: ["Wasm3Bridge"])
    ],
    targets: [
        .target(
            name: "Cwasm3",
            path: "Sources/Cwasm3",
            publicHeadersPath: "include",
            cSettings: [
                .define("d_m3MaxFunctionStackHeight", to: "2000"),
                .define("d_m3VerboseErrorMessages"),
            ]
        ),
        .target(
            name: "Wasm3Bridge",
            dependencies: ["Cwasm3"],
            path: "Sources/Wasm3Bridge"
        ),
    ]
)

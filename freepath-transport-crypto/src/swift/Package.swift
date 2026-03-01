// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CryptoBridge",
    platforms: [.macOS(.v12), .iOS(.v14)],
    products: [
        .library(name: "CryptoBridge", type: .static, targets: ["CryptoBridge"]),
    ],
    targets: [
        .target(name: "CryptoBridge"),
    ]
)

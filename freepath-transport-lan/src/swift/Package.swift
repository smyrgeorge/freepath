// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "MdnsBridge",
    platforms: [.macOS(.v12), .iOS(.v14)],
    products: [
        .library(name: "MdnsBridge", type: .static, targets: ["MdnsBridge"]),
    ],
    targets: [
        .target(name: "MdnsBridge"),
    ]
)

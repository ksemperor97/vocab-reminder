// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "VocabReminderMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "VocabReminderMac", targets: ["VocabReminderMac"])
    ],
    targets: [
        .executableTarget(
            name: "VocabReminderMac",
            swiftSettings: [
                .define("DEBUG", .when(configuration: .debug))
            ]
        ),
        .testTarget(
            name: "VocabReminderMacTests",
            dependencies: ["VocabReminderMac"]
        )
    ]
)

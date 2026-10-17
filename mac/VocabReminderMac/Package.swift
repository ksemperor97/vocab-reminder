// swift-tools-version: 6.0

import PackageDescription

// VocabCore holds everything that is not UI: models, scheduler, the SQLite
// store, sync, dictionary and notification planning. The menu bar app and the
// vocab-check CLI (shared vectors + a headless driver of the real store for
// end-to-end runs) both sit on top of it, so check code never ships inside
// the app binary.
let package = Package(
    name: "VocabReminderMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "VocabReminderMac", targets: ["VocabReminderMac"]),
        .executable(name: "vocab-check", targets: ["VocabCheck"])
    ],
    targets: [
        .target(
            name: "VocabCore",
            swiftSettings: [
                .define("DEBUG", .when(configuration: .debug))
            ],
            linkerSettings: [
                .linkedLibrary("sqlite3")
            ]
        ),
        .executableTarget(
            name: "VocabReminderMac",
            dependencies: ["VocabCore"],
            swiftSettings: [
                .define("DEBUG", .when(configuration: .debug))
            ]
        ),
        .executableTarget(
            name: "VocabCheck",
            dependencies: ["VocabCore"]
        ),
        .testTarget(
            name: "VocabReminderMacTests",
            dependencies: ["VocabCore"]
        )
    ]
)

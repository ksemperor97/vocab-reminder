# Server-managed recommendations — 2026-09-23

## Behavior

The computer sync server is the sole recommendation authority. It reads its catalogue,
selects candidates, applies the user's timezone/day budget, prioritizes existing reviews,
and permanently excludes known/dismissed lemmas. Recommendations are ordinary cards
with stable server IDs, so existing snapshot and incremental-sync clients can receive
them without a protocol upgrade.

On synchronization, untouched legacy recommendations are retired with ordinary
versioned tombstones. Started/reviewed recommendations and user-saved cards remain.
Feedback-only uploads also remove matching recommended cards, including stale copies
resurrected by another device. Upload reconciliation and generation are transactional.

Current Android/Mac source no longer contains a recommendation catalogue or selection
algorithm. It displays synchronized cards offline and records actions for the next sync.
Android's independent offline dictionary fallback remains available for manual captures;
it is not a recommendation candidate source.

For future catalogue edits: build/edit `shared/recommendations/recommendation_catalog.json`,
then run `npm run recommendations:publish`. The command validates a temporary copy,
backs up the previous target to `.previous`, and publishes atomically. The next sync
reads the changed catalogue without restarting the server or rebuilding a client.
Policy changes require deploying the server, not rebuilding clients.

## Deployment and verification

- Live server release: `20260923T052211Z-08342252e149-40009`.
- Backup/restore verification and authenticated health check passed before completion.
- Live local and phone-facing HTTPS snapshot requests passed.
- Live migration retired 9 untouched legacy recommendations and prepared 8 server recommendations.
- Live active cards: 48; active known/dismissed recommendations: 0; legacy pending recommendations: 0.
- Server tests: 80 passed.
- Android tests: 139 total, 138 passed, 1 skipped.
- Catalogue integrity/30-day simulation and 3 content-pipeline tests passed.
- Mac build and scheduler/sync command-line checks passed.
- Mac XCTest execution remains blocked by the installed Swift/Testing SDK mismatch.

The first deployment attempt was automatically rolled back when the restore drill
attempted recommendation generation. Restore drills now explicitly disable all
recommendation mutation, and the subsequent deployment completed successfully.

## Installed-client boundary

Android 0.4.1 (versionCode 5) was installed on the connected phone on 2026-09-23
using `adb install -r`. The original signing certificate was verified, the package's
first-install timestamp was retained, and the wordbook UI opened after the update.
The Android client now has no offline recommendation generator. The installed Mac
client was not replaced and remains a legacy client until separately upgraded.

Storage cleanup after installation: archived server logs were losslessly compressed
and verified (284,202,196 bytes reclaimed); Android build outputs (~171 MB) were removed.
The signed APK is retained in `dist/android/VocabReminderAndroid-0.4.1.apk`.
Sources, vocabulary data, backups, and shared development caches were preserved.

The catalogue has 189 entries; it does not yet meet the 1,095-entry long-term supply target.

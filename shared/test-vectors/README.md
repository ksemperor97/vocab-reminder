# Shared Test Vectors

M1 must create `scheduler.json` here with at least 15 shared vectors from guide sections 6, 13, and 19.

Both Kotlin and Swift scheduler implementations must pass the same vectors so interval, status, quiet-hours, and rebalance behavior cannot drift between platforms.

M7 adds `recommendations.json` for recommendation selection/fill-limit vectors.
Both Kotlin and Swift recommendation engines must pass it so the 50-word gate,
due-review priority, active recommendation slot cap, level estimation, and
feedback downranking rules cannot drift between platforms.

`capture_normalization.json` covers cross-platform capture parsing cases that
affect user-facing save flows, including URL/domain spans being ignored as word
candidates while nearby real words remain selectable.

`meaning_conflicts.json` defines the shared sync contract for simultaneous
Android and Mac offline meaning edits: both append-only candidates survive,
the exact-revision card tie selects the same primary in every implementation,
and the other candidate remains restorable after export and re-import.

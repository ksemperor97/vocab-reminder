# Shared Schema

`vocab-reminder.schema.json` captures the platform-neutral data model from guide section 5.

The schemas are intentionally storage-shaped rather than UI-shaped:

- Firestore collections can validate against the same field names.
- Kotlin and Swift models should preserve these names at sync boundaries.
- `review_events` is append-only by product rule; schema validation alone is not enough to enforce that. Firestore rules are added in M2.

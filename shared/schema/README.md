# Shared Schema

`vocab-reminder.schema.json` captures the platform-neutral data model from guide section 5.

The schemas are intentionally storage-shaped rather than UI-shaped:

- The local sync server, SQLite JSON payloads, and clients use the same field names.
- Kotlin and Swift models should preserve these names at sync boundaries.
- `review_events` is append-only by product rule; the local sync server enforces idempotency with `PRIMARY KEY(id)` plus insert-or-ignore.

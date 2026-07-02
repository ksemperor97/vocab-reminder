# Recommendation Catalogue

The Android recommendation engine uses the generated catalogue in
`shared/recommendations/recommendation_catalog.json`. The generated Kotlin copy
is `android/VocabReminderAndroid/app/src/main/java/com/vocabreminder/android/recommendation/RecommendationCatalog.kt`.

Frequency ranks are derived from `subtlex-word-frequencies` 2.0.0, an
ISC-licensed package based on the SUBTLEXus American English subtitle corpus.

Source: https://github.com/words/subtlex-word-frequencies

Regenerate after changing the curated seed list:

```sh
SUBTLEX_INDEX_JSON=/path/to/subtlex-word-frequencies/index.json \
  node shared/recommendations/scripts/build_catalog.mjs
```

The app does not bundle definitions/translations for the full 74,286-word list.
Instead it ships a generated 149-entry academic/scientific catalogue with
verified frequency ranks. The Android unit tests enforce catalogue size,
unique lemmas, required topics, and parity between the shared JSON and Kotlin
copy.

Covered M7 product rules:

- recommendations stay disabled below 50 user words,
- due reviews fill `daily_word_goal` before recommendations,
- recommendations are chosen near the user's estimated boundary band,
- `[알아요]` feedback shifts the band harder,
- capturing a recommended lemma promotes it into the user wordbook while keeping
  its progress.

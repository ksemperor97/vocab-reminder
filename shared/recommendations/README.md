# Recommendation Catalogue

The Android recommendation engine uses the generated catalogue in
`shared/recommendations/recommendation_catalog.json`. The generated Kotlin copy
is `android/VocabReminderAndroid/app/src/main/java/com/vocabreminder/android/recommendation/RecommendationCatalog.kt`.

The full ranked source pool is generated as JSONL in
`shared/recommendations/recommendation_candidate_pool.jsonl`, with metadata in
`shared/recommendations/recommendation_candidate_pool.meta.json`. This pool keeps
all 74,286 SUBTLEX entries available for the future definition/translation
pipeline without bundling them into the app catalogue.

`shared/recommendations/recommendation_fill_queue.json` contains the next 1,000
eligible candidates, excluding lemmas already present in the curated app
catalogue. Each queue item is marked `needs_definition_translation_topic`; this
is the input for the future definition, Korean meaning, example, and topic fill
step.

`shared/recommendations/scripts/enrich_fill_queue.mjs` can enrich queue entries
with English definitions/examples from dictionaryapi.dev, Korean meanings from a
translation fixture/provider, and heuristic topics. The default live output
`recommendation_enriched_candidates.json` is gitignored until source/licensing
and translation-provider choices are finalized. The committed
`recommendation_enriched_candidates.sample.json` is generated from fixtures and
proves the enrichment shape end to end.

Frequency ranks are derived from `subtlex-word-frequencies` 2.0.0, an
ISC-licensed package based on the SUBTLEXus American English subtitle corpus.

Source: https://github.com/words/subtlex-word-frequencies

Install root dependencies and regenerate all recommendation artifacts:

```sh
npm install
npm run recommendations:all
```

Run a live enrichment batch locally:

```sh
RECOMMENDATION_ENRICH_LIMIT=25 npm run recommendations:enrich
```

Regenerate only the curated app catalogue after changing the seed list:

```sh
SUBTLEX_INDEX_JSON=/path/to/subtlex-word-frequencies/index.json \
  node shared/recommendations/scripts/build_catalog.mjs
```

The app does not bundle definitions/translations for the full 74,286-word list.
Instead it ships a generated 149-entry academic/scientific catalogue with
verified frequency ranks. The full pool currently contains rank/count/bucket
metadata only, and the fill queue selects the next eligible items to enrich;
definitions, Korean meanings, examples, and domain topics are filled in the
curated app catalogue. The Android unit tests enforce catalogue size, unique
lemmas, required topics, and parity between the shared JSON and Kotlin copy.

Covered M7 product rules:

- recommendations stay disabled below 50 user words,
- due reviews fill `daily_word_goal` before recommendations,
- existing active recommendations occupy open daily slots before new
  recommendation cards are generated,
- recommendations are chosen near the user's estimated boundary band,
- `[알아요]` feedback shifts the band harder,
- `[관심 없음]` feedback excludes the exact lemma and downranks the dismissed
  topic within the boundary band,
- capturing a recommended lemma promotes it into the user wordbook while keeping
  its progress,
- sync duplicate import preserves that progress if one device has the
  recommended card and another has already captured the same lemma as a user
  card.

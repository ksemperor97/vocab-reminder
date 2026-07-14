# Recommendation Catalogue

The release catalogue is `recommendation_catalog.json`; generated Android and
Mac copies are `RecommendationCatalog.kt` and `RecommendationCatalog.swift`.
The current release contains 149 complete project-authored entries. This is
enough to exercise the product, but only 49 days at three new words per day. It
does **not** satisfy the 1,095-entry twelve-month minimum or the 2,000-entry
product target.

## Release data boundary

`recommendation_candidate_pool.jsonl` contains 74,286 SUBTLEX rank records and
`recommendation_fill_queue.json` deterministically selects the next 1,000
eligible lemmas. Neither file is a release dictionary: rank-only or unreviewed
entries cannot enter the app catalogue.

The catalogue builder accepts two content classes:

1. the existing project-authored curated seed (`reviewStatus` is
   `curated_at_authoring`), and
2. a licensed import produced by the reviewed-promotion gate (`reviewStatus` is
   `approved` and the human review is bound to the exact content checksum).

Every release entry has a stable `entryId` (`recommendation:<normalized-lemma>`),
frequency rank/count, a derived difficulty band, a content-source ID, and a
review state. `contentSources` records version, attribution, redistribution,
license, checksum, and share-alike metadata for imported sources. If any
licensed entry is pending, lacks complete fields, has stale review checksums, or
references changed source metadata, the build fails closed.

## Licensed JSONL review pipeline

The importer is provider-neutral: it reads a local Wiktextract-shaped JSONL
snapshot plus a separate manifest. It does not call, trust, or hard-code a
particular hosting provider. The manifest must provide:

- a stable source ID, dataset name/version, format, HTTPS source URL, and
  retrieval date;
- the exact JSONL SHA-256 checksum;
- every applicable license and its URL/scope;
- attribution, redistribution permission, share-alike requirement, and the
  redistribution notice.

`fixtures/licensed_wiktextract_fixture.*` is synthetic dual-licensed test data;
it is not a production Wiktionary snapshot. A real Kaikki/Wiktextract snapshot
needs its own manifest derived from that snapshot's actual version and checksum.

Import exact queue lemmas into a pending review file:

```sh
RECOMMENDATION_LEXICAL_JSONL=/absolute/path/to/kaikki-or-wiktextract.jsonl \
RECOMMENDATION_LEXICAL_SOURCE_MANIFEST=/absolute/path/to/source-manifest.json \
RECOMMENDATION_REVIEW_QUEUE_OUTPUT=/absolute/path/to/review-queue.json \
npm run recommendations:import-licensed
```

The importer deterministically selects exact normalized queue lemmas and keeps
alternative source senses for review. Every imported item starts with
`releaseEligible: false`, `review.status: pending`, and five false review checks.
It never promotes data by completeness alone.

A human reviewer must select/edit the final fields, set a supported topic, check
definition accuracy, Korean translation, example quality, topic fit, and
license/attribution, then record a reviewer ID and ISO-8601 review time. Compute
the checksum of the exact reviewed fields without changing review status:

```sh
RECOMMENDATION_REVIEW_QUEUE=/absolute/path/to/review-queue.json \
npm run recommendations:review-checksums
```

Copy the matching checksum into `review.contentChecksumSha256`, then explicitly
set `review.status` to `approved`. Promotion skips `pending` and `rejected`
items; malformed `approved` items fail the entire operation:

```sh
RECOMMENDATION_REVIEW_QUEUE=/absolute/path/to/review-queue.json \
RECOMMENDATION_REVIEWED_PROMOTIONS_OUTPUT=/absolute/path/to/reviewed-promotions.json \
npm run recommendations:promote-reviewed
```

To test a catalogue build with that artifact, set
`RECOMMENDATION_REVIEWED_PROMOTIONS`. The production default path is
`shared/recommendations/recommendation_reviewed_promotions.json`; it should be
committed only after real review. Identical source, review, SUBTLEX, and seed
inputs produce byte-identical JSON/Kotlin/Swift outputs.

```sh
RECOMMENDATION_REVIEWED_PROMOTIONS=/absolute/path/to/reviewed-promotions.json \
npm run recommendations:build
```

The pipeline regression verifies deterministic import/promotion/build,
source-checksum rejection, pending-item exclusion, and post-review tamper
rejection:

```sh
npm run recommendations:pipeline:test
```

## Sources and licensing

- Frequency ranks/counts: `subtlex-word-frequencies` 2.0.0 (ISC), based on the
  SUBTLEXus corpus. SUBTLEX contributes rank/count metadata only.
  <https://github.com/words/subtlex-word-frequencies>
- Current definitions, Korean meanings, examples, and topics: project-authored
  curated seed content, identified as such in catalogue metadata.
- Prospective expansion: English Wiktionary data extracted by Wiktextract and
  distributed by Kaikki. Its data page states CC BY-SA and GFDL terms, so
  attribution and compatible share-alike notices must accompany any promoted
  derivative. No Wiktionary-derived item is currently bundled.
  <https://kaikki.org/dictionary/>
  <https://github.com/tatuylonen/wiktextract>

`enrich_fill_queue.mjs` remains an exploratory provider/fixture enrichment
utility. Its output is not trusted by the release builder and cannot bypass the
licensed review pipeline.

## Personalization quality gate

Run the catalogue, platform-parity, vector-coverage, and 30-day behavior gate:

```sh
npm run recommendations:quality
```

The gate verifies complete/unique release entries, source and human-review
metadata, byte-level source/content checksums, exact ordered Android/Mac seed
parity, stable recommendation identities, and shared vector coverage. Its
30-day simulation requires zero duplicate recommendations, zero known/dismissed
reappearance, and zero review starvation. Set
`RECOMMENDATION_REQUIRE_LONG_TERM=1` only for the final supply gate; it correctly
fails while the catalogue remains below 1,095 reviewed entries.

Current cross-platform behavior already covers:

- recommendations disabled below 50 user words;
- due reviews and existing active recommendations consuming daily slots first;
- saved lemmas plus `known`/`dismissed` feedback excluding exact lemmas;
- frequency-boundary difficulty, saved-catalogue topic preference, known
  difficulty boost, and dismissed-topic downranking;
- Android and Mac consuming the same catalogue and shared vectors.

Two product requirements are still open and must not be represented as done:

- deriving recent topic signals specifically from locally stored capture
  context while proving that raw context never leaves the device; and
- showing a user-facing recommendation reason such as difficulty, recent topic,
  and frequency.

## Regeneration

Install root dependencies and regenerate all recommendation artifacts and gates:

```sh
npm install
npm run recommendations:all
```

Individual steps remain available as `recommendations:pool`,
`recommendations:queue`, `recommendations:enrich:sample`,
`recommendations:build`, and `recommendations:quality`.

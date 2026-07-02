# Recommendation Frequency Source

The MVP recommendation catalogue uses frequency ranks derived from
`words/subtlex-word-frequencies`, an ISC-licensed package based on the SUBTLEXus
American English subtitle corpus.

Source: https://github.com/words/subtlex-word-frequencies

The app does not bundle the full 74,286-word list yet. Instead it ships a small
ranked academic/scientific seed catalogue that is enough to implement and test
the M7 product rules:

- recommendations stay disabled below 50 user words,
- due reviews fill `daily_word_goal` before recommendations,
- recommendations are chosen near the user's estimated boundary band,
- `[알아요]` feedback shifts the band harder,
- capturing a recommended lemma promotes it into the user wordbook while keeping
  its progress.

import { initializeApp } from "firebase-admin/app";
import { logger } from "firebase-functions";
import { onRequest } from "firebase-functions/v2/https";

initializeApp();

export const health = onRequest((_request, response) => {
  response.status(200).json({ ok: true, service: "vocab-reminder-functions" });
});

export const dictionaryProxy = onRequest({ cors: true }, async (request, response) => {
  const word = typeof request.query.word === "string" ? request.query.word.trim() : "";

  if (!word) {
    response.status(400).json({ error: "Missing required query parameter: word" });
    return;
  }

  // guide 10.5: keep external dictionary calls behind Functions, never in clients.
  const baseUrl = process.env.FREE_DICTIONARY_BASE_URL ?? "https://api.dictionaryapi.dev/api/v2/entries/en";
  const dictionaryUrl = `${baseUrl}/${encodeURIComponent(word)}`;

  try {
    const dictionaryResponse = await fetch(dictionaryUrl);
    if (!dictionaryResponse.ok) {
      response.status(200).json({
        word,
        candidates: [],
        translation_fallback_configured: Boolean(process.env.TRANSLATION_API_KEY)
      });
      return;
    }

    const entries = await dictionaryResponse.json() as Array<{
      word?: string;
      phonetic?: string;
      meanings?: Array<{
        partOfSpeech?: string;
        definitions?: Array<{
          definition?: string;
          example?: string;
        }>;
      }>;
    }>;

    const candidates = entries.flatMap((entry) =>
      entry.meanings?.flatMap((meaning) =>
        meaning.definitions?.slice(0, 2).map((definition) => ({
          source: "free_dictionary_api",
          headword: entry.word ?? word,
          phonetic: entry.phonetic ?? null,
          part_of_speech: meaning.partOfSpeech ?? null,
          definition_en: definition.definition ?? "",
          example_sentence: definition.example ?? null,
          meaning_ko: null
        })) ?? []
      ) ?? []
    ).slice(0, 8);

    response.status(200).json({
      word,
      candidates,
      translation_fallback_configured: Boolean(process.env.TRANSLATION_API_KEY)
    });
  } catch (error) {
    logger.error("Dictionary proxy failed", { word, error });
    response.status(502).json({ error: "Dictionary proxy failed", word });
  }
});

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

  // guide 10.5: real dictionary/translation/LLM resolver logic is introduced in M2.
  logger.info("Dictionary proxy stub invoked", { word });
  response.status(501).json({
    error: "Dictionary resolver is not implemented yet.",
    word
  });
});

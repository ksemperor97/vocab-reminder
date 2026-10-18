// Short Korean meaning from a raw macOS Korean-English dictionary entry.
//
// An entry looks like
//   "bait 1 | beit | 명사1.[불][가]（낚싯바늘·덫에 다는） 미끼,먹이; 독미끼 ▸ a live bait산 미끼 2. …"
// and a card should show "미끼, 먹이": the first semicolon group of sense 1,
// at most three glosses, without grammar marks, labels, alternatives in []
// or leading object markers. The Mac app applies the same rule
// (DictionaryResolver.extractKoreanMeaning); both are pinned by
// shared/test-vectors/mac_dictionary_meanings.json.

const partsOfSpeech = "타동사|자동사|명사|동사|형용사|부사|대명사|전치사|접속사|감탄사|조동사|관사|한정사";
const maxGlosses = 3;

const englishPartOfSpeech = [
  [/^(?:동사|타동사|자동사)/u, "verb"],
  [/^형용사/u, "adjective"],
  [/^부사/u, "adverb"],
  [/^명사/u, "noun"]
];

// The part of speech of sense 1 in English ("noun", "verb", ...), or "".
export function macDictionaryPartOfSpeech(raw) {
  if (typeof raw !== "string") return "";
  const body = (raw.split(" | ").slice(2).join(" | ") || raw).trim();
  return englishPartOfSpeech.find(([pattern]) => pattern.test(body))?.[1] ?? "";
}

export function cleanMacDictionaryMeaning(raw) {
  if (typeof raw !== "string" || !/[가-힣]/u.test(raw)) return "";
  // Headword and pronunciation come first, each followed by " | ".
  let text = raw.split(" | ").slice(2).join(" | ") || raw;
  // Examples (▸), idioms (숙어) and the next sense are not part of sense 1.
  text = text.split("▸")[0].split(/숙어/u)[0];
  text = text.replace(new RegExp(`^\\s*(?:(?:${partsOfSpeech})\\s*[,，]?\\s*)+`, "u"), "");
  text = stripBrackets(text);
  text = text.replace(new RegExp(`^\\s*(?:(?:${partsOfSpeech})\\s*[,，]?\\s*)+`, "u"), "");
  // Senses are numbered "1." "2."; the first one with Korean in it is sense 1.
  const firstSense = text.split(/(?:^|\s|(?<=[^\d\s]))\d+\s*\.(?=\s*\S)/u).find((part) => /[가-힣]/u.test(part)) ?? "";
  const firstGroup = firstSense.split(/[;；\n]/u).find((part) => /[가-힣]/u.test(part)) ?? "";
  const glosses = firstGroup
    .split(/[,，]/u)
    .map(cleanGloss)
    .filter((gloss) => /[가-힣]/u.test(gloss))
    .filter((gloss, index, all) => all.indexOf(gloss) === index)
    .slice(0, maxGlosses);
  return glosses.join(", ");
}

function stripBrackets(text) {
  let previous;
  let current = text;
  do {
    previous = current;
    current = current
      .replace(/\[[^\[\]]*\]/gu, "")
      .replace(/〈[^〈〉]*〉/gu, "")
      .replace(/《[^《》]*》/gu, "")
      .replace(/『[^『』]*』/gu, "")
      .replace(/〔[^〔〕]*〕/gu, "")
      .replace(/（[^（）]*）/gu, "")
      .replace(/\([^()]*\)/gu, "")
      .replace(/【[^【】]*】/gu, "");
  } while (current !== previous);
  return current.replace(/\bcf\.\s*[A-Z][A-Z\s\d]*/gu, "");
}

function cleanGloss(value) {
  return value
    .replace(new RegExp(`^\\s*(?:${partsOfSpeech})\\s*`, "u"), "")
    .replace(/^\s*…\s*(?:을|를|에게|에|과|와|으로|로)?\s*/u, "")
    .replace(/[.。]\s*$/u, "")
    .replace(/\s+/gu, " ")
    .trim();
}

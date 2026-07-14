import crypto from "node:crypto";

export const REVIEW_CHECKS = [
  "definitionAccuracy",
  "translationAccuracy",
  "exampleQuality",
  "topicFit",
  "licenseAndAttribution"
];

export const RELEASE_TOPICS = new Set([
  "academic",
  "general",
  "methods",
  "neuroscience",
  "statistics"
]);

export function canonicalJson(value) {
  return JSON.stringify(sortRecursively(value));
}

export function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

export function normalizeLemma(value) {
  return String(value ?? "").normalize("NFKC").trim().toLocaleLowerCase("en-US");
}

export function contentChecksum(entry) {
  return sha256(canonicalJson(releaseContent(entry)));
}

export function releaseContent(entry) {
  return {
    lemma: normalizeLemma(entry.lemma),
    meaningKo: requiredTrimmed(entry.meaningKo, "meaningKo"),
    definitionEn: requiredTrimmed(entry.definitionEn, "definitionEn"),
    partOfSpeech: requiredTrimmed(entry.partOfSpeech, "partOfSpeech"),
    exampleSentence: requiredTrimmed(entry.exampleSentence, "exampleSentence"),
    frequencyRank: requiredPositiveInteger(entry.frequencyRank, "frequencyRank"),
    frequencyCount: requiredNonnegativeInteger(entry.frequencyCount, "frequencyCount"),
    topic: requiredTrimmed(entry.topic, "topic")
  };
}

export function validateSourceManifest(manifest, dataBytes) {
  if (manifest?.schemaVersion !== 1) throw new Error("Source manifest schemaVersion must be 1.");
  const sourceId = requiredTrimmed(manifest.sourceId, "sourceId");
  if (!/^[a-z0-9][a-z0-9._-]*$/i.test(sourceId)) throw new Error("sourceId contains unsupported characters.");
  const format = requiredTrimmed(manifest.format, "format");
  const datasetName = requiredTrimmed(manifest.datasetName, "datasetName");
  const datasetVersion = requiredTrimmed(manifest.datasetVersion, "datasetVersion");
  const sourceUrl = requiredHttpsUrl(manifest.sourceUrl, "sourceUrl");
  const retrievedAt = requiredIsoDate(manifest.retrievedAt, "retrievedAt");
  const attribution = requiredTrimmed(manifest.attribution, "attribution");
  const redistributionNotice = requiredTrimmed(manifest.redistributionNotice, "redistributionNotice");
  if (manifest.redistributionAllowed !== true) {
    throw new Error("Source manifest must explicitly set redistributionAllowed=true.");
  }
  if (typeof manifest.shareAlikeRequired !== "boolean") {
    throw new Error("Source manifest must explicitly state shareAlikeRequired.");
  }
  if (!Array.isArray(manifest.licenses) || manifest.licenses.length === 0) {
    throw new Error("Source manifest must include at least one license.");
  }
  const licenses = manifest.licenses.map((license, index) => ({
    id: requiredTrimmed(license?.id, `licenses[${index}].id`),
    name: requiredTrimmed(license?.name, `licenses[${index}].name`),
    url: requiredHttpsUrl(license?.url, `licenses[${index}].url`),
    appliesTo: requiredTrimmed(license?.appliesTo, `licenses[${index}].appliesTo`)
  })).sort((left, right) => left.id.localeCompare(right.id));
  const actualDataChecksum = sha256(dataBytes);
  const declaredDataChecksum = requiredSha256(manifest.dataChecksumSha256, "dataChecksumSha256");
  if (actualDataChecksum !== declaredDataChecksum) {
    throw new Error(`Source data checksum mismatch: expected ${declaredDataChecksum}, got ${actualDataChecksum}.`);
  }

  const normalizedManifest = {
    schemaVersion: 1,
    sourceId,
    format,
    datasetName,
    datasetVersion,
    sourceUrl,
    retrievedAt,
    dataChecksumSha256: actualDataChecksum,
    licenses,
    attribution,
    redistributionAllowed: true,
    shareAlikeRequired: manifest.shareAlikeRequired,
    redistributionNotice
  };
  return {
    ...normalizedManifest,
    metadataChecksumSha256: sha256(canonicalJson(normalizedManifest))
  };
}

export function validatePromotionArtifact(payload) {
  if (payload?.schemaVersion !== 1 || payload?.promotionPolicyVersion !== 1) {
    throw new Error("Promotion artifact schemaVersion and promotionPolicyVersion must both be 1.");
  }
  if (!Array.isArray(payload.sourceSnapshots) || payload.sourceSnapshots.length === 0) {
    throw new Error("Promotion artifact must preserve sourceSnapshots.");
  }
  requiredSha256(payload.sourceReviewQueueChecksumSha256, "sourceReviewQueueChecksumSha256");
  const sources = new Map();
  for (const source of payload.sourceSnapshots) {
    if (source?.schemaVersion !== 1) throw new Error("Source snapshot schemaVersion must be 1.");
    const sourceId = requiredTrimmed(source?.sourceId, "sourceSnapshots.sourceId");
    if (sources.has(sourceId)) throw new Error(`Duplicate source snapshot: ${sourceId}`);
    requiredTrimmed(source.format, `${sourceId}.format`);
    requiredTrimmed(source.datasetName, `${sourceId}.datasetName`);
    requiredTrimmed(source.datasetVersion, `${sourceId}.datasetVersion`);
    requiredHttpsUrl(source.sourceUrl, `${sourceId}.sourceUrl`);
    requiredIsoDate(source.retrievedAt, `${sourceId}.retrievedAt`);
    requiredSha256(source.dataChecksumSha256, `${sourceId}.dataChecksumSha256`);
    requiredSha256(source.metadataChecksumSha256, `${sourceId}.metadataChecksumSha256`);
    requiredTrimmed(source.attribution, `${sourceId}.attribution`);
    requiredTrimmed(source.redistributionNotice, `${sourceId}.redistributionNotice`);
    if (source.redistributionAllowed !== true || typeof source.shareAlikeRequired !== "boolean") {
      throw new Error(`${sourceId} is missing redistribution/share-alike metadata.`);
    }
    if (!Array.isArray(source.licenses) || source.licenses.length === 0) {
      throw new Error(`${sourceId} is missing license metadata.`);
    }
    source.licenses.forEach((license, index) => {
      requiredTrimmed(license?.id, `${sourceId}.licenses[${index}].id`);
      requiredTrimmed(license?.name, `${sourceId}.licenses[${index}].name`);
      requiredHttpsUrl(license?.url, `${sourceId}.licenses[${index}].url`);
      requiredTrimmed(license?.appliesTo, `${sourceId}.licenses[${index}].appliesTo`);
    });
    const { metadataChecksumSha256, ...metadata } = source;
    if (sha256(canonicalJson(metadata)) !== metadataChecksumSha256) {
      throw new Error(`${sourceId} metadata changed after import; checksum does not match.`);
    }
    sources.set(sourceId, source);
  }
  if (!Array.isArray(payload.entries)) throw new Error("Promotion artifact entries must be an array.");
  const seen = new Set();
  const entries = payload.entries.map((entry, index) => {
    const content = releaseContent(entry);
    if (!content.lemma) throw new Error(`Promotion entry ${index} has an empty lemma.`);
    if (seen.has(content.lemma)) throw new Error(`Duplicate promoted lemma: ${content.lemma}`);
    seen.add(content.lemma);
    if (!RELEASE_TOPICS.has(content.topic)) throw new Error(`${content.lemma} has unsupported topic ${content.topic}.`);
    if (entry.releaseEligible !== true) throw new Error(`${content.lemma} is not explicitly releaseEligible.`);
    if (entry.review?.status !== "approved") throw new Error(`${content.lemma} lacks approved human review.`);
    requiredTrimmed(entry.review.reviewerId, `${content.lemma}.review.reviewerId`);
    requiredIsoDate(entry.review.reviewedAt, `${content.lemma}.review.reviewedAt`);
    for (const check of REVIEW_CHECKS) {
      if (entry.review.checks?.[check] !== true) throw new Error(`${content.lemma} review check ${check} is not true.`);
    }
    const expectedChecksum = contentChecksum(content);
    if (entry.review.contentChecksumSha256 !== expectedChecksum) {
      throw new Error(`${content.lemma} changed after review; content checksum does not match.`);
    }
    const sourceId = requiredTrimmed(entry.provenance?.sourceId, `${content.lemma}.provenance.sourceId`);
    const source = sources.get(sourceId);
    if (!source) throw new Error(`${content.lemma} references unknown source ${sourceId}.`);
    if (entry.provenance.datasetVersion !== source.datasetVersion ||
        entry.provenance.dataChecksumSha256 !== source.dataChecksumSha256 ||
        entry.provenance.metadataChecksumSha256 !== source.metadataChecksumSha256) {
      throw new Error(`${content.lemma} source snapshot does not match the promotion artifact.`);
    }
    requiredSha256(entry.provenance.recordChecksumSha256, `${content.lemma}.provenance.recordChecksumSha256`);
    return {
      ...content,
      releaseEligible: true,
      provenance: entry.provenance,
      review: entry.review
    };
  });
  return { sources, entries };
}

export function difficultyBand(frequencyRank) {
  if (frequencyRank <= 5_000) return "core";
  if (frequencyRank <= 15_000) return "intermediate";
  if (frequencyRank <= 30_000) return "boundary";
  if (frequencyRank <= 55_000) return "advanced";
  return "rare";
}

export function requiredTrimmed(value, label) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${label} must be a non-empty string.`);
  return value.trim();
}

function requiredPositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${label} must be a positive integer.`);
  return value;
}

function requiredNonnegativeInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${label} must be a nonnegative integer.`);
  return value;
}

function requiredSha256(value, label) {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/.test(value)) throw new Error(`${label} must be a lowercase SHA-256 value.`);
  return value;
}

function requiredHttpsUrl(value, label) {
  const string = requiredTrimmed(value, label);
  let url;
  try {
    url = new URL(string);
  } catch {
    throw new Error(`${label} must be a valid URL.`);
  }
  if (url.protocol !== "https:") throw new Error(`${label} must use HTTPS.`);
  return url.toString();
}

function requiredIsoDate(value, label) {
  const string = requiredTrimmed(value, label);
  if (!/^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)?$/.test(string) || Number.isNaN(Date.parse(string))) {
    throw new Error(`${label} must be an ISO-8601 UTC date or date-time.`);
  }
  return string;
}

function sortRecursively(value) {
  if (Array.isArray(value)) return value.map(sortRecursively);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortRecursively(value[key])]));
  }
  return value;
}

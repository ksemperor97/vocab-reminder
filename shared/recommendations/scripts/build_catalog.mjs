import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  difficultyBand,
  sha256,
  validatePromotionArtifact
} from "./review_pipeline_common.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const subtlexPath = process.env.SUBTLEX_INDEX_JSON ?? resolve(repoRoot, "node_modules/subtlex-word-frequencies/index.json");
const subtlex = JSON.parse(readFileSync(subtlexPath, "utf8"));
const ranks = new Map(subtlex.map((entry, index) => [entry.word.toLowerCase(), { rank: index + 1, count: entry.count }]));

const entries = [
  ["subtle", "미묘한, 감지하기 어려운", "delicate or not obvious", "adjective", "The change produced a subtle improvement.", "academic"],
  ["preliminary", "예비의, 초기 단계의", "coming before a more important action or event", "adjective", "These are preliminary results.", "academic"],
  ["substantial", "상당한, 실질적인", "large in amount, value, or importance", "adjective", "The model showed a substantial improvement.", "academic"],
  ["threshold", "기준점, 문턱값", "the level at which something starts to happen", "noun", "Responses above the threshold were counted.", "statistics"],
  ["dense", "빽빽한, 조밀한", "closely compacted together", "adjective", "The dense matrix was hard to inspect.", "methods"],
  ["compelling", "설득력 있는, 강력한", "evoking interest or attention in a powerful way", "adjective", "The evidence was compelling.", "academic"],
  ["trajectory", "궤적, 진행 경로", "the path followed by a moving object or process", "noun", "The learning trajectory changed over time.", "methods"],
  ["plausible", "그럴듯한, 타당해 보이는", "seeming reasonable or probable", "adjective", "The authors offered a plausible explanation.", "academic"],
  ["bias", "편향, 편견", "a systematic tendency or prejudice", "noun", "Sampling bias affected the result.", "statistics"],
  ["adaptation", "적응, 조정", "the process of changing to fit new conditions", "noun", "Adaptation occurred after repeated exposure.", "neuroscience"],
  ["suppress", "억제하다, 누르다", "to prevent something from being expressed", "verb", "The mask suppressed the response.", "neuroscience"],
  ["prospective", "장래의, 전향적인", "expected or likely to happen in the future", "adjective", "The prospective analysis was preregistered.", "methods"],
  ["subsequent", "그 다음의, 후속의", "coming after something in time", "adjective", "Subsequent trials used a shorter delay.", "academic"],
  ["approximate", "대략의, 근사적인", "close to the actual value but not exact", "adjective", "The approximate value was sufficient.", "statistics"],
  ["confound", "교란 요인, 혼동시키다", "to confuse or mix effects together", "verb", "Motion can confound the measurement.", "methods"],
  ["artifact", "인공 산물, 잡음", "an unintended result of a process or measurement", "noun", "The signal contained a measurement artifact.", "methods"],
  ["contingent", "조건부의, ~에 달린", "depending on something else", "adjective", "The effect was contingent on task difficulty.", "academic"],
  ["coherent", "일관된, 논리적으로 연결된", "logical and consistent", "adjective", "The model produced a coherent summary.", "academic"],
  ["facilitate", "촉진하다, 쉽게 하다", "to make an action or process easier", "verb", "Feedback facilitated learning.", "neuroscience"],
  ["hypothesis", "가설", "a proposed explanation made for testing", "noun", "The hypothesis predicted a delayed response.", "academic"],
  ["validate", "검증하다, 타당성을 확인하다", "to check that something is sound or accurate", "verb", "A second dataset validated the model.", "methods"],
  ["complement", "보완하다, 보완물", "to add to something in a useful way", "verb", "The behavioral result complemented the neural data.", "academic"],
  ["latent", "잠재적인, 숨어 있는", "present but not visible or active", "adjective", "The latent variable captured task difficulty.", "statistics"],
  ["ambiguous", "애매한, 여러 뜻으로 해석되는", "open to more than one interpretation", "adjective", "The ambiguous result required another experiment.", "academic"],
  ["replicate", "재현하다, 반복하다", "to repeat a study or result", "verb", "The lab tried to replicate the finding.", "methods"],
  ["paradigm", "패러다임, 실험 틀", "a typical example or model", "noun", "The paradigm separated memory and attention.", "methods"],
  ["robust", "견고한, 안정적인", "strong and unlikely to fail", "adjective", "The model showed robust decoding performance.", "statistics"],
  ["derive", "도출하다, 얻다", "to obtain something from a source", "verb", "The score was derived from reaction times.", "methods"],
  ["converge", "수렴하다, 한 점으로 모이다", "to come together toward one point", "verb", "The estimates converge after many trials.", "statistics"],
  ["infer", "추론하다", "to reach a conclusion from evidence", "verb", "The model inferred the hidden state.", "statistics"],
  ["stimulus", "자극", "something that causes a response", "noun", "The stimulus elicited a strong response.", "neuroscience"],
  ["elicit", "이끌어내다, 유도하다", "to draw out a response, answer, or fact", "verb", "The stimulus elicited a strong response.", "neuroscience"],
  ["tentative", "잠정적인, 임시의", "not certain or fixed", "adjective", "The results are tentative.", "academic"],
  ["empirical", "경험적 자료에 근거한", "based on observation or experiment", "adjective", "The claim needs empirical support.", "academic"],
  ["ubiquitous", "어디에나 있는, 아주 흔한", "present or found everywhere", "adjective", "Smartphones are ubiquitous these days.", "general"],
  ["mitigate", "완화하다, 줄이다", "to make something less harmful or serious", "verb", "The protocol mitigated measurement noise.", "methods"],
  ["negligible", "무시할 만한, 사소한", "so small as to be unimportant", "adjective", "The residual error was negligible.", "statistics"],
  ["variance", "분산, 변동성", "the degree to which values differ", "noun", "The variance increased in the noisy condition.", "statistics"],
  ["decoding", "해독, 디코딩", "the process of converting signals into information", "noun", "Decoding accuracy was above chance.", "neuroscience"],
  ["pervasive", "널리 퍼진, 만연한", "spreading widely through an area or group", "adjective", "The bias was pervasive across datasets.", "academic"],
  ["retrospective", "회고적인, 후향적인", "looking back on or dealing with past events", "adjective", "The retrospective analysis used existing records.", "methods"],
  ["salient", "두드러진, 현저한", "most noticeable or important", "adjective", "The salient feature was the delayed response.", "neuroscience"],
  ["inference", "추론", "a conclusion reached from evidence", "noun", "Inference depended on the prior distribution.", "statistics"],
  ["criterion", "기준, 판단 기준", "a standard by which something is judged", "noun", "The exclusion criterion was defined in advance.", "methods"],
  ["sparse", "드문드문한, 희소한", "thinly distributed or scattered", "adjective", "The sparse representation improved interpretability.", "statistics"],
  ["aggregate", "합산하다, 집계하다", "to collect into a whole", "verb", "The results were aggregated across subjects.", "methods"],
  ["intrinsic", "본질적인, 내재적인", "belonging naturally to something", "adjective", "The task measured intrinsic motivation.", "academic"],
  ["gradient", "기울기, 변화도", "a rate of change across space or value", "noun", "The gradient was steeper in the control group.", "statistics"],
  ["equivocal", "애매한, 분명하지 않은", "open to more than one interpretation", "adjective", "The evidence remained equivocal.", "academic"],
  ["encoding", "부호화, 인코딩", "the process of converting information into a form", "noun", "Encoding improved after repeated exposure.", "neuroscience"],
  ["hierarchical", "계층적인", "arranged in levels of importance", "adjective", "The hierarchical model captured subject effects.", "statistics"],
  ["modulate", "조절하다, 변화시키다", "to influence or adjust something", "verb", "Attention modulated the neural response.", "neuroscience"],
  ["generalize", "일반화하다", "to apply more broadly", "verb", "The finding may not generalize to children.", "methods"],
  ["plasticity", "가소성", "the ability to change or adapt", "noun", "Plasticity was strongest early in training.", "neuroscience"],
  ["attenuate", "약화시키다, 감소시키다", "to reduce the force or effect of something", "verb", "The control reduced but did not attenuate the effect.", "neuroscience"],
  ["homogeneous", "동질적인", "of the same kind throughout", "adjective", "The sample was relatively homogeneous.", "methods"],
  ["mediated", "매개된, 중재된", "brought about through an intermediate factor", "adjective", "The effect was mediated by attention.", "statistics"],
  ["heterogeneous", "이질적인, 다양한", "diverse in character or content", "adjective", "The population was heterogeneous.", "methods"],
  ["canonical", "표준적인, 정식의", "accepted as standard or authoritative", "adjective", "The canonical response appeared after 300 ms.", "academic"],
  ["diverge", "갈라지다, 달라지다", "to separate or become different", "verb", "The two trajectories diverge after training.", "statistics"],
  ["abundant", "풍부한, 많은", "present in large quantities", "adjective", "The corpus provided abundant examples.", "academic"],
  ["accelerate", "가속하다, 촉진하다", "to make a process happen faster", "verb", "Feedback can accelerate learning.", "methods"],
  ["adjacent", "인접한, 가까운", "next to or near something", "adjective", "Adjacent trials were grouped together.", "methods"],
  ["arbitrary", "임의의, 자의적인", "based on choice rather than a fixed rule", "adjective", "The cutoff was not arbitrary.", "methods"],
  ["allocate", "할당하다, 배분하다", "to distribute resources for a purpose", "verb", "The model allocated more weight to recent trials.", "methods"],
  ["analogous", "유사한, 비슷한", "comparable in certain respects", "adjective", "The task was analogous to a memory test.", "academic"],
  ["anomaly", "이례적인 것, 이상치", "something that differs from what is expected", "noun", "The anomaly disappeared after preprocessing.", "statistics"],
  ["apparatus", "장치, 기구", "equipment used for an experiment", "noun", "The apparatus recorded responses automatically.", "methods"],
  ["assess", "평가하다", "to evaluate or estimate something", "verb", "The survey assessed confidence after each trial.", "methods"],
  ["attribute", "귀인하다, 속성", "to regard something as caused by something else", "verb", "The authors attribute the effect to attention.", "academic"],
  ["baseline", "기준선, 기초 수준", "a reference level for comparison", "noun", "Accuracy was compared with baseline.", "statistics"],
  ["characterize", "특징짓다, 설명하다", "to describe the distinctive nature of something", "verb", "The study characterized individual differences.", "methods"],
  ["clarify", "명확히 하다", "to make something easier to understand", "verb", "A follow-up analysis clarified the result.", "academic"],
  ["cluster", "군집, 무리", "a group of similar items close together", "noun", "Items formed a semantic cluster.", "statistics"],
  ["cohort", "집단, 코호트", "a group sharing a defining characteristic", "noun", "The cohort completed the same protocol.", "methods"],
  ["compatible", "양립 가능한, 호환되는", "able to exist or work together", "adjective", "The results are compatible with the hypothesis.", "academic"],
  ["constrain", "제한하다, 제약하다", "to limit or restrict something", "verb", "The prior constrained the parameter estimate.", "statistics"],
  ["correlate", "상관되다", "to vary together with another measure", "verb", "Reaction time correlated with confidence.", "statistics"],
  ["deviation", "편차, 벗어남", "a difference from a standard or expected value", "noun", "The deviation was largest in the noisy condition.", "statistics"],
  ["diminish", "감소시키다, 줄어들다", "to make or become less", "verb", "The effect diminished after practice.", "academic"],
  ["discrete", "분리된, 이산적인", "separate and distinct", "adjective", "Responses were coded as discrete categories.", "statistics"],
  ["domain", "영역, 분야", "a field of knowledge or activity", "noun", "The result generalized across domains.", "academic"],
  ["dynamic", "동적인, 변화하는", "changing over time", "adjective", "The model captured dynamic behavior.", "methods"],
  ["efficacy", "효능, 효과", "the ability to produce a desired result", "noun", "The trial tested the efficacy of feedback.", "methods"],
  ["elaborate", "상세히 설명하다, 정교한", "to explain in more detail", "verb", "The discussion elaborated the mechanism.", "academic"],
  ["encompass", "포함하다, 아우르다", "to include a range of things", "verb", "The framework encompasses several accounts.", "academic"],
  ["enhance", "향상시키다", "to improve or increase something", "verb", "Sleep enhanced memory retention.", "neuroscience"],
  ["exhibit", "보이다, 나타내다", "to show a quality or behavior", "verb", "Participants exhibited faster responses.", "methods"],
  ["explicit", "명시적인, 분명한", "clearly stated or shown", "adjective", "The instructions made the rule explicit.", "academic"],
  ["feasible", "실행 가능한", "possible and practical to do", "adjective", "The shorter protocol was feasible.", "methods"],
  ["fluctuate", "변동하다", "to rise and fall irregularly", "verb", "Performance fluctuated across sessions.", "statistics"],
  ["framework", "틀, 체계", "a structure of ideas used to explain something", "noun", "The framework links attention and memory.", "academic"],
  ["fundamental", "근본적인, 기본적인", "forming a necessary base", "adjective", "The task measured a fundamental process.", "academic"],
  ["imply", "암시하다, 함축하다", "to suggest without stating directly", "verb", "The pattern may imply a causal link.", "academic"],
  ["incidence", "발생률, 빈도", "the rate at which something occurs", "noun", "The incidence of errors decreased.", "statistics"],
  ["induce", "유도하다, 일으키다", "to cause something to happen", "verb", "The manipulation induced a response bias.", "methods"],
  ["integrate", "통합하다", "to combine parts into a whole", "verb", "The model integrated sensory evidence.", "neuroscience"],
  ["intermittent", "간헐적인", "occurring at irregular intervals", "adjective", "Intermittent feedback changed behavior.", "methods"],
  ["interval", "간격, 구간", "a period of time or range between values", "noun", "The interval increased after each success.", "statistics"],
  ["isolate", "분리하다, 격리하다", "to separate something from others", "verb", "The analysis isolated the relevant factor.", "methods"],
  ["manipulate", "조작하다, 조절하다", "to control or change something deliberately", "verb", "The experiment manipulated task difficulty.", "methods"],
  ["magnitude", "크기, 규모", "the size or extent of something", "noun", "The magnitude of the effect was small.", "statistics"],
  ["mechanism", "기제, 메커니즘", "a process that explains how something works", "noun", "The mechanism remains unclear.", "academic"],
  ["minimal", "최소의, 아주 적은", "very small in amount", "adjective", "The task required minimal training.", "methods"],
  ["nuance", "미묘한 차이", "a subtle difference in meaning or expression", "noun", "The analysis captured an important nuance.", "academic"],
  ["parameter", "매개변수, 파라미터", "a numerical factor in a model", "noun", "The parameter was estimated for each subject.", "statistics"],
  ["persistent", "지속적인, 끈질긴", "continuing over time", "adjective", "The bias was persistent across sessions.", "academic"],
  ["phenomenon", "현상", "an observable fact or event", "noun", "The phenomenon appeared in both groups.", "academic"],
  ["prior", "사전의, 이전의", "existing before something else", "adjective", "Prior knowledge affected recall.", "statistics"],
  ["protocol", "절차, 실험 계획", "a formal procedure for an experiment", "noun", "The protocol included a practice block.", "methods"],
  ["qualitative", "질적인", "relating to qualities rather than quantities", "adjective", "The interviews provided qualitative evidence.", "methods"],
  ["quantify", "정량화하다", "to measure or express as a number", "verb", "The score quantified uncertainty.", "statistics"],
  ["rationale", "근거, 이유", "the reason behind a choice or action", "noun", "The rationale for exclusion was documented.", "academic"],
  ["residual", "잔차, 남은 것", "what remains after other effects are removed", "noun", "The residual was modeled as noise.", "statistics"],
  ["simultaneous", "동시의", "happening at the same time", "adjective", "Simultaneous cues improved detection.", "methods"],
  ["specificity", "특이성, 구체성", "the quality of being specific", "noun", "The measure had high specificity.", "statistics"],
  ["systematic", "체계적인, 일관된", "done according to a fixed plan or pattern", "adjective", "The error was systematic, not random.", "methods"],
  ["temporal", "시간적인", "relating to time", "adjective", "The temporal pattern changed after learning.", "neuroscience"],
  ["transform", "변환하다, 변화시키다", "to change in form or appearance", "verb", "The values were transformed before analysis.", "methods"],
  ["variable", "변수", "a value that can change", "noun", "The dependent variable was reaction time.", "statistics"],
  ["viable", "실행 가능한, 생존 가능한", "capable of working successfully", "adjective", "The approach was viable with fewer trials.", "methods"],
  ["visualize", "시각화하다", "to represent something visually", "verb", "The graph visualized the distribution.", "methods"],
  ["weighted", "가중된", "adjusted by assigning different importance", "adjective", "A weighted average reduced noise.", "statistics"],
  ["assumption", "가정", "something accepted as true for reasoning", "noun", "The assumption was tested directly.", "academic"],
  ["longitudinal", "종단적인", "following the same subjects over time", "adjective", "The longitudinal design tracked change.", "methods"],
  ["calibration", "보정, 조정", "the process of adjusting a measurement system", "noun", "Calibration improved confidence estimates.", "statistics"],
  ["covariance", "공분산", "a measure of how two variables vary together", "noun", "The covariance matrix was regularized.", "statistics"],
  ["distribution", "분포", "the way values are spread", "noun", "The distribution was skewed.", "statistics"],
  ["sampling", "표본 추출", "the process of selecting observations", "noun", "Sampling was stratified by age.", "statistics"],
  ["anomalous", "이례적인, 이상한", "deviating from what is normal or expected", "adjective", "An anomalous value was inspected before analysis.", "statistics"],
  ["regression", "회귀", "a statistical method for modeling relationships", "noun", "Regression explained additional variance.", "statistics"],
  ["likelihood", "가능도, 가능성", "a measure of how well data support a model", "noun", "The likelihood favored the simpler model.", "statistics"],
  ["posterior", "사후의, 사후분포", "updated after observing evidence", "adjective", "The posterior estimate shifted upward.", "statistics"],
  ["cortical", "피질의", "relating to the cerebral cortex", "adjective", "Cortical activity increased during encoding.", "neuroscience"],
  ["neural", "신경의", "relating to nerves or the nervous system", "adjective", "Neural responses predicted behavior.", "neuroscience"],
  ["neuron", "뉴런, 신경세포", "a nerve cell", "noun", "The neuron responded to visual input.", "neuroscience"],
  ["synaptic", "시냅스의", "relating to connections between neurons", "adjective", "Synaptic plasticity supports learning.", "neuroscience"],
  ["inhibition", "억제", "the act of reducing or preventing activity", "noun", "Inhibition improved with practice.", "neuroscience"],
  ["excitatory", "흥분성의", "increasing the chance of activity", "adjective", "Excitatory input changed the response.", "neuroscience"],
  ["activation", "활성화", "the process of becoming active", "noun", "Activation was stronger in the left cortex.", "neuroscience"],
  ["connectivity", "연결성", "the degree to which parts are connected", "noun", "Functional connectivity increased after training.", "neuroscience"],
  ["hippocampal", "해마의", "relating to the hippocampus", "adjective", "Hippocampal activity supported memory.", "neuroscience"],
  ["perceptual", "지각의", "relating to perception", "adjective", "Perceptual learning improved discrimination.", "neuroscience"],
  ["selective", "선택적인", "focused on particular things rather than all things", "adjective", "Selective attention modulated the response.", "neuroscience"],
  ["sensory", "감각의", "relating to the senses", "adjective", "Sensory evidence accumulated over time.", "neuroscience"],
  ["reinforcement", "강화", "a consequence that strengthens behavior", "noun", "Reinforcement improved choice accuracy.", "neuroscience"],
  ["oscillation", "진동, 주기적 변동", "a repeated back-and-forth variation", "noun", "Theta oscillation increased during recall.", "neuroscience"],
  ["lateral", "측면의, 가쪽의", "toward the side", "adjective", "The lateral region showed stronger activation.", "neuroscience"],
  ["medial", "중앙의, 안쪽의", "toward the middle", "adjective", "The medial region was active during retrieval.", "neuroscience"]
];

const projectSourceId = "vocabreminder-project-authored-seed-v1";
const seen = new Set();
const projectCatalog = entries.map(([lemma, meaningKo, definitionEn, partOfSpeech, exampleSentence, topic]) => {
  if (seen.has(lemma)) throw new Error(`Duplicate lemma: ${lemma}`);
  seen.add(lemma);
  const frequency = ranks.get(lemma.toLowerCase());
  if (!frequency) throw new Error(`Missing SUBTLEX entry: ${lemma}`);
  return {
    lemma,
    meaningKo,
    definitionEn,
    partOfSpeech,
    exampleSentence,
    frequencyRank: frequency.rank,
    frequencyCount: frequency.count,
    topic,
    difficultyBand: difficultyBand(frequency.rank),
    entryId: `recommendation:${lemma}`,
    contentSourceId: projectSourceId,
    reviewStatus: "curated_at_authoring"
  };
});

const promotionPath = resolve(process.env.RECOMMENDATION_REVIEWED_PROMOTIONS ??
  resolve(repoRoot, "shared/recommendations/recommendation_reviewed_promotions.json"));
const promotionText = existsSync(promotionPath) ? readFileSync(promotionPath, "utf8") : null;
const promotionArtifact = promotionText ? JSON.parse(promotionText) : null;
const validatedPromotions = promotionArtifact ? validatePromotionArtifact(promotionArtifact) : { sources: new Map(), entries: [] };
const promotedCatalog = validatedPromotions.entries.map((entry) => {
  if (seen.has(entry.lemma)) throw new Error(`Promoted lemma collides with existing catalogue: ${entry.lemma}`);
  seen.add(entry.lemma);
  return {
    lemma: entry.lemma,
    meaningKo: entry.meaningKo,
    definitionEn: entry.definitionEn,
    partOfSpeech: entry.partOfSpeech,
    exampleSentence: entry.exampleSentence,
    frequencyRank: entry.frequencyRank,
    frequencyCount: entry.frequencyCount,
    topic: entry.topic,
    difficultyBand: difficultyBand(entry.frequencyRank),
    entryId: `recommendation:${entry.lemma}`,
    contentSourceId: entry.provenance.sourceId,
    reviewStatus: "approved",
    reviewedContentChecksumSha256: entry.review.contentChecksumSha256,
    review: entry.review,
    provenance: entry.provenance
  };
});
const catalog = [...projectCatalog, ...promotedCatalog]
  .sort((a, b) => a.frequencyRank - b.frequencyRank || a.lemma.localeCompare(b.lemma, "en"));

const contentSources = [{
  sourceId: projectSourceId,
  kind: "project_authored",
  datasetName: "VocabReminder curated seed content",
  datasetVersion: "2026-07-02",
  rightsBasis: "project_authored",
  attribution: "VocabReminder project-authored curated seed content",
  redistributionAllowed: true,
  shareAlikeRequired: false,
  humanReviewPolicy: "curated_at_authoring"
}, ...[...validatedPromotions.sources.values()]
  .sort((left, right) => left.sourceId.localeCompare(right.sourceId, "en"))
  .map((source) => ({
  sourceId: source.sourceId,
  kind: "licensed_import",
  datasetName: source.datasetName,
  datasetVersion: source.datasetVersion,
  sourceUrl: source.sourceUrl,
  dataChecksumSha256: source.dataChecksumSha256,
  metadataChecksumSha256: source.metadataChecksumSha256,
  licenses: source.licenses,
  attribution: source.attribution,
  redistributionAllowed: source.redistributionAllowed,
  shareAlikeRequired: source.shareAlikeRequired,
  redistributionNotice: source.redistributionNotice,
  humanReviewPolicy: "approved_review_with_content_checksum"
}))];

const jsonPath = resolve(process.env.RECOMMENDATION_CATALOG_OUTPUT ??
  resolve(repoRoot, "shared/recommendations/recommendation_catalog.json"));
writeFileSync(jsonPath, JSON.stringify({
  schemaVersion: 2,
  catalogVersion: "2026-07-13.1",
  source: {
    package: "subtlex-word-frequencies",
    version: "2.0.0",
    license: "ISC",
    repository: "https://github.com/words/subtlex-word-frequencies",
    rankDefinition: "1-based index in index.json sorted by descending SUBTLEXus count"
  },
  contentSources,
  build: {
    policyVersion: 1,
    reviewedPromotionsIncluded: promotedCatalog.length,
    reviewedPromotionsChecksumSha256: promotionText ? sha256(promotionText) : null,
    deterministicInputs: [
      "subtlex-word-frequencies@2.0.0",
      "project-authored-seed-v1",
      ...(promotionText ? ["reviewed-promotions-sha256"] : [])
    ]
  },
  generatedAt: "2026-07-13",
  entries: catalog
}, null, 2) + "\n");

const kotlinEntries = catalog.map((entry) => {
  return [
    "        RecommendationSeed(",
    `            lemma = ${kotlinString(entry.lemma)},`,
    `            meaningKo = ${kotlinString(entry.meaningKo)},`,
    `            definitionEn = ${kotlinString(entry.definitionEn)},`,
    `            partOfSpeech = ${kotlinString(entry.partOfSpeech)},`,
    `            exampleSentence = ${kotlinString(entry.exampleSentence)},`,
    `            frequencyRank = ${entry.frequencyRank},`,
    `            topic = ${kotlinString(entry.topic)}`,
    "        )"
  ].join("\n");
}).join(",\n");

const kotlinPath = resolve(process.env.RECOMMENDATION_KOTLIN_OUTPUT ??
  resolve(repoRoot, "android/VocabReminderAndroid/app/src/main/java/com/vocabreminder/android/recommendation/RecommendationCatalog.kt"));
writeFileSync(kotlinPath, `package com.vocabreminder.android.recommendation

// Generated from shared/recommendations/recommendation_catalog.json.
// Source ranks are derived from subtlex-word-frequencies 2.0.0 (ISC/SUBTLEXus).
object RecommendationCatalog {
    val seeds: List<RecommendationSeed> = listOf(
${kotlinEntries}
    )
}
`);

const swiftEntries = catalog.map((entry) => {
  return [
    "        RecommendationSeed(",
    `            lemma: ${swiftString(entry.lemma)},`,
    `            meaningKo: ${swiftString(entry.meaningKo)},`,
    `            definitionEn: ${swiftString(entry.definitionEn)},`,
    `            partOfSpeech: ${swiftString(entry.partOfSpeech)},`,
    `            exampleSentence: ${swiftString(entry.exampleSentence)},`,
    `            frequencyRank: ${entry.frequencyRank},`,
    `            topic: ${swiftString(entry.topic)}`,
    "        )"
  ].join("\n");
}).join(",\n");

const swiftPath = resolve(process.env.RECOMMENDATION_SWIFT_OUTPUT ??
  resolve(repoRoot, "mac/VocabReminderMac/Sources/VocabReminderMac/RecommendationCatalog.swift"));
writeFileSync(swiftPath, `import Foundation

// Generated from shared/recommendations/recommendation_catalog.json.
// Source ranks are derived from subtlex-word-frequencies 2.0.0 (ISC/SUBTLEXus).
enum RecommendationCatalog {
    static let seeds: [RecommendationSeed] = [
${swiftEntries}
    ]
}
`);

console.log(`Wrote ${catalog.length} recommendation seeds`);

function kotlinString(value) {
  return JSON.stringify(value);
}

function swiftString(value) {
  return JSON.stringify(value);
}

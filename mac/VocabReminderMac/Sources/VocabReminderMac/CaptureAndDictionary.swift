import Foundation
import NaturalLanguage
#if canImport(CoreServices)
import CoreServices
#endif

enum CaptureNormalizer {
    private static let stopWords: Set<String> = [
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "to", "of", "in", "on",
        "for", "with", "from", "by", "at", "as", "is", "are", "was", "were", "be", "been",
        "this", "that", "these", "those", "it", "its", "their", "there", "we", "you", "they"
    ]

    static func analyze(_ rawText: String) -> CaptureAnalysis {
        let normalized = normalize(rawText)
        let tokens = normalized.matches(of: /[A-Za-z][A-Za-z'-]*/).map { normalize(String($0.output)) }
        let displayWord: String
        if tokens.isEmpty {
            displayWord = normalized
        } else if tokens.count <= 3 {
            displayWord = tokens.joined(separator: " ")
        } else {
            displayWord = tokens[0]
        }
        let candidates = Array(NSOrderedSet(array: tokens.map { $0.lowercased() }.filter { $0.count > 2 && !stopWords.contains($0) })) as? [String] ?? []
        return CaptureAnalysis(
            rawText: rawText,
            normalizedText: normalized,
            displayWord: displayWord,
            lemma: lemma(displayWord),
            wordCount: tokens.count,
            sourceSentence: tokens.count >= 4 ? normalizeSentence(rawText) : "",
            candidates: Array(candidates.prefix(8))
        )
    }

    static func analyzeSelection(rawText: String, selectedWord: String) -> CaptureAnalysis {
        var analysis = analyze(selectedWord)
        analysis.rawText = rawText
        analysis.sourceSentence = normalizeSentence(rawText)
        return analysis
    }

    static func normalize(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: #" "'“”‘’[]{}()<>,.;:!?"#))
    }

    private static func normalizeSentence(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    static func lemma(_ displayWord: String) -> String {
        let normalized = normalize(displayWord).lowercased()
        if normalized.contains(" ") { return normalized }

        let tagger = NLTagger(tagSchemes: [.lemma])
        tagger.string = normalized
        let tag = tagger.tag(at: normalized.startIndex, unit: .word, scheme: .lemma).0?.rawValue
        if let tag, !tag.isEmpty, tag.lowercased() != normalized {
            return tag.lowercased()
        }
        if normalized.hasSuffix("ies"), normalized.count > 4 { return String(normalized.dropLast(3)) + "y" }
        if normalized.hasSuffix("ied"), normalized.count > 4 { return String(normalized.dropLast(3)) + "y" }
        if normalized.hasSuffix("ly"), normalized.count > 5 { return String(normalized.dropLast(2)) }
        if normalized.hasSuffix("s"),
           !normalized.hasSuffix("ss"),
           !normalized.hasSuffix("us"),
           !normalized.hasSuffix("ous"),
           normalized.count > 3 {
            return String(normalized.dropLast())
        }
        return normalized
    }
}

private struct LocalEntry {
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
}

enum DictionaryResolver {
    private static let localEntries: [String: LocalEntry] = [
        "tentative": LocalEntry(meaningKo: "잠정적인, 임시의", definitionEn: "not certain or fixed", partOfSpeech: "adjective", exampleSentence: "The results are tentative."),
        "robust": LocalEntry(meaningKo: "견고한, 안정적인", definitionEn: "strong and unlikely to fail", partOfSpeech: "adjective", exampleSentence: "The model showed robust decoding performance."),
        "elicit": LocalEntry(meaningKo: "이끌어내다, 유도하다", definitionEn: "to draw out a response, answer, or fact", partOfSpeech: "verb", exampleSentence: "The stimulus elicited a strong response."),
        "ubiquitous": LocalEntry(meaningKo: "어디에나 있는, 아주 흔한", definitionEn: "present or found everywhere", partOfSpeech: "adjective", exampleSentence: "Smartphones are ubiquitous these days."),
        "salient": LocalEntry(meaningKo: "두드러진, 현저한", definitionEn: "most noticeable or important", partOfSpeech: "adjective", exampleSentence: "The salient feature was the delayed response."),
        "mitigate": LocalEntry(meaningKo: "완화하다, 줄이다", definitionEn: "to make something less harmful or serious", partOfSpeech: "verb", exampleSentence: "The protocol mitigated measurement noise.")
    ]

    static func resolve(_ rawText: String, sourceApp: String = "Mac") -> DictionaryResult {
        resolve(CaptureNormalizer.analyze(rawText), sourceApp: sourceApp)
    }

    static func resolve(_ analysis: CaptureAnalysis, sourceApp: String = "Mac") -> DictionaryResult {
        if let macDefinition = MacDictionary.lookup(analysis.displayWord), !macDefinition.isEmpty {
            let entry = localEntries[analysis.lemma]
            // guide 10.3: Mac system dictionary is preserved as a definition candidate; Korean fallback can still come from local/LLM later.
            return DictionaryResult(
                lemma: analysis.lemma,
                displayWord: analysis.displayWord,
                meaningKo: entry?.meaningKo ?? "",
                definitionEn: entry?.definitionEn ?? String(macDefinition.prefix(240)),
                partOfSpeech: entry?.partOfSpeech ?? "",
                exampleSentence: analysis.sourceSentence.isEmpty ? (entry?.exampleSentence ?? "") : analysis.sourceSentence,
                rawSource: macDefinition,
                sourceType: "mac_dictionary",
                confidence: 0.86,
                sourceSentence: analysis.sourceSentence,
                sourceApp: sourceApp
            )
        }

        if let entry = localEntries[analysis.lemma] {
            return DictionaryResult(
                lemma: analysis.lemma,
                displayWord: analysis.displayWord,
                meaningKo: entry.meaningKo,
                definitionEn: entry.definitionEn,
                partOfSpeech: entry.partOfSpeech,
                exampleSentence: analysis.sourceSentence.isEmpty ? entry.exampleSentence : analysis.sourceSentence,
                rawSource: "\(entry.partOfSpeech): \(entry.definitionEn)",
                sourceType: "local_dictionary",
                confidence: 0.82,
                sourceSentence: analysis.sourceSentence,
                sourceApp: sourceApp
            )
        }

        return DictionaryResult(
            lemma: analysis.lemma,
            displayWord: analysis.displayWord,
            meaningKo: "",
            definitionEn: "",
            partOfSpeech: "",
            exampleSentence: analysis.sourceSentence,
            rawSource: "",
            sourceType: "user",
            confidence: 0,
            sourceSentence: analysis.sourceSentence,
            sourceApp: sourceApp
        )
    }
}

enum MacDictionary {
    static func lookup(_ text: String) -> String? {
        #if canImport(CoreServices)
        let nsText = text as NSString
        let range = CFRange(location: 0, length: nsText.length)
        guard let definition = DCSCopyTextDefinition(nil, text as CFString, range) else {
            return nil
        }
        return definition.takeRetainedValue() as String
        #else
        return nil
        #endif
    }
}

import Foundation
import NaturalLanguage
#if canImport(CoreServices)
import CoreServices
#endif

#if canImport(CoreServices)
@_silgen_name("DCSCopyAvailableDictionaries")
private func DCSCopyAvailableDictionaries() -> Unmanaged<CFArray>?

@_silgen_name("DCSDictionaryGetName")
private func DCSDictionaryGetName(_ dictionary: CFTypeRef) -> Unmanaged<CFString>?

@_silgen_name("DCSDictionaryGetShortName")
private func DCSDictionaryGetShortName(_ dictionary: CFTypeRef) -> Unmanaged<CFString>?
#endif

package enum CaptureNormalizer {
    private static let urlLikeSpanPattern = #"\b(?:https?://|www\.)\S+|\b[A-Za-z0-9.-]+\.(?:com|net|org|edu|gov|io|dev|app|co|kr|jp|cn|uk|de|fr|php|html?)\S*"#
    private static let stopWords: Set<String> = [
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "to", "of", "in", "on",
        "for", "with", "from", "by", "at", "as", "is", "are", "was", "were", "be", "been",
        "this", "that", "these", "those", "it", "its", "their", "there", "we", "you", "they"
    ]

    package static func analyze(_ rawText: String) -> CaptureAnalysis {
        let normalized = normalize(rawText)
        let tokenSource = normalized.replacingOccurrences(of: urlLikeSpanPattern, with: " ", options: [.regularExpression, .caseInsensitive])
        let tokens = tokenSource.matches(of: /[A-Za-z][A-Za-z'-]*/).map { normalize(String($0.output)) }
        let contentTokens = tokens.filter { $0.count > 2 && !stopWords.contains($0.lowercased()) }
        let displayWord: String
        if tokens.isEmpty {
            displayWord = normalized
        } else if tokens.count <= 3, contentTokens.count == 1 {
            displayWord = contentTokens[0]
        } else if tokens.count <= 3 {
            displayWord = tokens.joined(separator: " ")
        } else {
            displayWord = tokens[0]
        }
        let candidates = Array(NSOrderedSet(array: contentTokens.map { $0.lowercased() })) as? [String] ?? []
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

    package static func analyzeSelection(rawText: String, selectedWord: String) -> CaptureAnalysis {
        var analysis = analyze(selectedWord)
        analysis.rawText = rawText
        analysis.sourceSentence = normalizeSentence(rawText)
        return analysis
    }

    package static func normalize(_ value: String) -> String {
        stripNonASCIISymbols(value)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: #" "'“”‘’[]{}()<>,.;:!?"#))
    }

    private static func normalizeSentence(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    package static func lemma(_ displayWord: String) -> String {
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
        if normalized.hasSuffix("ing"), normalized.count > 5 { return restoreSilentE(trimTrailingDoubleConsonant(String(normalized.dropLast(3)))) }
        if normalized.hasSuffix("ated"), normalized.count > 5 { return String(normalized.dropLast()) }
        if normalized.hasSuffix("ed"), normalized.count > 4 { return restoreSilentE(trimTrailingDoubleConsonant(String(normalized.dropLast(2)))) }
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

    /// "baptiz" -> "baptize", "lov" -> "love", "judg" -> "judge": stems that
    /// cannot end an English word lost a silent e to the -ed/-ing ending.
    private static func restoreSilentE(_ stem: String) -> String {
        stem.range(of: #"(?:[^aeiou]iz|yz|[^aeious]is|v|[^k]c|dg|u|[^aeiou]os)$"#, options: .regularExpression) == nil ? stem : stem + "e"
    }

    /// "stopped" -> "stop", but "kissed" -> "kiss": a doubled s, f or z belongs to the word.
    private static func trimTrailingDoubleConsonant(_ value: String) -> String {
        guard value.count >= 2,
              let last = value.last,
              last == value.dropLast().last,
              !"aeiousfz".contains(last)
        else {
            return value
        }
        return String(value.dropLast())
    }

    private static func stripNonASCIISymbols(_ value: String) -> String {
        String(value.unicodeScalars.filter { scalar in
            if scalar.value < 128 { return true }
            switch scalar.properties.generalCategory {
            case .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
                return false
            default:
                return true
            }
        })
    }
}

private struct LocalEntry {
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
}

package struct ParsedMacDefinition {
    package init(
        definitionEn: String,
        exampleSentence: String,
        partOfSpeech: String
    ) {
        self.definitionEn = definitionEn
        self.exampleSentence = exampleSentence
        self.partOfSpeech = partOfSpeech
    }

    package var definitionEn: String
    package var exampleSentence: String
    package var partOfSpeech: String
}

package enum DictionaryResolver {
    private static let localEntries: [String: LocalEntry] = [
        "tentative": LocalEntry(meaningKo: "잠정적인, 임시의", definitionEn: "not certain or fixed", partOfSpeech: "adjective", exampleSentence: "The results are tentative."),
        "robust": LocalEntry(meaningKo: "견고한, 안정적인", definitionEn: "strong and unlikely to fail", partOfSpeech: "adjective", exampleSentence: "The model showed robust decoding performance."),
        "behemoth": LocalEntry(meaningKo: "거대한 것, 거대한 짐승", definitionEn: "something enormous, powerful, or hard to control", partOfSpeech: "noun", exampleSentence: "The startup became a behemoth in the software industry."),
        "look": LocalEntry(meaningKo: "보다, 바라보다; 찾아보다", definitionEn: "to direct your eyes toward something; to search or check", partOfSpeech: "verb", exampleSentence: "She was looking for local dining spots."),
        "elicit": LocalEntry(meaningKo: "이끌어내다, 유도하다", definitionEn: "to draw out a response, answer, or fact", partOfSpeech: "verb", exampleSentence: "The stimulus elicited a strong response."),
        "ubiquitous": LocalEntry(meaningKo: "어디에나 있는, 아주 흔한", definitionEn: "present or found everywhere", partOfSpeech: "adjective", exampleSentence: "Smartphones are ubiquitous these days."),
        "salient": LocalEntry(meaningKo: "두드러진, 현저한", definitionEn: "most noticeable or important", partOfSpeech: "adjective", exampleSentence: "The salient feature was the delayed response."),
        "resilient": LocalEntry(meaningKo: "회복력 있는, 탄력 있는", definitionEn: "able to recover quickly from difficulty", partOfSpeech: "adjective", exampleSentence: "A resilient system recovers quickly."),
        "mitigate": LocalEntry(meaningKo: "완화하다, 줄이다", definitionEn: "to make something less harmful or serious", partOfSpeech: "verb", exampleSentence: "The protocol mitigated measurement noise.")
    ]

    package static func resolve(_ rawText: String, sourceApp: String = "Mac") -> DictionaryResult {
        resolve(CaptureNormalizer.analyze(rawText), sourceApp: sourceApp)
    }

    package static func resolve(_ analysis: CaptureAnalysis, sourceApp: String = "Mac") -> DictionaryResult {
        resolve(analysis, sourceApp: sourceApp, lookup: MacDictionary.lookup)
    }

    package static func resolve(_ analysis: CaptureAnalysis, sourceApp: String = "Mac", lookup: (String) -> String?) -> DictionaryResult {
        if let macDefinition = lookup(analysis.displayWord), !macDefinition.isEmpty {
            let entry = localEntries[analysis.lemma]
            let parsed = parseEnglishDefinition(from: macDefinition)
            let meaningKo = extractKoreanMeaning(from: macDefinition) ?? entry?.meaningKo ?? ""
            // The Korean-English dictionary has no English definition; what the
            // English parser finds there is the headword ("af·fin·i·ty").
            let isKoreanEntry = containsHangul(macDefinition)
            // guide 10.3: Mac system dictionary is preserved as a definition candidate; Korean fallback can still come from local/LLM later.
            return DictionaryResult(
                lemma: analysis.lemma,
                displayWord: analysis.displayWord,
                meaningKo: meaningKo,
                definitionEn: entry?.definitionEn ?? (isKoreanEntry ? "" : parsed.definitionEn),
                partOfSpeech: entry?.partOfSpeech ?? (isKoreanEntry ? koreanDictionaryPartOfSpeech(from: macDefinition) : parsed.partOfSpeech),
                exampleSentence: analysis.sourceSentence.isEmpty ? (entry?.exampleSentence ?? (isKoreanEntry ? "" : parsed.exampleSentence)) : analysis.sourceSentence,
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
            sourceType: "pending_capture",
            confidence: 0,
            sourceSentence: analysis.sourceSentence,
            sourceApp: sourceApp
        )
    }

    private static let koreanPartsOfSpeech = "타동사|자동사|명사|동사|형용사|부사|대명사|전치사|접속사|감탄사|조동사|관사|한정사"

    /// The short Korean meaning of a macOS Korean-English dictionary entry:
    /// the first semicolon group of sense 1, at most three glosses, without
    /// grammar marks, labels, alternatives in [] or leading object markers.
    /// "bait 1 | beit | 명사1.[불][가]（낚싯바늘·덫에 다는） 미끼,먹이; 독미끼 ▸ …"
    /// becomes "미끼, 먹이". The server applies the same rule
    /// (server/src/mac_dictionary_meaning.js); both are pinned by
    /// shared/test-vectors/mac_dictionary_meanings.json.
    package static func extractKoreanMeaning(from definition: String) -> String? {
        guard containsHangul(definition) else { return nil }
        let pieces = definition.components(separatedBy: " | ")
        var text = pieces.count > 2 ? pieces.dropFirst(2).joined(separator: " | ") : definition
        // Examples (▸), idioms (숙어) and later senses are not part of sense 1.
        text = text.components(separatedBy: "▸")[0].components(separatedBy: "숙어")[0]
        let leadingPartOfSpeech = #"^\s*(?:(?:\#(koreanPartsOfSpeech))\s*[,，]?\s*)+"#
        text = text.replacingOccurrences(of: leadingPartOfSpeech, with: "", options: .regularExpression)
        text = strippingBrackets(text)
        text = text.replacingOccurrences(of: leadingPartOfSpeech, with: "", options: .regularExpression)
        // Senses are numbered "1." "2."; the first one with Korean in it is sense 1.
        let senses = splitting(text, pattern: #"(?:^|\s|(?<=[^\d\s]))\d+\s*\.(?=\s*\S)"#)
        guard let firstSense = senses.first(where: containsHangul) else { return nil }
        guard let firstGroup = splitting(firstSense, pattern: #"[;；\n]"#).first(where: containsHangul) else { return nil }
        var glosses: [String] = []
        for piece in splitting(firstGroup, pattern: #"[,，]"#) {
            let gloss = cleanedGloss(piece)
            if containsHangul(gloss), glosses.contains(gloss) == false {
                glosses.append(gloss)
            }
            if glosses.count == 3 { break }
        }
        return glosses.isEmpty ? nil : glosses.joined(separator: ", ")
    }

    /// Sense 1's part of speech in English ("noun", "verb", ...), or "".
    package static func koreanDictionaryPartOfSpeech(from definition: String) -> String {
        let pieces = definition.components(separatedBy: " | ")
        let body = (pieces.count > 2 ? pieces.dropFirst(2).joined(separator: " | ") : definition)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        for (prefixes, english) in [(["동사", "타동사", "자동사"], "verb"), (["형용사"], "adjective"), (["부사"], "adverb"), (["명사"], "noun")]
        where prefixes.contains(where: { body.hasPrefix($0) }) {
            return english
        }
        return ""
    }

    private static func containsHangul(_ value: String) -> Bool {
        value.unicodeScalars.contains { (0xAC00...0xD7A3).contains(Int($0.value)) }
    }

    private static func splitting(_ value: String, pattern: String) -> [String] {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return [value] }
        let nsValue = value as NSString
        var parts: [String] = []
        var start = 0
        for match in expression.matches(in: value, range: NSRange(location: 0, length: nsValue.length)) {
            parts.append(nsValue.substring(with: NSRange(location: start, length: match.range.location - start)))
            start = match.range.location + match.range.length
        }
        parts.append(nsValue.substring(from: start))
        return parts
    }

    private static func strippingBrackets(_ value: String) -> String {
        let patterns = [#"\[[^\[\]]*\]"#, #"〈[^〈〉]*〉"#, #"《[^《》]*》"#, #"『[^『』]*』"#, #"〔[^〔〕]*〕"#, #"（[^（）]*）"#, #"\([^()]*\)"#, #"【[^【】]*】"#]
        var current = value
        while true {
            var next = current
            for pattern in patterns {
                next = next.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
            }
            if next == current { break }
            current = next
        }
        return current.replacingOccurrences(of: #"\bcf\.\s*[A-Z][A-Z\s\d]*"#, with: "", options: .regularExpression)
    }

    private static func cleanedGloss(_ value: String) -> String {
        value
            .replacingOccurrences(of: #"^\s*(?:\#(koreanPartsOfSpeech))\s*"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"^\s*…\s*(?:을|를|에게|에|과|와|으로|로)?\s*"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"[.。]\s*$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    package static func parseEnglishDefinition(from definition: String) -> ParsedMacDefinition {
        let collapsed = definition
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let partOfSpeech = firstMatch(
            in: collapsed,
            pattern: #"\b(noun|verb|adjective|adverb|pronoun|preposition|conjunction|interjection)\b"#
        )?.lowercased() ?? ""

        let body: String
        if !partOfSpeech.isEmpty,
           let range = collapsed.range(of: #"\b\#(partOfSpeech)\b"#, options: [.regularExpression, .caseInsensitive]) {
            body = String(collapsed[range.upperBound...])
        } else {
            body = collapsed
        }

        let pieces = body.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
        let definitionPart = cleanedDefinition(String(pieces.first ?? ""))
        let examplePart = pieces.count > 1 ? cleanedExample(String(pieces[1])) : ""
        let fallback = cleanedDefinition(collapsed)
        return ParsedMacDefinition(
            definitionEn: definitionPart.isEmpty ? String(fallback.prefix(240)) : definitionPart,
            exampleSentence: examplePart,
            partOfSpeech: partOfSpeech
        )
    }

    private static func cleanedDefinition(_ value: String) -> String {
        var result = value
            .replacingOccurrences(of: #"^\s*(?:\d+[\.)]?|[①-⑳])\s*"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"^\s*\([^)]*\)\s*"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+\|\s+.*$"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while let range = result.range(of: #"^\s*\([^)]*\)\s*"#, options: .regularExpression) {
            result.removeSubrange(range)
            result = result.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return result
    }

    private static func cleanedExample(_ value: String) -> String {
        let result = value
            .replacingOccurrences(of: #"\s+\|\s+.*$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"\s+\d+\s+\([^)]*\).*$"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ".;:")))
        return String(result.prefix(180))
    }

    private static func firstMatch(in value: String, pattern: String) -> String? {
        guard let range = value.range(of: pattern, options: [.regularExpression, .caseInsensitive]) else {
            return nil
        }
        return String(value[range])
    }
}

package enum MacDictionary {
    package static func koreanEnglishDictionaryIsAvailable() -> Bool {
        #if canImport(CoreServices)
        return koreanEnglishDictionary() != nil
        #else
        return false
        #endif
    }

    package static func lookup(_ text: String) -> String? {
        #if canImport(CoreServices)
        let nsText = text as NSString
        let range = CFRange(location: 0, length: nsText.length)
        if let koreanEnglish = koreanEnglishDictionary(),
           let definition = DCSCopyTextDefinition(koreanEnglish, text as CFString, range)?.takeRetainedValue() as String?,
           definition.isEmpty == false {
            return definition
        }
        guard let definition = DCSCopyTextDefinition(nil, text as CFString, range) else {
            return nil
        }
        return definition.takeRetainedValue() as String
        #else
        return nil
        #endif
    }

    #if canImport(CoreServices)
    private static func koreanEnglishDictionary() -> DCSDictionary? {
        guard let unmanaged = DCSCopyAvailableDictionaries() else { return nil }
        let dictionaries = unmanaged.takeRetainedValue() as NSArray
        for item in dictionaries {
            let dictionary = item as! DCSDictionary
            let name = DCSDictionaryGetName(dictionary as CFTypeRef)?.takeUnretainedValue() as String? ?? ""
            let shortName = DCSDictionaryGetShortName(dictionary as CFTypeRef)?.takeUnretainedValue() as String? ?? ""
            if name.contains("영한") ||
                name.localizedCaseInsensitiveContains("Korean - English") ||
                shortName.localizedCaseInsensitiveContains("Korean - English") {
                return dictionary
            }
        }
        return nil
    }
    #endif
}

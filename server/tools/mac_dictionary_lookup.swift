import CoreServices
import Darwin
import Foundation

@_silgen_name("DCSCopyAvailableDictionaries")
private func DCSCopyAvailableDictionaries() -> Unmanaged<CFArray>?

@_silgen_name("DCSDictionaryGetName")
private func DCSDictionaryGetName(_ dictionary: CFTypeRef) -> Unmanaged<CFString>?

@_silgen_name("DCSDictionaryGetShortName")
private func DCSDictionaryGetShortName(_ dictionary: CFTypeRef) -> Unmanaged<CFString>?

struct DictionaryOutput: Encodable {
    let lemma: String
    let meaningKo: String
    let definitionEn: String
    let partOfSpeech: String
    let exampleSentence: String
    let sourceType: String
    let confidence: Double
}

private func lookup(_ text: String) -> String? {
    let nsText = text as NSString
    let range = CFRange(location: 0, length: nsText.length)
    if let koreanEnglish = koreanEnglishDictionary(),
       let definition = DCSCopyTextDefinition(koreanEnglish, text as CFString, range)?.takeRetainedValue() as String?,
       !definition.isEmpty {
        return definition
    }
    guard let definition = DCSCopyTextDefinition(nil, text as CFString, range) else {
        return nil
    }
    return definition.takeRetainedValue() as String
}

private func koreanEnglishDictionary() -> DCSDictionary? {
    guard let available = DCSCopyAvailableDictionaries() else { return nil }
    for item in available.takeRetainedValue() as NSArray {
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

private func koreanMeaning(from definition: String) -> String? {
    let stripped = definition.replacingOccurrences(
        of: #"\[[^\]]+\]|\([^)]*\)|/[^\n/]+/"#,
        with: " ",
        options: .regularExpression
    )
    for rawPart in stripped.components(separatedBy: CharacterSet(charactersIn: "\n\r•·")) {
        var part = rawPart
            .replacingOccurrences(of: #"▸.*$"#, with: "", options: .regularExpression)
            .replacingOccurrences(
                of: #"^\s*(?:\d+[\.)]?|[①-⑳]|noun|verb|adjective|adverb|명사|동사|형용사|부사)\s*[:：]?\s*"#,
                with: "",
                options: [.regularExpression, .caseInsensitive]
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let firstKorean = part.firstIndex(where: { character in
            character.unicodeScalars.contains { (0xAC00...0xD7A3).contains(Int($0.value)) }
        }) else {
            continue
        }
        part = String(part[firstKorean...])
            .replacingOccurrences(
                of: #"^\s*(?:명사|동사|형용사|부사)\s*\d*[\.)]?\s*"#,
                with: "",
                options: .regularExpression
            )
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: " .。;:：,|"))
        if !part.isEmpty { return String(part.prefix(200)) }
    }
    return nil
}

private func englishDefinition(from definition: String, hasKoreanMeaning: Bool) -> String {
    if hasKoreanMeaning { return "" }
    return String(
        definition
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(2_000)
    )
}

private func partOfSpeech(from definition: String) -> String {
    let pattern = #"\b(noun|verb|adjective|adverb|pronoun|preposition|conjunction|interjection)\b"#
    if let range = definition.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
        return String(definition[range]).lowercased()
    }
    for (korean, english) in [("형용사", "adjective"), ("부사", "adverb"), ("동사", "verb"), ("명사", "noun")] {
        if definition.contains(korean) { return english }
    }
    return ""
}

guard CommandLine.arguments.count == 2 else {
    FileHandle.standardError.write(Data("usage: mac_dictionary_lookup <lemma>\n".utf8))
    Darwin.exit(64)
}

let lemma = CommandLine.arguments[1]
guard !lemma.isEmpty, lemma.count <= 80, let rawDefinition = lookup(lemma), !rawDefinition.isEmpty else {
    Darwin.exit(2)
}

let meaningKo = koreanMeaning(from: rawDefinition) ?? ""
let output = DictionaryOutput(
    lemma: lemma,
    meaningKo: meaningKo,
    definitionEn: englishDefinition(from: rawDefinition, hasKoreanMeaning: !meaningKo.isEmpty),
    partOfSpeech: partOfSpeech(from: rawDefinition),
    exampleSentence: "",
    sourceType: "mac_dictionary",
    confidence: meaningKo.isEmpty ? 0.68 : 0.78
)

do {
    let data = try JSONEncoder().encode(output)
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data("\n".utf8))
} catch {
    Darwin.exit(1)
}

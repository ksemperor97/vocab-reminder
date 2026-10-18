import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
final class CaptureAndDictionaryTests {
    @Test func testKoreanMeaningPreservesMiddleDotInsideDefinition() {
        let definition = """
        com·pla·cent | kəmpléisnt | 형용사1.（결점은 깨닫지 못하고 자기의 장점·입장 등에 대하여） 기뻐하고 있는, 자기 만족의 \
        ▸ a complacent smile 혼자 흐뭇해하는 미소.
        """

        #expect(DictionaryResolver.extractKoreanMeaning(from: definition) == "기뻐하고 있는, 자기 만족의")
    }

    /// shared/test-vectors/mac_dictionary_meanings.json: real entries from the
    /// macOS Korean-English dictionary and the short meaning a card shows.
    @Test func testSharedMacDictionaryVectors() throws {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var url = directory.appendingPathComponent("shared/test-vectors/mac_dictionary_meanings.json")
        while FileManager.default.fileExists(atPath: url.path) == false, directory.path != "/" {
            directory.deleteLastPathComponent()
            url = directory.appendingPathComponent("shared/test-vectors/mac_dictionary_meanings.json")
        }
        let payload = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        let cases = try #require(payload?["cases"] as? [[String: Any]])
        #expect(cases.isEmpty == false)
        for entry in cases {
            let raw = try #require(entry["raw"] as? String)
            let lemma = entry["lemma"] as? String ?? "?"
            #expect(DictionaryResolver.extractKoreanMeaning(from: raw) == entry["expected_meaning_ko"] as? String, "\(lemma)")
            #expect(DictionaryResolver.koreanDictionaryPartOfSpeech(from: raw) == entry["expected_part_of_speech"] as? String, "\(lemma)")
            let result = DictionaryResolver.resolve(CaptureNormalizer.analyze(lemma), sourceApp: "test") { _ in raw }
            #expect(result.definitionEn.isEmpty, "\(lemma) English definition: \(result.definitionEn)")
        }
    }
}

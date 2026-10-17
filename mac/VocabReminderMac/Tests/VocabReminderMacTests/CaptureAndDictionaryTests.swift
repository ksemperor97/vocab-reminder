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

        #expect(DictionaryResolver.extractKoreanMeaning(from: definition) == "（결점은 깨닫지 못하고 자기의 장점·입장 등에 대하여） 기뻐하고 있는, 자기 만족의")
    }
}

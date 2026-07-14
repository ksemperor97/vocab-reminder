import Foundation
@testable import VocabReminderMac

final class DictionaryURLProtocolStub: URLProtocol, @unchecked Sendable {
    typealias Handler = (URLRequest) throws -> (status: Int, headers: [String: String], data: Data)

    nonisolated(unsafe) static var handler: Handler?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            let output = try handler(request)
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: output.status,
                httpVersion: "HTTP/1.1",
                headerFields: output.headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: output.data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DictionaryURLProtocolStub.self]
        return URLSession(configuration: configuration)
    }

    static func jsonData(_ object: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: object)
    }

    static func bodyData(from request: URLRequest) -> Data? {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { return nil }
            if count == 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

final class LockedRequestCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var storage = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func increment() {
        lock.lock()
        storage += 1
        lock.unlock()
    }
}

@MainActor
func makeBlankMeaningStore(words: [String]) throws -> (store: LocalVocabularyStore, directory: URL) {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("VocabReminderDictionaryTests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
    for word in words {
        store.capture(text: word, method: .manual, sourceApp: "XCTest")
        store.savePreview(meaningOverride: "", exampleOverride: "", method: .manual)
    }
    return (store, directory)
}

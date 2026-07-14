import Foundation
import Network

@MainActor
final class MacAutoSyncController {
    static let shared = MacAutoSyncController(monitorNetwork: true, scheduleRetryTasks: true)

    private static let afterChangeDelayNanoseconds: UInt64 = 1_000_000_000
    static let timerIntervalNanoseconds: UInt64 = 15 * 60 * 1_000_000_000
    static let endpointUnavailableRetryDelay: TimeInterval = 60 * 60

    private weak var store: LocalVocabularyStore?
    private var syncTask: Task<Void, Never>?
    private var debounceTask: Task<Void, Never>?
    private var timerTask: Task<Void, Never>?
    private var enrichmentRetryTask: Task<Void, Never>?
    private var pendingSyncAfterCurrent = false
    private let session: URLSession
    private let nowProvider: @Sendable () -> Date
    private let retryLedger: DictionaryEnrichmentRetryLedger
    private let scheduleRetryTasks: Bool
    private var networkMonitor: NWPathMonitor?

    init(
        defaults: UserDefaults = .standard,
        retryDefaultsKey: String = "dictionaryEnrichmentRetryLedgerV1",
        session: URLSession = .shared,
        nowProvider: @escaping @Sendable () -> Date = Date.init,
        monitorNetwork: Bool = false,
        scheduleRetryTasks: Bool = false
    ) {
        self.session = session
        self.nowProvider = nowProvider
        self.retryLedger = DictionaryEnrichmentRetryLedger(defaults: defaults, key: retryDefaultsKey)
        self.scheduleRetryTasks = scheduleRetryTasks
        if monitorNetwork {
            startNetworkMonitor()
        }
    }

    func start(store: LocalVocabularyStore) {
        self.store = store
        syncNow(reason: "startup")
        guard timerTask == nil else { return }
        timerTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.timerIntervalNanoseconds)
                self?.syncNow(reason: "timer")
            }
        }
    }

    func enqueueAfterLocalChange() {
        guard store != nil else { return }
        debounceTask?.cancel()
        debounceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.afterChangeDelayNanoseconds)
            guard !Task.isCancelled else { return }
            self?.syncNow(reason: "change")
        }
    }

    func clearDictionaryEnrichmentRetry(cardId: String) {
        retryLedger.clear(cardId: cardId)
    }

    func syncNow(reason: String) {
        guard let store else { return }
        guard let credentials = SyncDefaults.effectiveCredentials() else {
            return
        }
        guard syncTask == nil else {
            pendingSyncAfterCurrent = true
            return
        }
        syncTask = Task { @MainActor [weak self, weak store] in
            defer {
                if let self = self {
                    self.syncTask = nil
                    if self.pendingSyncAfterCurrent {
                        self.pendingSyncAfterCurrent = false
                        self.syncNow(reason: "change")
                    }
                }
            }
            guard let store else { return }
            do {
                let summary = try await store.syncWithServer(
                    serverBaseURL: credentials.serverURL,
                    authToken: credentials.bearerToken
                )
                MacSyncStatusStore.recordSuccess(skippedSummary: summary.skippedSummary)
                let enrichment = await self?.performDictionaryEnrichment(
                    store: store,
                    config: CloudSyncConfig(
                        serverBaseURL: credentials.serverURL,
                        bearerToken: credentials.bearerToken
                    )
                ) ?? .idle
                if enrichment.appliedCount > 0 || enrichment.hasMoreEligibleTargets {
                    // The follow-up run pushes newly selected definition
                    // candidates immediately and drains the next bounded batch.
                    self?.pendingSyncAfterCurrent = true
                }
                if reason != "timer" && reason != "dictionary-retry" {
                    store.message = summary.skippedSummary.isEmpty
                        ? "자동 동기화 완료 · 카드 \(summary.cards), 이벤트 \(summary.reviewEvents)"
                        : "자동 동기화 완료 · 스킵 \(summary.skippedSummary)"
                }
            } catch {
                MacSyncStatusStore.recordFailure(error.localizedDescription)
                store.recordSyncFailure(error)
                if reason != "timer" && reason != "dictionary-retry" {
                    store.message = "자동 동기화 대기: \(error.localizedDescription)"
                }
            }
        }
    }

    @discardableResult
    func performDictionaryEnrichment(
        store: LocalVocabularyStore,
        config: CloudSyncConfig
    ) async -> DictionaryEnrichmentRunSummary {
        let now = nowProvider()
        let allTargets = store.pendingDictionaryEnrichmentTargets()
        let allTargetIds = Set(allTargets.map(\.cardId))
        retryLedger.prune(validCardIds: allTargetIds)

        guard allTargets.isEmpty == false else {
            enrichmentRetryTask?.cancel()
            enrichmentRetryTask = nil
            store.setDictionaryEnrichmentStatus(.idle)
            return .idle
        }

        let eligibleTargets = allTargets.filter { retryLedger.isEligible(cardId: $0.cardId, at: now) }
        guard eligibleTargets.isEmpty == false else {
            let nextRetryAt = retryLedger.nextRetryDate(cardIds: allTargetIds)
            let tally = retryLedger.tally(cardIds: allTargetIds)
            store.setDictionaryEnrichmentStatus(
                DictionaryEnrichmentStatus(
                    pendingCount: tally.pending,
                    notFoundCount: tally.notFound,
                    providerFailureCount: tally.providerFailure,
                    endpointUnavailable: store.dictionaryEnrichmentStatus.endpointUnavailable,
                    nextRetryAt: nextRetryAt
                )
            )
            scheduleEnrichmentRetry(at: nextRetryAt)
            return DictionaryEnrichmentRunSummary(nextRetryAt: nextRetryAt)
        }

        let batch = Array(eligibleTargets.prefix(CloudSyncClient.maxDictionaryItemsPerBatch))
        store.setDictionaryEnrichmentStatus(
            DictionaryEnrichmentStatus(pendingCount: batch.count)
        )

        do {
            let results = try await CloudSyncClient(config: config, session: session)
                .lookupDictionary(lemmas: batch.map(\.lemma))
            let resultByLemma = Dictionary(
                results.map { (normalizeCloudDictionaryLemma($0.lemma), $0) },
                uniquingKeysWith: { first, _ in first }
            )
            let applySummary = store.applyDictionaryEnrichment(
                results,
                requestedTargets: batch,
                now: now
            )
            let appliedIds = Set(applySummary.appliedCardIds)
            let remainingIds = Set(store.pendingDictionaryEnrichmentTargets().map(\.cardId))
            var tally = DictionaryEnrichmentOutcomeTally()

            for target in batch {
                if appliedIds.contains(target.cardId) || remainingIds.contains(target.cardId) == false {
                    retryLedger.clear(cardId: target.cardId)
                    if appliedIds.contains(target.cardId) { tally.resolved += 1 }
                    continue
                }

                let outcome = resultByLemma[target.lemma]?.outcome ?? .pending
                switch outcome {
                case .resolved:
                    // A concurrent user edit or card deletion can make a valid
                    // response inapplicable. It is already removed from the
                    // remaining blank targets above, so this branch is only a
                    // defensive short retry.
                    retryLedger.record(cardId: target.cardId, outcome: .pending, at: now)
                    tally.pending += 1
                case .pending:
                    retryLedger.record(cardId: target.cardId, outcome: .pending, at: now)
                    tally.pending += 1
                case .notFound:
                    retryLedger.record(cardId: target.cardId, outcome: .notFound, at: now)
                    tally.notFound += 1
                case .providerFailure:
                    retryLedger.record(cardId: target.cardId, outcome: .providerFailure, at: now)
                    tally.providerFailure += 1
                }
            }

            let currentTargets = store.pendingDictionaryEnrichmentTargets()
            let currentIds = Set(currentTargets.map(\.cardId))
            retryLedger.prune(validCardIds: currentIds)
            let currentTally = retryLedger.tally(cardIds: currentIds)
            let hasMoreEligible = currentTargets.contains {
                retryLedger.isEligible(cardId: $0.cardId, at: now)
            }
            let nextRetryAt = hasMoreEligible ? now : retryLedger.nextRetryDate(cardIds: currentIds)
            store.setDictionaryEnrichmentStatus(
                DictionaryEnrichmentStatus(
                    pendingCount: currentTally.pending,
                    resolvedCount: tally.resolved,
                    notFoundCount: currentTally.notFound,
                    providerFailureCount: currentTally.providerFailure,
                    nextRetryAt: nextRetryAt
                )
            )
            scheduleEnrichmentRetry(at: nextRetryAt)
            return DictionaryEnrichmentRunSummary(
                appliedCount: applySummary.appliedCount,
                hasMoreEligibleTargets: hasMoreEligible,
                nextRetryAt: nextRetryAt
            )
        } catch {
            let endpointUnavailable: Bool
            if case CloudSyncError.http(let status, _) = error {
                endpointUnavailable = status == 404 || status == 405
            } else {
                endpointUnavailable = false
            }
            let overrideDelay = endpointUnavailable ? Self.endpointUnavailableRetryDelay : nil
            for target in batch {
                retryLedger.record(
                    cardId: target.cardId,
                    outcome: .pending,
                    at: now,
                    delayOverride: overrideDelay
                )
            }
            let nextRetryAt = retryLedger.nextRetryDate(cardIds: allTargetIds)
            store.setDictionaryEnrichmentStatus(
                DictionaryEnrichmentStatus(
                    pendingCount: batch.count,
                    endpointUnavailable: endpointUnavailable,
                    nextRetryAt: nextRetryAt
                )
            )
            scheduleEnrichmentRetry(at: nextRetryAt)
            return DictionaryEnrichmentRunSummary(nextRetryAt: nextRetryAt)
        }
    }

    private func scheduleEnrichmentRetry(at date: Date?) {
        enrichmentRetryTask?.cancel()
        enrichmentRetryTask = nil
        guard scheduleRetryTasks, let date else { return }
        let delay = max(0, date.timeIntervalSince(nowProvider()))
        let nanoseconds = UInt64(min(delay, TimeInterval(UInt64.max) / 1_000_000_000) * 1_000_000_000)
        enrichmentRetryTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard Task.isCancelled == false else { return }
            self?.enrichmentRetryTask = nil
            self?.syncNow(reason: "dictionary-retry")
        }
    }

    private func startNetworkMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor [weak self] in
                self?.syncNow(reason: "network")
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.vocabreminder.mac.network-monitor"))
        networkMonitor = monitor
    }
}

struct DictionaryEnrichmentRunSummary: Equatable, Sendable {
    var appliedCount: Int = 0
    var hasMoreEligibleTargets = false
    var nextRetryAt: Date?

    static let idle = DictionaryEnrichmentRunSummary()
}

private struct DictionaryEnrichmentOutcomeTally {
    var pending = 0
    var resolved = 0
    var notFound = 0
    var providerFailure = 0
}

private struct DictionaryEnrichmentRetryEntry: Codable, Equatable {
    var outcome: CloudDictionaryOutcome
    var attemptCount: Int
    var nextRetryAt: Date
}

@MainActor
final class DictionaryEnrichmentRetryLedger {
    private let defaults: UserDefaults
    private let key: String
    private var entries: [String: DictionaryEnrichmentRetryEntry]

    init(defaults: UserDefaults, key: String) {
        self.defaults = defaults
        self.key = key
        if let data = defaults.data(forKey: key),
           let decoded = try? JSONDecoder.vocab.decode(
               [String: DictionaryEnrichmentRetryEntry].self,
               from: data
           ) {
            entries = decoded
        } else {
            entries = [:]
        }
    }

    func isEligible(cardId: String, at now: Date) -> Bool {
        guard let entry = entries[cardId] else { return true }
        return entry.nextRetryAt <= now
    }

    func record(
        cardId: String,
        outcome: CloudDictionaryOutcome,
        at now: Date,
        delayOverride: TimeInterval? = nil
    ) {
        precondition(outcome != .resolved)
        let previous = entries[cardId]
        let attemptCount = previous?.outcome == outcome ? (previous?.attemptCount ?? 0) + 1 : 1
        let delay = delayOverride ?? Self.retryDelay(outcome: outcome, attemptCount: attemptCount)
        entries[cardId] = DictionaryEnrichmentRetryEntry(
            outcome: outcome,
            attemptCount: attemptCount,
            nextRetryAt: now.addingTimeInterval(delay)
        )
        persist()
    }

    func clear(cardId: String) {
        guard entries.removeValue(forKey: cardId) != nil else { return }
        persist()
    }

    func prune(validCardIds: Set<String>) {
        let oldCount = entries.count
        entries = entries.filter { validCardIds.contains($0.key) }
        if entries.count != oldCount { persist() }
    }

    func nextRetryDate(cardIds: Set<String>) -> Date? {
        entries
            .filter { cardIds.contains($0.key) }
            .map(\.value.nextRetryAt)
            .min()
    }

    func tally(cardIds: Set<String>) -> (pending: Int, notFound: Int, providerFailure: Int) {
        var pending = 0
        var notFound = 0
        var providerFailure = 0
        for (cardId, entry) in entries where cardIds.contains(cardId) {
            switch entry.outcome {
            case .pending:
                pending += 1
            case .notFound:
                notFound += 1
            case .providerFailure:
                providerFailure += 1
            case .resolved:
                break
            }
        }
        return (pending, notFound, providerFailure)
    }

    static func retryDelay(outcome: CloudDictionaryOutcome, attemptCount: Int) -> TimeInterval {
        let index = max(0, attemptCount - 1)
        let delays: [TimeInterval]
        switch outcome {
        case .pending:
            delays = [5 * 60, 15 * 60, 60 * 60, 6 * 60 * 60]
        case .notFound:
            delays = [6 * 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60]
        case .providerFailure:
            delays = [60, 5 * 60, 15 * 60, 60 * 60]
        case .resolved:
            return 0
        }
        return delays[min(index, delays.count - 1)]
    }

    private func persist() {
        guard let data = try? JSONEncoder.vocab.encode(entries) else { return }
        defaults.set(data, forKey: key)
    }
}

enum MacSyncStatusStore {
    static func recordSuccess(skippedSummary: String = "") {
        let defaults = UserDefaults.standard
        defaults.set(Date().formatted(date: .abbreviated, time: .shortened), forKey: "lastLocalSyncSummary")
        defaults.set("", forKey: "lastLocalSyncError")
        defaults.set(skippedSummary, forKey: "lastLocalSyncSkipped")
    }

    static func recordFailure(_ error: String) {
        UserDefaults.standard.set(error, forKey: "lastLocalSyncError")
    }
}

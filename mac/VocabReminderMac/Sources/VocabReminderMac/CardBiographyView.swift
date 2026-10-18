import VocabCore
import SwiftUI

struct CardBiographyView: View {
    var card: VocabularyCard
    @ObservedObject var store: LocalVocabularyStore
    @State private var showAll = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("카드 전기")
                        .font(.headline)
                    Text("문맥 · 뜻 후보 · 복습/수정 · 동기화")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if items.count > 3 {
                    Button(showAll ? "최근 3개" : "전체 \(items.count)개") {
                        showAll.toggle()
                    }
                    .buttonStyle(.link)
                }
                if preservedAlternativeCandidateIDs.isEmpty == false {
                    BiographyBadge(text: "뜻 대안 보존", color: .orange)
                }
            }

            if card.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                HStack {
                    Text(
                        card.definitionSourcePrimary == "user"
                            ? "사용자가 뜻을 비워 자동 해석이 잠겨 있습니다."
                            : "뜻이 아직 없어 알림이 보류됩니다."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    Spacer()
                    Button("다시 해석") {
                        store.retryDictionaryEnrichment(cardId: card.id)
                    }
                    .buttonStyle(.link)
                }
            }

            if items.isEmpty {
                Text("아직 기록된 활동이 없습니다.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(visibleItems) { item in
                        CardBiographyRow(
                            item: item,
                            card: card,
                            currentPrimaryCandidateID: currentPrimaryCandidateID,
                            preservedAlternativeCandidateIDs: preservedAlternativeCandidateIDs,
                            restoreCandidate: { candidateID in
                                store.restoreDefinitionCandidate(
                                    cardId: card.id,
                                    candidateId: candidateID
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    private var items: [CardBiographyItem] {
        CardBiographyBuilder.items(
            card: card,
            contexts: store.contexts,
            candidates: store.definitionCandidates,
            reviewEvents: store.reviewEvents,
            syncEvents: store.syncEvents
        )
    }

    private var visibleItems: [CardBiographyItem] {
        showAll ? items : Array(items.prefix(3))
    }

    private var currentPrimaryCandidateID: String? {
        CardBiographyBuilder.currentPrimaryCandidateID(
            card: card,
            candidates: store.definitionCandidates
        )
    }

    private var preservedAlternativeCandidateIDs: Set<String> {
        CardBiographyBuilder.preservedAlternativeCandidateIDs(
            card: card,
            candidates: store.definitionCandidates
        )
    }
}

private struct CardBiographyRow: View {
    var item: CardBiographyItem
    var card: VocabularyCard
    var currentPrimaryCandidateID: String?
    var preservedAlternativeCandidateIDs: Set<String>
    var restoreCandidate: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            switch item.kind {
            case .context(let context):
                contextBody(context)
            case .definitionCandidate(let candidate):
                candidateBody(candidate)
            case .review(let event):
                reviewBody(event)
            case .sync(let event):
                syncBody(event)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.65), in: RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func contextBody(_ context: StoredContext) -> some View {
        HStack {
            BiographyBadge(text: "문맥", color: .blue)
            Text(biographyDate(item.occurredAt))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        if context.sourceSentence.isEmpty == false {
            Text(context.sourceSentence)
                .font(.callout)
                .textSelection(.enabled)
        }
        if context.pageTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
            Text("페이지: \(context.pageTitle)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        Text("캡처 경로: \(MacCaptureSource.mechanismLabel(for: context))")
            .font(.caption)
            .foregroundStyle(.secondary)
        if let application = MacCaptureSource.originalApplicationLabel(for: context) {
            Text("기록된 앱: \(application)")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            Text("원본 앱: 확인되지 않음")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        if let sanitized = SourceURLSanitizer.sanitize(context.sourceURL),
           let url = URL(string: sanitized) {
            Link(sanitized, destination: url)
                .font(.caption)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private func candidateBody(_ candidate: StoredDefinitionCandidate) -> some View {
        let isCurrent = candidate.id == currentPrimaryCandidateID
        let conflicts = preservedAlternativeCandidateIDs.contains(candidate.id)
        HStack(spacing: 6) {
            BiographyBadge(text: "뜻 후보", color: .purple)
            if isCurrent {
                BiographyBadge(text: "현재 primary", color: .green)
            } else if candidate.selectedAsPrimary {
                BiographyBadge(text: "primary 기록", color: .secondary)
            }
            if conflicts {
                BiographyBadge(text: "충돌/대안", color: .orange)
            }
            Spacer()
            Text(biographyDate(item.occurredAt))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        Text(candidate.parsedMeaningKo.isEmpty ? "한글 뜻 없음" : candidate.parsedMeaningKo)
            .font(.callout)
            .fontWeight(.medium)
        if candidate.parsedDefinitionEn.isEmpty == false {
            Text(candidate.parsedDefinitionEn)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        HStack {
            Text("source: \(candidate.source.isEmpty ? "unknown" : candidate.source)")
            Text("confidence: \(Int((min(max(candidate.confidence, 0), 1) * 100).rounded()))%")
            Spacer()
            if isCurrent == false {
                Button("이 후보로 복구") { restoreCandidate(candidate.id) }
                    .buttonStyle(.link)
                    .disabled(
                        candidate.parsedMeaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                            candidate.parsedDefinitionEn.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func reviewBody(_ event: StoredReviewEvent) -> some View {
        HStack {
            BiographyBadge(
                text: event.response == .manualEdit ? "수정" : "복습",
                color: event.response == .manualEdit ? .orange : .green
            )
            Text(biographyDate(item.occurredAt))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        Text(reviewEventTitle(event))
            .font(.callout)
        if event.response != .manualEdit {
            Text("\(event.previousStatus.rawValue) → \(event.nextStatus.rawValue) · 다음 학습 \(relativeBiographyDue(event.nextDueAt))")
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if let source = event.metadata["candidate_source"] {
            Text("source: \(source)")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func syncBody(_ event: StoredSyncEvent) -> some View {
        HStack {
            BiographyBadge(text: "동기화", color: event.succeeded ? .cyan : .red)
            Text(biographyDate(item.occurredAt))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        Text(event.summary)
            .font(.caption)
            .foregroundStyle(.secondary)
    }
}

private struct BiographyBadge: View {
    var text: String
    var color: Color

    var body: some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.semibold)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }
}

private func reviewEventTitle(_ event: StoredReviewEvent) -> String {
    if event.response == .manualEdit {
        switch event.metadata["action"] {
        case "definition_candidate_restored":
            return "뜻 후보를 primary로 복구"
        case "card_edit":
            return "카드 내용 수정"
        default:
            return "카드 수정"
        }
    }
    switch event.response {
    case .remembered:
        return "외웠다"
    case .forgotten:
        return "모르겠음"
    case .unavailable:
        return "나중에"
    case .reveal:
        return "뜻 보기"
    case .suspend:
        return "복습 보류"
    case .restore:
        return "복습 복구"
    case .manualEdit:
        return "카드 수정"
    }
}

private func biographyDate(_ date: Date) -> String {
    date.formatted(
        Date.FormatStyle(date: .abbreviated, time: .shortened)
            .locale(Locale(identifier: "ko_KR"))
    )
}

private func relativeBiographyDue(_ dueAt: Date, now: Date = Date()) -> String {
    guard dueAt > now else { return "지금" }
    let seconds = dueAt.timeIntervalSince(now)
    if seconds < 3_600 { return "\(Int(ceil(seconds / 60)))분 뒤" }
    if seconds < 86_400 { return "\(Int(ceil(seconds / 3_600)))시간 뒤" }
    return dueAt.formatted(date: .abbreviated, time: .shortened)
}

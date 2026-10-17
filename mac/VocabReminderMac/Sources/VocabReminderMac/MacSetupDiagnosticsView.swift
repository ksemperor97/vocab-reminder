import VocabCore
import SwiftUI

struct MacSetupDiagnosticsView: View {
    @ObservedObject var store: LocalVocabularyStore
    @AppStorage("macSetupDiagnosticsSeenV1") private var hasSeenSetup = false
    @State private var isExpanded = true
    @State private var serviceStatus = MacCaptureServiceStatus.missing
    @State private var notificationStatus = "확인 중"
    @State private var dictionaryAvailable: Bool?
    @State private var requestDiagnostic: CloudRequestDiagnostic?
    @State private var serviceInstallRunning = false

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            VStack(alignment: .leading, spacing: 10) {
                diagnosticRow(
                    title: "우클릭 캡처 Service",
                    value: serviceStatus.detail,
                    state: serviceStatus.installed && serviceStatus.enabled ? .ready : .attention
                )
                Text("정확한 위치: 시스템 설정 → 키보드 → 키보드 단축키… → 서비스 → 텍스트")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                HStack {
                    Button(serviceInstallRunning ? "설치 중…" : serviceStatus.installed ? "다시 설치" : "앱에서 설치") {
                        installCaptureService()
                    }
                    .disabled(serviceInstallRunning)
                    Button("서비스 설정 열기", action: openKeyboardSettings)
                }

                Divider()
                diagnosticRow(
                    title: "알림 권한",
                    value: notificationStatus,
                    state: notificationStatus == "허용됨" || notificationStatus == "임시 허용" ? .ready : .attention
                )
                Text("정확한 위치: 시스템 설정 → 알림 → Vocabulary Reminder")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button("알림 설정 열기", action: openNotificationPreferences)

                Divider()
                diagnosticRow(
                    title: "로그인 자동 시작",
                    value: LoginItemController.isEnabled ? "켜짐" : "꺼짐",
                    state: LoginItemController.isEnabled ? .ready : .attention
                )

                Divider()
                diagnosticRow(
                    title: "영한사전",
                    value: dictionaryStatusLabel,
                    state: dictionaryAvailable == true ? .ready : .attention
                )
                Text("정확한 위치: 사전 앱 → 설정 → 영한사전")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(enrichmentStatusLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("사전 앱 열기", action: openDictionaryApp)

                Divider()
                diagnosticRow(
                    title: "마지막 캡처",
                    value: captureStatusLabel,
                    state: captureVisualState
                )
                diagnosticRow(
                    title: "마지막 네트워크 요청",
                    value: requestStatusLabel,
                    state: requestVisualState
                )
                Text("진단에는 상태·시각·짧은 요청 ID만 표시하며 토큰, 캡처 문장, 조회 단어는 저장하지 않습니다.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                HStack {
                    Button("상태 새로고침", action: refresh)
                    Spacer()
                    Button("설정 확인 완료") {
                        hasSeenSetup = true
                        isExpanded = false
                    }
                }
            }
            .padding(.top, 8)
        } label: {
            HStack {
                Text("처음 설정 및 진단")
                    .font(.headline)
                if hasSeenSetup == false {
                    Text("첫 실행")
                        .font(.caption2)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(.blue.opacity(0.15), in: Capsule())
                }
            }
        }
        .onAppear {
            isExpanded = hasSeenSetup == false
            refresh()
        }
        .onChange(of: store.message) { _, _ in
            requestDiagnostic = CloudRequestDiagnosticsStore.load()
        }
    }

    private var dictionaryStatusLabel: String {
        switch dictionaryAvailable {
        case true: return "영한사전 활성화됨"
        case false: return "영한사전을 찾지 못함"
        case nil: return "확인 중"
        }
    }

    private var enrichmentStatusLabel: String {
        let summary = store.dictionaryEnrichmentStatus.userFacingSummary
        return summary.isEmpty ? "서버 사전 보강: 대기 작업 없음" : summary
    }

    private var captureStatusLabel: String {
        guard let record = store.lastCaptureDiagnostic else { return "기록 없음" }
        let outcome = record.outcome == .succeeded ? "성공" : "실패"
        return "\(outcome) · \(captureMechanismLabel(record.mechanism)) · \(diagnosticDate(record.occurredAt)) · \(captureDetailLabel(record.detailCode))"
    }

    private var captureVisualState: DiagnosticVisualState {
        guard let record = store.lastCaptureDiagnostic else { return .neutral }
        return record.outcome == .succeeded ? .ready : .attention
    }

    private var requestVisualState: DiagnosticVisualState {
        guard let requestDiagnostic else { return .neutral }
        return requestDiagnostic.succeeded ? .ready : .attention
    }

    private var requestStatusLabel: String {
        guard let diagnostic = requestDiagnostic else { return "기록 없음" }
        let outcome = diagnostic.succeeded ? "성공" : "실패"
        let status = diagnostic.statusCode.map { "HTTP \($0)" } ?? diagnostic.category
        return "\(outcome) · \(networkOperationLabel(diagnostic.operation)) · \(status) · 요청 \(diagnostic.shortRequestID) · \(diagnosticDate(diagnostic.occurredAt))"
    }

    @ViewBuilder
    private func diagnosticRow(title: String, value: String, state: DiagnosticVisualState) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: state.symbolName)
                .foregroundStyle(state.color)
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
            Spacer()
            Text(value)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
    }

    private func refresh() {
        serviceStatus = MacCaptureServiceManager.inspect()
        dictionaryAvailable = MacDictionary.koreanEnglishDictionaryIsAvailable()
        requestDiagnostic = CloudRequestDiagnosticsStore.load()
        Task { @MainActor in
            notificationStatus = await MacNotificationScheduler.shared.authorizationDiagnosticLabel()
        }
    }

    private func installCaptureService() {
        serviceInstallRunning = true
        Task { @MainActor in
            let failure = await Task.detached(priority: .userInitiated) { () -> String? in
                do {
                    try MacCaptureServiceManager.install()
                    return nil
                } catch {
                    return error.localizedDescription
                }
            }.value
            serviceInstallRunning = false
            if let failure {
                store.message = "Service 설치 실패: \(failure)"
            } else {
                NSUpdateDynamicServices()
                store.message = "우클릭 캡처 Service를 설치했습니다. 목록에 없으면 앱을 다시 여세요."
            }
            refresh()
        }
    }

    private func openKeyboardSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension") else { return }
        NSWorkspace.shared.open(url)
    }

    private func openNotificationPreferences() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") else { return }
        NSWorkspace.shared.open(url)
    }

    private func openDictionaryApp() {
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/Dictionary.app"))
    }
}

private enum DiagnosticVisualState {
    case ready
    case attention
    case neutral

    var symbolName: String {
        switch self {
        case .ready: return "checkmark.circle.fill"
        case .attention: return "exclamationmark.triangle.fill"
        case .neutral: return "info.circle"
        }
    }

    var color: Color {
        switch self {
        case .ready: return .green
        case .attention: return .orange
        case .neutral: return .secondary
        }
    }
}

private func captureMechanismLabel(_ value: String) -> String {
    switch value {
    case "mac_service": return "macOS Service"
    case "clipboard_hotkey": return "클립보드 단축키/버튼"
    case "url_scheme": return "URL scheme"
    case "manual": return "앱 직접 입력"
    default: return value
    }
}

private func captureDetailLabel(_ value: String) -> String {
    switch value {
    case "preview_ready": return "미리보기 준비"
    case "selection_required": return "단어 선택 대기"
    case "candidate_selected": return "단어 선택 완료"
    case "saved": return "카드 저장 완료"
    case "clipboard_empty": return "클립보드가 비어 있음"
    case "normalized_text_empty": return "저장할 텍스트 없음"
    case "service_input_empty": return "Service 입력 없음"
    case "url_scheme_invalid": return "URL scheme 입력 오류"
    default: return value
    }
}

private func networkOperationLabel(_ operation: CloudRequestOperation) -> String {
    switch operation {
    case .health: return "연결 확인"
    case .syncPush: return "동기화 보내기"
    case .syncPull: return "동기화 받기"
    case .dictionaryBatch: return "사전 보강"
    case .dictionaryLookup: return "사전 조회"
    case .other: return "네트워크"
    }
}

private func diagnosticDate(_ date: Date) -> String {
    date.formatted(
        Date.FormatStyle(date: .numeric, time: .shortened)
            .locale(Locale(identifier: "ko_KR"))
    )
}

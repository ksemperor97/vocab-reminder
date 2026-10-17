import VocabCore
import AppKit
import Carbon
import Foundation

@MainActor
final class GlobalHotKeyController: ObservableObject {
    static let shared = GlobalHotKeyController()

    var onPressed: (() -> Void)?
    private var hotKeyRef: EventHotKeyRef?
    private var eventHandler: EventHandlerRef?

    init() {
        register()
    }

    private func register() {
        var eventType = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        let selfPointer = Unmanaged.passUnretained(self).toOpaque()
        InstallEventHandler(GetEventDispatcherTarget(), { _, event, userData in
            guard let userData else { return noErr }
            let controller = Unmanaged<GlobalHotKeyController>.fromOpaque(userData).takeUnretainedValue()
            Task { @MainActor in controller.onPressed?() }
            return noErr
        }, 1, &eventType, selfPointer, &eventHandler)

        let hotKeyID = EventHotKeyID(signature: fourCharCode("VCRD"), id: 1)
        let status = RegisterEventHotKey(UInt32(kVK_ANSI_V), UInt32(cmdKey | optionKey), hotKeyID, GetEventDispatcherTarget(), 0, &hotKeyRef)
        if status != noErr {
            LocalVocabularyStore.shared.message = "⌘⌥V 단축키 등록 실패 — 다른 앱과 충돌"
        }
    }
}

private func fourCharCode(_ string: String) -> OSType {
    string.utf8.reduce(0) { ($0 << 8) + OSType($1) }
}

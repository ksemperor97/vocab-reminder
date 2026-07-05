# 사용자 대면 QC 체크리스트 — 실기기 수동 검증

목적: Codex의 자동 테스트와 무관하게, **사용자가 실제로 맞닥뜨리는 모든 경우**를 실기기(Galaxy SM-F766N + Mac)에서 하나씩 눈으로 확인한다. 판정 기준은 `vocab_reminder_user_experience.md`(UX)와 `vocab_reminder_claude_guide.md`(guide)의 해당 절이다.

판정 기호: `[ ]` 미실행 / `[P]` 통과 / `[F]` 실패 / `[B]` 차단(선행 조건 부족)
각 항목은 **실패해도 계속 진행**하고, 실패 내용을 항목 아래에 기록한다.

---

## 0. 사전 조건

```text
P0-1. Galaxy에 debug APK 설치됨 (com.vocabreminder.android)
      ./gradlew :app:installDebug
P0-2. 알림 권한 허용됨 (안 되어 있으면 QC-A2에서 거부 흐름부터 테스트)
P0-3. Mac 앱 실행: cd mac/VocabReminderMac && swift run
P0-4. (원격 QC 시) Mac에서 scrcpy 실행 — Galaxy 화면을 Mac 창으로 미러링
      brew install scrcpy && scrcpy
P0-5. 시간이 걸리는 항목의 가속 방법:
      - +10분 첫 알림: 그냥 기다린다 (10분)
      - 10~30분 재확인: 기다린다 (최대 30분)
      - 1일 이상 간격: 기기 설정 > 날짜/시간 자동 해제 → 날짜 +1일 → 앱에서 [오늘 편성]
        ※ 날짜 조작 후 반드시 자동 시간으로 복원
```

---

## A. 설치·온보딩 (UX 1장)

- [P] QC-A1. 앱 첫 실행 시 알림 권한 요청이 뜬다. 요청 문구에 알림이 앱의 핵심이라는 설명이 있는가. (UX 1.1)
  - 증거: `/tmp/vocab_qc_20260703_094928/A1_first_launch_permission.png`, `/tmp/vocab_qc_20260703_094928/A1_first_launch_permission.xml`
  - 초기 증상: 권한 요청은 뜨지만 Android 시스템 기본 문구만 보이고, 앱 내 사전 설명/핵심 기능 안내가 없었다.
  - 수정 후 재검증: 앱 설명 다이얼로그와 시스템 권한창 확인. 증거: `/tmp/vocab_qc_20260703_094928/A1_clean_rationale_dialog.png`, `/tmp/vocab_qc_20260703_094928/A1_clean_rationale_dialog.xml`, `/tmp/vocab_qc_20260703_094928/A1_clean_system_permission.png`, `/tmp/vocab_qc_20260703_094928/A1_clean_system_permission.xml`
- [P] QC-A2. 권한을 **거부**해도 앱이 동작하고, 알림이 꺼져 있다는 안내(배너 등)가 보이는가. (UX 1.1 — 미구현 가능성 높음, 확인 필수)
  - 증거: `/tmp/vocab_qc_20260703_094928/A2_permission_denied_home.png`, `/tmp/vocab_qc_20260703_094928/A2_permission_denied_home.xml`
  - 초기 증상: 권한 거부 후 앱은 동작하지만 알림 꺼짐 안내 배너/문구가 보이지 않았다.
  - 수정 후 재검증: 거부 후 앱 동작 및 알림 꺼짐 배너 확인. 증거: `/tmp/vocab_qc_20260703_094928/A2_clean_denied_banner.png`, `/tmp/vocab_qc_20260703_094928/A2_clean_denied_banner.xml`
- [P] QC-A3. 알림 채널 `vocabulary_review`가 시스템 설정에 생성된다.
  - 증거: `/tmp/vocab_qc_20260703_094928/A3_notification_dumpsys.txt`
- [P] QC-A4. 앱 재실행 시 데이터가 유지된다 (저장한 카드가 남아 있음).
  - 증거: `/tmp/vocab_qc_20260703_094928/A4_card_persisted_scrolled2.png`, `/tmp/vocab_qc_20260703_094928/A4_card_persisted_scrolled2.xml`

## B. 캡처 — 단어를 잡는 순간 (UX 2장)

- [P] QC-B1. 수동 추가: "tentative" 입력 → [미리보기] → 뜻/예문/출처가 채워진 미리보기 → [저장]. 3초 안에 끝나는 체감인가. (UX 2.1)
  - 증거: `/tmp/vocab_qc_20260703_094928/B1_tentative_preview.xml`, `/tmp/vocab_qc_20260703_094928/B1_tentative_saved_after_back.xml`, `/tmp/vocab_qc_20260703_094928/B1_tentative_saved_list_final.xml`
  - UI-M2 재검증(2026-07-03): FAB bottom sheet에서 `tentative` preview/save 통과. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_preview_visible.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_saved.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_store.xml`
- [P] QC-B2. 미리보기에서 뜻을 직접 수정 후 저장 → 이후 알림/목록에 수정한 뜻이 나온다. (UX 2.7)
  - 증거: `/tmp/vocab_qc_20260703_094928/B2_elicit_preview_edited.xml`, `/tmp/vocab_qc_20260703_094928/B2_elicit_saved_list.xml`
  - UI-M2 재검증(2026-07-03): bottom sheet preview에서 뜻을 `이끌어내다, 유도하다_useredit`로 수정 후 저장했고 store에 반영됨. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B2_preview_edited.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B2_saved.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B2_store.xml`
- [P] QC-B3. 4단어 이상 문장 입력("The results are tentative, but robust.") → "어떤 단어를 저장할까요?" 후보 선택 UI. 선택한 단어로 저장되고 원문이 예문으로 남는다. (UX 2.3)
  - 증거: `/tmp/vocab_qc_20260703_094928/B3_sentence_candidate_ui.xml`, `/tmp/vocab_qc_20260703_094928/B3_retry_salient_saved_list.xml`
  - UI-M2 재검증(2026-07-03): bottom sheet에서 문장 입력 후 후보 `results/tentative/robust`가 보이고 `robust` 저장 시 원문이 context/example로 남음. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B3_candidates.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B3_robust_preview.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B3_store.xml`
- [P] QC-B3a. **URL 캡처 시 URL 조각이 후보로 나옴** (2026-07-03 실기기 확인): ppomppu URL 공유 → 후보 = [https][ppomppu][bbs][view][php][extref]. URL 토큰은 후보에서 제외해야 함. → `codex_device_qc_protocol.md` F-2 참고.
  - 수정 후 재검증: URL share에서 후보 선택 UI가 뜨지 않고 URL 조각 후보가 사라짐. 증거: `/tmp/vocab_qc_20260703_094928/B3a_url_share_no_candidates_full_url.xml`
- [P] QC-B4. 다른 앱(Chrome 등)에서 단어 선택 → 선택 메뉴 → 앱 이름 → 미니 화면에서 저장. **원래 앱으로 그대로 복귀**하는가. (UX 2.4)
  - 초기 증상: PROCESS_TEXT 저장 후 Vocabulary Reminder에 남아 원래 브라우저로 돌아가지 않았다. 증거: `/tmp/vocab_qc_20260703_094928/B4_after_process_text_save.xml`
  - 수정 후 재검증: 저장 후 Chrome Example Domain 화면으로 복귀. 증거: `/tmp/vocab_qc_20260703_094928/B4_recheck_after_save_return.xml`
  - UI-M2 재검증(2026-07-03): PROCESS_TEXT 진입이 bottom sheet preview로 열리고 `salient` 저장 후 foreground가 Chrome으로 복귀함. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_process_text_preview.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_after_save_return.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_after_save_focus.txt`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_store.xml`
- [P] QC-B5. 공유(Share) → 앱 선택 → 문장이면 후보 선택 흐름. (UX 2.5)
  - 증거: `/tmp/vocab_qc_20260703_094928/B5_share_sentence_candidates.xml`, `/tmp/vocab_qc_20260703_094928/B5_share_mitigate_saved_list.xml`
  - UI-M2 재검증(2026-07-03): SHARE 문장 후보 선택 흐름 확인 중 `mitigated`가 `mitigat`로 잘못 정규화되는 회귀를 발견해 `mitigate`로 보정 후 재검증했다. 저장 후 Chrome으로 복귀하고 store에 `source_app_primary=Android share`, `source_type=local_dictionary`로 남음. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_post_patch_candidates.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_post_patch_mitigate_preview.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_post_patch_after_save.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_after_save_focus.txt`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_store.xml`
- [P] QC-B6. [클립보드] 버튼: 미리 복사해 둔 텍스트를 버튼을 **누른 순간에만** 읽는다. (UX 2.6)
  - 증거: `/tmp/vocab_qc_20260703_094928/B6_before_clipboard_button.xml`, `/tmp/vocab_qc_20260703_094928/B6_after_clipboard_button.xml`
  - UI-M2 재검증(2026-07-03): bottom sheet에서 [클립보드] 버튼을 누른 뒤 `ubiquitous` preview가 생성되고 저장 후 store에 `source_app_primary=clipboard`와 원문 context가 남음. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_clean_seed_clipboard_loaded.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_clipboard_preview_visible.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_saved.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_store.xml`
- [P] QC-B7. 정규화: "Robustly," (대문자·쉼표 포함) 저장 → lemma가 robust 계열로 잡히고 원형도 표시된다. (UX 2.1, guide 10.7)
  - 증거: `/tmp/vocab_qc_20260703_094928/B7_robustly_preview.xml`, `/tmp/vocab_qc_20260703_094928/B7_robustly_saved_list.xml`
- [P] QC-B8. 구(phrase): "give up" 저장 → 하나의 카드가 된다. (UX 2.11)
  - 증거: `/tmp/vocab_qc_20260703_094928/B8_phrase_saved_list.xml`
- [P] QC-B9. 중복: 이미 저장한 단어를 다시 저장 → **새 카드가 생기지 않고** 문맥 추가 안내. 카드 수 불변 확인. (UX 2.10)
  - 초기 증상: 카드 수는 유지됐지만 일반 저장 메시지가 나오고 기존 예문이 local default로 바뀌었다. 증거: `/tmp/vocab_qc_20260703_094928/B9_duplicate_mitigate_saved_message.xml`, `/tmp/vocab_qc_20260703_094928/B9_duplicate_mitigate_saved_list.xml`
  - 수정 후 재검증: `salient 문맥 추가됨 · 카드 수 9개 유지`, 기존 예문 보존. 증거: `/tmp/vocab_qc_20260703_094928/B9_recheck_salient_duplicate_message.xml`, `/tmp/vocab_qc_20260703_094928/B9_recheck_salient_duplicate_list_scrolled.xml`
- [P] QC-B10. 사전에 없는 단어("perovskite") → "뜻 없음 · 알림 보류" 상태로 저장되고, 뜻을 채우기 전까지 알림이 오지 않는다. (UX 2.8)
  - 증거: `/tmp/vocab_qc_20260703_094928/B10_perovskite_saved_hold_message_retry.xml`, `/tmp/vocab_qc_20260703_094928/B10_notification_dump_after_perovskite_retry.txt`
- [P] QC-B11. 비행기 모드에서 저장 → 저장 자체는 즉시 성공한다. (UX 2.9)
  - 증거: `/tmp/vocab_qc_20260703_094928/B11_airplane_save_message.xml`, `/tmp/vocab_qc_20260703_094928/B11_airplane_saved_list.xml`
  - 복구: `airplane_mode_on=0` 확인.

## C. 복습 알림 코어 (UX 3.1~3.2)

- [P] QC-C1. 저장 약 10분 후 알림 도착. 제목=단어, **뜻은 어디에도 안 보임**, 버튼 3개: [외웠다] [못외웠다] [나중에 하기]. (UX 3.1)
  - 증거: `/tmp/vocab_qc_20260703_094928/C1_active_notifications_natural_dumpsys.txt`, `/tmp/vocab_qc_20260703_094928/C1_notification_shade_natural.xml`, `/tmp/vocab_qc_20260703_094928/C3_expanded_mitigate_actions.xml`
- [P] QC-C2. 잠금 화면에서도 뜻이 노출되지 않는다. (guide 14.4)
  - 증거: `/tmp/vocab_qc_20260703_094928/C2_lock_screen_notification_no_meaning.png`, `/tmp/vocab_qc_20260703_094928/C2_lock_screen_notification_no_meaning.xml`
- [P] QC-C3. [외웠다] → 알림이 "단어 ✓ / 뜻 / 다음 복습" confirmation으로 교체되고 **30초 이상 스스로 사라지지 않는다**. 뜻 알림을 지우기 전에는 다음 prompt가 나오지 않고, 지우면 다음 due prompt가 이어진다. 앱은 열리지 않는다. (UX 3.2)
  - 이전 증거(구 timeout 정책): `/tmp/vocab_qc_20260703_094928/C3_expanded_mitigate_actions.xml`, `/tmp/vocab_qc_20260703_094928/C3_remembered_confirmation.xml`, `/tmp/vocab_qc_20260703_094928/C3_after_confirmation_timeout.xml`
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): `alphaqc` [외웠다] 후 confirmation이 즉시/30초 후 모두 `710001` 단일 알림으로 유지됐고, dismiss deleteIntent 후 `betaqc` prompt가 이어짐. 증거: `/tmp/vocab_notification_flow_20260705/android/02_remembered_meaning_immediate_*`, `/tmp/vocab_notification_flow_20260705/android/03_remembered_meaning_after30_*`, `/tmp/vocab_notification_flow_20260705/android/06_after_remembered_deleteintent_user0_*`.
- [P] QC-C4. [외웠다] 후 카드의 다음 due가 앞으로 늘어났다 (앱 목록에서 확인).
  - 증거: `/tmp/vocab_qc_20260703_094928/C4_give_up_due_increased_after_restart.xml`

## D. 못외웠다 루프 — 제품의 심장 (UX 3.3)

- [P] QC-D1. [못외웠다] → 알림이 **그 자리에서 즉시** 뜻+예문 알림으로 바뀌고, 사용자가 지울 때까지 유지된다. 뜻 알림을 지우기 전에는 다음 prompt가 나오지 않고, 지우면 다음 due prompt가 이어진다. 앱을 열 필요 없음. (UX 3.3)
  - 이전 증거(즉시 뜻 표시): `/tmp/vocab_qc_20260703_094928/D1_expanded_offlineword_actions.xml`, `/tmp/vocab_qc_20260703_094928/D1_forgotten_meaning_notification.xml`
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): `betaqc` [못외웠다] 후 뜻+예문 알림이 즉시/30초 후 모두 `710002` 단일 알림으로 유지됐고, dismiss deleteIntent 후 `gammaqc` prompt가 이어짐. 증거: `/tmp/vocab_notification_flow_20260705/android/07_forgotten_meaning_immediate_*`, `/tmp/vocab_notification_flow_20260705/android/08_forgotten_meaning_after30_*`, `/tmp/vocab_notification_flow_20260705/android/09_after_forgotten_deleteintent_*`.
- [P] QC-D2. 10~30분 뒤 같은 단어의 재확인 알림이 온다.
  - 초기 실패: WorkManager DB에는 `review:offlineword`가 `ENQUEUED`였지만 system job이 없어 자연 재확인이 오지 않음. 증거: `/tmp/vocab_qc_20260703_094928/D2_workdb_after_due.sqlite`, `/tmp/vocab_qc_20260703_094928/D2_current_after_due_targeted.txt`
  - 수정 후 증거: `/tmp/vocab_qc_20260703_094928/D2_recheck_duepast_notification.txt`, `/tmp/vocab_qc_20260703_094928/D2_recheck_duepast_window.xml`
- [P] QC-D3. 재확인에서 [외웠다] → 카드가 재학습을 빠져나온다. **이전 간격의 절반(최대 7일)**로 재개되는지 앱의 due 표시로 확인. (UX 3.3, guide 6.3)
  - 증거: `/tmp/vocab_qc_20260703_094928/D3_offlineword_expanded_actions.xml`, `/tmp/vocab_qc_20260703_094928/D3_store_after_remembered.xml`
- [P] QC-D4. 같은 카드에 하루 3회 못외웠다 → 4회째는 오늘 다시 묻지 않는다. (guide 6.4)
  - 초기 실패: 하루 3회 제한 없음. 수정 후 세 번째 [못외웠다]에서 `due_at=2026-07-04...`로 이동. 증거: `/tmp/vocab_qc_20260703_094928/D4_offlineword_expanded_before_third_forgotten.xml`, `/tmp/vocab_qc_20260703_094928/D4_after_third_forgotten_notification.txt`, `/tmp/vocab_qc_20260703_094928/D4_store_after_third_forgotten.xml`
- [P] QC-D5. (날짜 조작) interval 14일짜리 카드를 만들어 [못외웠다] → 재확인 성공 → due가 +7일이 된다 (14의 절반). 90일짜리도 +7일 (상한). (guide 19.7)
  - 증거: `/tmp/vocab_qc_20260703_094928/D5_14_expanded_before_forgotten.xml`, `/tmp/vocab_qc_20260703_094928/D5_14_recheck_expanded.xml`, `/tmp/vocab_qc_20260703_094928/D5_14_store_after_recheck_remembered.xml`, `/tmp/vocab_qc_20260703_094928/D5_90_expanded_before_forgotten.xml`, `/tmp/vocab_qc_20260703_094928/D5_90_recheck_expanded.xml`, `/tmp/vocab_qc_20260703_094928/D5_90_store_after_recheck_remembered.xml`

## E. 나중에 하기 / 무응답 (UX 3.4~3.5)

- [P] QC-E1. [나중에 하기] → 알림 사라짐, 설정한 미루기 시간(기본 60분) 뒤 재알림. 카드의 interval/상태 불변. (UX 3.4)
  - 증거: `/tmp/vocab_qc_20260703_094928/E1_expanded_before_snooze.xml`, `/tmp/vocab_qc_20260703_094928/E1_after_snooze_notification.txt`, `/tmp/vocab_qc_20260703_094928/E1_store_after_snooze.xml`, `/tmp/vocab_qc_20260703_094928/E1_realert_duepast_notification.txt`
- [P] QC-E2. 설정에서 미루기를 5분으로 바꾸면 실제로 5분 뒤에 온다 (설정 반영 확인).
  - 초기 재현 세팅 오류: settings 노드가 없어 기본 60분으로 동작. settings 노드 추가 후 재검증 통과.
  - 증거: `/tmp/vocab_qc_20260703_094928/E2_recheck_expanded_before_snooze.xml`, `/tmp/vocab_qc_20260703_094928/E2_recheck_store_after_snooze.xml`, `/tmp/vocab_qc_20260703_094928/E2_realert_duepast_notification.txt`
- [P] QC-E3. 같은 카드에 하루 2회 [나중에 하기] → 그 단어는 내일로 넘어간다. (guide 6.3)
  - 증거: `/tmp/vocab_qc_20260703_094928/E3_expanded_before_second_snooze.xml`, `/tmp/vocab_qc_20260703_094928/E3_store_after_second_snooze.xml`
- [P] QC-E4. 알림을 그냥 무시 → 알림이 트레이에 계속 남아 있다. 어떤 이벤트도 기록되지 않는다. (UX 3.5)
  - 증거: `/tmp/vocab_qc_20260703_094928/E4_ignore_notification_still_active.txt`, `/tmp/vocab_qc_20260703_094928/E4_store_after_ignore_wait.xml`
- [P] QC-E4a. 질문 프롬프트를 응답 없이 스와이프 → [나중에 하기]와 동일하게 UNAVAILABLE 이벤트가 기록되고 due가 snooze만큼 이동한다. 즉시 다음 due prompt가 이어진다. (UX 3.5)
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): `gammaqc` prompt를 알림 shade에서 실제 swipe로 지웠고, store에 `UNAVAILABLE` 이벤트와 미래 `due_at`이 기록되며 다음 추천 prompt가 게시됨. 증거: `/tmp/vocab_notification_flow_20260705/android/10_gamma_prompt_before_swipe_shade.png`, `/tmp/vocab_notification_flow_20260705/android/10_after_gamma_swipe_*`, `/tmp/vocab_notification_flow_20260705/android/10_store_after_gamma_swipe.xml`.
- [P] QC-E5. 잠자는 시간(설정을 현재 시각 직후로 임시 변경) 진입 → 남은 알림이 걷힌다. 잠자는 끝 시간 후 다시 게시된다. (UX 3.5, guide 6.6)
  - 초기 실패: 15분 주기 WorkManager sweep을 `cmd jobscheduler run -f`로 강제해도 기존 알림이 걷히지 않았다. 경계 알람 리시버를 추가해 잠자는 시작/끝 시각을 직접 처리하도록 수정.
  - 수정 후 증거: 시작 전 알림 `/tmp/vocab_qc_20260703_094928/E5_boundary_before_start_notification.txt`, 시작 후 제거 `/tmp/vocab_qc_20260703_094928/E5_boundary_after_start_notification.txt`, 끝 후 재게시 `/tmp/vocab_qc_20260703_094928/E5_boundary_after_end_notification.txt`
- [P] QC-E6. 잠자는 시간 중 due가 된 카드는 알림이 오지 않고, 끝난 뒤 온다. (UX 3.6)
  - 초기 실패: 당일형 잠자는 시간(예: 11:26-11:30)에서 release 계산이 다음 날로 밀렸다. Android/Mac 공용 scheduler와 vector를 수정.
  - 수정 후 증거: 알림 부재 및 due가 당일 release 이후로 이동. `/tmp/vocab_qc_20260703_094928/E6_recheck_after_plan_notification.txt`, `/tmp/vocab_qc_20260703_094928/E6_recheck_store_after_plan.xml`

## F. 하루 편성·부하 제어 (UX 3.6~3.7)

- [P] QC-F1. 설정 화면: 하루 목표/큐 상한/미루기/잠자는 시작·끝이 있고 [설정 저장] 후 재실행해도 유지된다.
  - 증거: `/tmp/vocab_qc_20260703_094928/F1_settings_before_save.xml`, `/tmp/vocab_qc_20260703_094928/F1_settings_after_save_store.xml`, `/tmp/vocab_qc_20260703_094928/F1_settings_after_relaunch.xml`
  - UI-M2 재검증(2026-07-03): 설정 탭에서 저장 후 앱을 강제 종료/재실행해도 `daily_word_goal=7`, `max_queue_size=20`, `snooze_duration_minutes=45`, `23:00/09:00`, `Asia/Seoul`이 화면과 store에 유지됨. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_snooze_edited.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_saved_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_relaunch_settings_top.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_relaunch_settings_bottom.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_relaunch_store.xml`
- [P] QC-F2. [오늘 편성]: due 카드 수 ≤ 하루 목표면 오늘 queue에 전부 포함되지만, 트레이에는 첫 prompt 1개만 표시된다. 현재 뜻 알림을 지울 때마다 다음 due prompt가 이어진다.
  - 이전 증거(구/초기 queue 정책): `/tmp/vocab_qc_20260703_094928/F2_due_under_goal_notifications.txt`, `/tmp/vocab_qc_20260703_094928/F2_due_under_goal_shade.xml`
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): due review 3개 seed에서 처음에는 `alphaqc` prompt 1개만 표시됐고, meaning dismiss 후 `betaqc`, `gammaqc`가 순서대로 이어짐. 증거: `/tmp/vocab_notification_flow_20260705/android/01_initial_prompt_*`, `/tmp/vocab_notification_flow_20260705/android/06_after_remembered_deleteintent_user0_*`, `/tmp/vocab_notification_flow_20260705/android/09_after_forgotten_deleteintent_*`.
- [ ] QC-F3. (날짜 조작으로 due 15개 만들기, 목표 10) [오늘 편성] → 오늘 queue는 10개만 잡고 트레이에는 prompt 1개만 표시된다. 초과 5개는 자동 재조정으로 미래 날짜로 분산. 카드들의 interval/lapse는 불변. (guide 6.9, 19.12)
  - 이전 증거(구/초기 queue 정책): `/tmp/vocab_qc_20260703_094928/F3_due15_goal10_notifications.txt`, `/tmp/vocab_qc_20260703_094928/F3_due15_goal10_store_after_plan.xml`
- [P] QC-F4. 우선순위: 재학습 중 카드가 review/new보다 먼저 알림 자리를 차지한다. (guide 6.7)
  - 증거: `/tmp/vocab_qc_20260703_094928/F4_priority_notifications.txt`, `/tmp/vocab_qc_20260703_094928/F4_priority_store_after_plan.xml`
- [ ] QC-F5. 큐 상한(20)보다 많은 due → 오늘 queue 후보가 상한을 넘지 않고, 트레이에는 prompt 1개만 표시된다.
  - 이전 증거(구/초기 queue 정책): `/tmp/vocab_qc_20260703_094928/F5_queue_cap_notifications.txt`, `/tmp/vocab_qc_20260703_094928/F5_queue_cap_store_after_plan.xml`
- [P] QC-F6. [복습 재조정] 버튼 → 밀린 단어들의 due가 향후 며칠로 재배치되고 기억 상태는 불변. (UX 3.7)
  - 증거: `/tmp/vocab_qc_20260703_094928/F6_rebalance_after_ui.xml`, `/tmp/vocab_qc_20260703_094928/F6_rebalance_store_after.xml`
- [P] QC-F7. due 카드가 여러 개여도 트레이에는 복습 prompt가 **한 번에 하나만** 보인다. 현재 prompt를 처리하면 뜻 알림으로 교체되고, 그 뜻 알림을 지울 때 queue의 다음 due item이 이어서 표시된다 — "한 장씩 몰아서 복습" 체감. (UX 3.6)
  - 이전 증거(다중 알림 처리): `/tmp/vocab_qc_20260703_094928/F7_recheck_before_actions_notification.txt`, `/tmp/vocab_qc_20260703_094928/F7_recheck_final_notification.txt`, `/tmp/vocab_qc_20260703_094928/F7_recheck_store_final.xml`
  - queue 정책 변경 재검증(2026-07-03): 앱 시작 후 due 6개 중 `ubiquitous` prompt 1개만 표시되고, 알림 셰이드에서 [나중에 하기] 처리 후 `tentative` prompt 1개만 이어서 표시됨. 테스트 후 store와 알림 상태 복구. 증거: `/tmp/vocab_ui_20260703_130300/notification_queue/after_initial_plan_notifications.txt`, `/tmp/vocab_ui_20260703_130300/notification_queue/queue_shade_expanded.png`, `/tmp/vocab_ui_20260703_130300/notification_queue/queue_shade_expanded.xml`, `/tmp/vocab_ui_20260703_130300/notification_queue/after_shade_unavailable_notifications.txt`, `/tmp/vocab_ui_20260703_130300/notification_queue/after_restore_notifications.txt`
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): `alphaqc` prompt 1개 → [외웠다] meaning 1개(30초 유지) → dismiss 후 `betaqc` prompt 1개 → [못외웠다] meaning 1개(30초 유지) → dismiss 후 `gammaqc` prompt 1개 → prompt swipe 후 추천 prompt 1개 흐름을 확인. 증거: `/tmp/vocab_notification_flow_20260705/android`.

## G. 추천 단어장 (UX 9장)

- [P] QC-G1. 내 단어장 50개 **미만**이면 [오늘 편성]을 해도 추천이 생성되지 않는다.
  - 증거: `/tmp/vocab_qc_20260703_094928/G1_under_50_store_after_plan.xml`, `/tmp/vocab_qc_20260703_094928/G1_under_50_notifications.txt`
- [P] QC-G2. 50개 도달(대량 입력 또는 sync import로 채움) 후 첫 편성 → 시작 안내가 **1회만** 표시된다.
  - 증거: `/tmp/vocab_qc_20260703_094928/G2_50_intro_twice_store_after_plan.xml`, `/tmp/vocab_qc_20260703_094928/G2_50_intro_twice_notifications.txt`
- [P] QC-G3. 복습 due가 6개, 목표 10 → 추천 4개가 채워진다. 복습이 10개 이상인 날은 추천 0개. (guide 19.11)
  - 증거: `/tmp/vocab_qc_20260703_094928/G3_due6_goal10_store_after_plan.xml`, `/tmp/vocab_qc_20260703_094928/G3_due6_goal10_notifications.txt`, `/tmp/vocab_qc_20260703_094928/G3_due10_goal10_store_after_plan.xml`, `/tmp/vocab_qc_20260703_094928/G3_due10_goal10_notifications.txt`
- [P] QC-G4. 추천 첫 알림은 소개형: 뜻이 **보이고**, "알고 있는 단어인가요?" + [시작하기] [알아요] [관심 없음]. 퀴즈형 아님. (UX 9)
  - 초기 실패: Android 추천 첫 알림이 복습 알림처럼 뜻을 숨겼다. Mac/UX와 맞게 뜻/예문 표시형으로 수정 후 재검증.
  - 증거: `/tmp/vocab_qc_20260703_094928/G2_50_intro_twice_notifications.txt`
- [P] QC-G5. [시작하기] → 추천 단어장에 남아 일반 복습 사이클 진입.
  - 증거: `/tmp/vocab_qc_20260703_094928/G5_start_before_action_notification.txt`, `/tmp/vocab_qc_20260703_094928/G5_start_store_after_action.xml`
  - 순차 알림 재검증(2026-07-05, Galaxy SM-F766N QA 패키지): `omegaqc` 추천 [시작하기] 후 meaning 알림이 즉시/30초 후 모두 유지됐고, meaning dismiss 후 started 추천이 due 전 재노출되지 않고 `sigmaqc` 다음 추천 prompt가 게시됨. 증거: `/tmp/vocab_notification_flow_20260705/android/17_initial_recommendation_prompt_*`, `/tmp/vocab_notification_flow_20260705/android/18_recommendation_meaning_immediate_*`, `/tmp/vocab_notification_flow_20260705/android/19_recommendation_meaning_after30_*`, `/tmp/vocab_notification_flow_20260705/android/20_after_recommendation_feedback_dismiss_fresh_*`.
- [P] QC-G6. [알아요] → 카드가 사라지고, 이후 추천 난이도가 올라가는 경향 (여러 번 눌러 확인).
  - 증거: `/tmp/vocab_qc_20260703_094928/G6_known_before_action_notification.txt`, `/tmp/vocab_qc_20260703_094928/G6_known_store_after_action.xml`
- [P] QC-G7. [관심 없음] → 카드가 사라지고 유사 계열 추천이 줄어든다.
  - 증거: `/tmp/vocab_qc_20260703_094928/G7_dismiss_before_action_notification.txt`, `/tmp/vocab_qc_20260703_094928/G7_dismiss_store_after_action.xml`
- [P] QC-G8. 추천받은 단어를 캡처(수동 입력)로 다시 저장 → **내 단어장으로 승격**되고 진도(due/interval) 유지. (UX 9)
  - 증거: `/tmp/vocab_qc_20260703_094928/G8_promotion_recheck_preview_ui.xml`, `/tmp/vocab_qc_20260703_094928/G8_promotion_recheck_store_after_capture.xml`
- [P] QC-G9. 내 단어장과 추천 단어장이 UI에서 분리되어 보인다.
  - 초기 실패: 카드별 `wordbook=...`만 있고 목록 섹션은 분리되지 않았다. `내 단어장`/`추천 단어장` 섹션 헤더를 추가 후 재검증.
  - 증거: `/tmp/vocab_qc_20260703_094928/G9_wordbook_sections_ui.xml`, `/tmp/vocab_qc_20260703_094928/G9_wordbook_sections_ui.png`

## H. Mac 앱 (UX 1.2, 2.1~2.3, 5장)

- [P] QC-H1. `swift run` → 메뉴 바에 아이콘이 뜬다 (Dock에는 없음).
  - **발견 (2026-07-03, Codex 직접 실행)**: 실행 즉시 크래시. `UNUserNotificationCenter.current()`가 번들 없는 프로세스(`swift run`)에서 `bundleProxyForCurrentProcess is nil`로 abort. 앱이 한 번도 실제 실행된 적 없었다는 증거.
  - **수정/재검증**: `MacNotificationScheduler`에 번들 유무 가드 추가. `swift run VocabReminderMac` 실행 후 메뉴바 아이콘 확인, Dock 미노출은 `LSUIElement=true`로 확인. 증거: `/tmp/vocab_qc_20260703_094928/H1_mac_screen_running.png`, `/tmp/vocab_qc_20260703_094928/H1_mac_menu_open.png`, `/tmp/vocab_qc_20260703_094928/H1_mac_main_window.png`
- [P] QC-H2. 텍스트 선택 → Cmd+C → **⌘⌥V** → 캡처 팝업에 단어/뜻 자동 채움 (macOS 내장 사전 결과). (UX 2.2)
  - 증거: `/tmp/vocab_qc_20260703_094928/H2_hotkey_preview.png`
- [P] QC-H3. 미리보기에서 수정/저장/취소 동작.
  - 증거: `/tmp/vocab_qc_20260703_094928/H3_mac_saved_edited_meaning.png`, `/tmp/vocab_qc_20260703_094928/H3_mac_store_after_save.json`, `/tmp/vocab_qc_20260703_094928/H3_mac_cancel_no_extra_card.png`, `/tmp/vocab_qc_20260703_094928/H3_mac_store_after_cancel.json`
- [P] QC-H4. 카드 목록: 저장 단어, due, 상태 표시. 카드 편집(뜻 수정)이 목록에 반영.
  - 판정 범위: Mac MVP에는 별도 사후 편집 화면이 없으므로, preview에서 수정한 뜻이 저장 후 목록 카드에 반영되는지를 확인했다.
  - 증거: `/tmp/vocab_qc_20260703_094928/H3_mac_saved_edited_meaning.png`, `/tmp/vocab_qc_20260703_094928/H3_mac_store_after_save.json`
- [P] QC-H5. Mac에서 복습 액션(외웠다/못외웠다/나중에 하기) 실행 → due 갱신.
  - 증거: `/tmp/vocab_qc_20260703_094928/H5_mac_review_actions_ui.png`, `/tmp/vocab_qc_20260703_094928/H5_mac_store_after_review_actions.json`
- [P] QC-H6. Mac 로컬 알림이 due 시각에 온다 (권한 요청 포함).
  - 초기 실패: unsigned `.app`에서 `UNErrorDomain error 1`로 알림 권한/예약 실패. `package_app.sh`에 ad-hoc codesign과 `NSUserNotificationAlertStyle=banner`를 추가.
  - 재검증: signed `.app`에서 authorizationStatus=2, delivered notification 확인. 증거: `/tmp/vocab_qc_20260703_094928/H6_mac_signed_after_due_screen.png`, `/tmp/vocab_qc_20260703_094928/H6_mac_signed_after_due_status.txt`, `/tmp/vocab_qc_20260703_094928/H6_mac_signed_Info.plist`
- [P] QC-H7. 추천: 시작 안내 1회, 소개형 알림([시작하기]/[알아요]/[관심 없음]), 캡처 시 승격.
  - 초기 실패: Mac 알림 계획이 미래 user 카드 10개를 먼저 잡아 due-now 추천 소개 알림이 밀렸다. due user → recommendation → future user 순으로 수정.
  - 증거: `/tmp/vocab_qc_20260703_094928/H7_mac_recommend_fill_ui.png`, `/tmp/vocab_qc_20260703_094928/H7_mac_recommend_notification_status_after_fix.txt`, `/tmp/vocab_qc_20260703_094928/H7_mac_recommend_actions_after_ui.png`, `/tmp/vocab_qc_20260703_094928/H7_mac_store_after_recommend_actions.json`, `/tmp/vocab_qc_20260703_094928/H7_mac_tentative_promoted_ui.png`, `/tmp/vocab_qc_20260703_094928/H7_mac_store_after_tentative_promotion.json`
- [P] QC-H8. [복습 재조정] 동작.
  - 증거: `/tmp/vocab_qc_20260703_094928/H8_mac_before_rebalance_ui.png`, `/tmp/vocab_qc_20260703_094928/H8_mac_after_rebalance_ui.png`, `/tmp/vocab_qc_20260703_094928/H8_mac_store_after_rebalance.json`
- [P] QC-H9. `swift run VocabReminderMac --check-scheduler-vectors` → 공유 벡터 전부 통과 (Kotlin과 동일 규칙 보증).
  - 증거: `/tmp/vocab_qc_20260703_094928/H9_mac_scheduler_vectors_after_h7_fix.txt`

## I. 동기화 (UX 6장 — 자동 HTTPS tunnel sync)

- [P] QC-I1. Android release UI에 서버 주소/토큰/VPN 안내가 보이지 않는다.
  - 2026-07-03 운영 URL release APK 설치 후 확인. 설치된 패키지는 `run-as: package not debuggable`로 release 상태였다.
  - Sync 화면에는 `자동 동기화: 준비됨`, `연결 테스트`, `지금 동기화`만 보이고 `서버 주소`, `토큰`, `VPN`, `WireGuard`, `sync_server_url_field`, `sync_auth_token_field`, endpoint 문자열은 UI dump에 없었다.
  - 자동 재검증: `npm run sync:android-release-ui-smoke` 통과.
  - 증거: `/tmp/vocab_sync_release_ui_real_20260703_201153/release_sync_panel.xml`
- [P] QC-I2. Android에서 AdGuard VPN을 켠 상태 그대로 앱 실행 → 자동 sync 시도.
  - 2026-07-03 `dumpsys connectivity`에서 `VPN:com.adguard.android`, `sessionId=AdGuard`, `tun0` 활성 상태를 확인했고, release 앱 실행 직후 `com.vocabreminder.android/androidx.work.impl.background.systemjob.SystemJobService` job 시작/종료 기록을 확인했다.
  - 같은 상태에서 `synchronize` 저장 후 `지금 동기화`를 눌러 Worker snapshot이 6개에서 7개로 증가했다.
  - 자동 재검증: `npm run sync:android-release-ui-smoke` 통과.
  - 증거: `/tmp/vocab_sync_release_ui_real_20260703_201153/connectivity.txt`, `/tmp/vocab_sync_release_ui_real_20260703_201153/jobscheduler_vocab.txt`, `/tmp/vocab_sync_roundtrip_android_real_20260703_2028`
- [P] QC-I3. Android에서 단어 저장 → 자동 sync → MacBook Air에서 같은 카드 확인.
  - 2026-07-03 Android release에서 `synchronize` 저장 후 Worker snapshot 6 -> 7. MacBook Air release 앱 실행 후 Mac 로컬과 Worker가 10개로 merge됐고 Mac 로컬에 `synchronize`가 포함됨.
  - 증거: `/tmp/vocab_sync_roundtrip_android_real_20260703_2028`
- [ ] QC-I4. MacBook Air에서 뜻 수정 → 자동 sync → Android에 반영, 이전 뜻은 후보로 보존.
- [ ] QC-I5. 같은 lemma를 Android/MacBook에서 저장 → sync 후 카드 1개 + 문맥 2개.
- [ ] QC-I6. 같은 batch를 두 번 push해도 중복 없음 (idempotency).
- [ ] QC-I7. Mac mini sync 서버/tunnel 꺼진 상태에서 복습/저장 → 앱 정상 동작, 오류 팝업 없음.
- [ ] QC-I8. Mac mini sync 서버/tunnel 복구 후 누적 변경분 자동 반영.
- [ ] QC-I9. 잘못된 토큰/401 → 동기화 상태 화면에 명확한 에러 표시.
- [ ] QC-I10. 깨진 레코드 섞인 batch → 스킵 카운트가 동기화 화면에 표시, 기존 데이터 무손상.
- [ ] QC-I11. Mac mini 재부팅 → sync 서버와 tunnel agent가 자동 복구 → Android/MacBook sync 성공.
- [ ] QC-I12. 모바일 데이터에서도 VPN 변경 없이 Android sync 성공.
- [P] QC-I13. MacBook Air 로컬 기존 단어 → 서버 sync → Android pull 확인.
  - 2026-07-03 Mac 로컬 `promoteword`, `maconly`, `mergeword`가 Worker에 반영됐고 Android release에서 다시 `지금 동기화` 후 UI 스크롤로 세 단어가 표시됨.
  - 증거: `/tmp/vocab_sync_roundtrip_android_pull_mac_20260703_2032`

## J. 파괴적 동작·엣지

- [P] QC-J1. 카드 삭제 → 목록에서 사라지고 예약된 알림도 더 이상 오지 않는다.
  - 실패: Android/Mac 카드 목록에 일반 사용자 카드 삭제 UI가 없다. Android store에는 추천 카드 soft-delete만 노출되어 있고, user card 삭제 + 예약 취소 사용자 경로는 확인되지 않았다.
  - 증거: `/tmp/vocab_qc_20260703_094928/IJ_android_current_ui.xml`, `/tmp/vocab_qc_20260703_094928/J_android_store_before.xml`
  - 수정 후 재검증: Android 카드 목록에 `삭제` 버튼을 추가했다. `maconly` 삭제 후 UI는 `저장된 단어 2개`로 바뀌고 `maconly`가 사라졌으며, store의 `mac-only-card`에는 `deleted_at=2026-07-03T03:57:18.894869Z`가 기록되었다. `dumpsys notification`과 `jobscheduler`에서도 `mac-only-card` 흔적이 없었다.
  - 증거: `/tmp/vocab_qc_20260703_094928/IJ_recheck/android_ui_j1_delete_visible.xml`, `/tmp/vocab_qc_20260703_094928/IJ_recheck/android_ui_j1_after_delete.xml`, `/tmp/vocab_qc_20260703_094928/IJ_recheck/android_store_j1_after_delete.xml`, `/tmp/vocab_qc_20260703_094928/IJ_recheck/android_notifications_j1_after_delete.txt`, `/tmp/vocab_qc_20260703_094928/IJ_recheck/android_jobs_j1_after_delete.txt`
- [B] QC-J2. suspend(있다면) → 알림 제외되지만 목록에는 남는다. 미구현이면 기록.
  - 기록: scheduler/status 모델에는 `SUSPENDED`가 있으나 Android/Mac 사용자 UI에는 suspend/restore 조작이 없다. 이 항목은 "있다면" 조건이므로 미구현으로 기록한다.
  - 증거: `/tmp/vocab_qc_20260703_094928/IJ_android_current_ui.xml`
- [B] QC-J3. 앱 강제 종료 후에도 예약된 알림이 온다 (WorkManager 생존).
  - 차단: 현재 WorkManager DB에는 review 예약이 없고 quiet-hours 주기 작업만 있었다. fresh review 예약을 만들기 위한 preview save 탭이 현재 기기 상태에서 저장 콜백까지 들어가지 않아, 강제 종료 후 자연 도착을 새로 검증하지 못했다. Android의 true `am force-stop`은 플랫폼상 알람/잡을 중지시키므로 별도 정의가 필요하다.
  - 증거: `/tmp/vocab_qc_20260703_094928/J3_jobs_after_save.txt`, `/tmp/vocab_qc_20260703_094928/J3_after_tentative_preview_ui.xml`, `/tmp/vocab_qc_20260703_094928/J3_store_after_precise_save.xml`
- [B] QC-J4. 기기 재부팅 후에도 알림이 온다 (재부팅 리스케줄 — 미구현 가능성, 확인 필수).
  - 차단: 사용자 기기 재부팅은 이번 실행에서 수행하지 않았다. 병합된 패키지에는 WorkManager `RescheduleReceiver`와 `RECEIVE_BOOT_COMPLETED` 권한이 있으나, 앱 자체 AlarmManager fallback 재등록 receiver는 없다. 통제된 재부팅으로 별도 확인 필요.
  - 증거: `/tmp/vocab_qc_20260703_094928/J4_android_package_receivers.txt`
- [B] QC-J5. 비행기 모드에서 복습(못외웠다 포함) → 정상 동작, 온라인 복귀 후 데이터 이상 없음.
  - 차단: 비행기 모드 저장은 QC-B11에서 통과했지만, 현재 active review 알림이 없어 `못외웠다` 알림 액션을 비행기 모드에서 실행하지 못했다. `airplane_mode_on`은 1로 변경 시도 후 0으로 복구했다.
  - 증거: `/tmp/vocab_qc_20260703_094928/J5_airplane_after_attempt.txt`, `/tmp/vocab_qc_20260703_094928/J5_notification_list.txt`, `/tmp/vocab_qc_20260703_094928/J5_airplane_restored.txt`

---

## 실행 기록

| 날짜 | 실행자 | 범위 | 통과/실패/차단 |
|---|---|---|---|
| 2026-07-03 | Codex | A. 설치·온보딩 | P: A1,A2,A3,A4 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | B. 캡처 | P: B1-B11,B3a / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | C. 복습 알림 코어 | P: C1-C4 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | D. 못외웠다 루프 | P: D1-D5 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | E. 나중에 하기 / 무응답 | P: E1-E6 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | F. 하루 편성·부하 제어 | P: F1-F7 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | G. 추천 단어장 | P: G1-G9 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | H. Mac 앱 | P: H1-H9 / F: 없음 / B: 없음 |
| 2026-07-03 | Codex | I. 동기화 | P: I1,I2,I3,I13 / F: 없음 / B: I4-I12 일부 세부 edge 미실행 |
| 2026-07-03 | Codex | J. 파괴적 동작·엣지 | P: J1 / F: 없음 / B: J2-J5 |
| 2026-07-05 | Codex | Android 알림 순차 흐름 | P: C3,D1,E4a,F2,F7,G5 / F: 없음 / B: 없음 |
| | | | |

## 실패 항목 기록 양식

```text
QC-ID:
증상:
재현 절차:
기대(UX/guide 근거):
```

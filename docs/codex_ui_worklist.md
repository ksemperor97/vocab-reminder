# Codex UI 개선 작업 지시서

근거 문서: `visual_improvement_review.md` (실기기 관찰, V-번호가 이 문서와 1:1 대응).
행동 검증 규칙: `codex_device_qc_protocol.md`의 절대 규칙을 그대로 따른다 — **모든 완료 판정은 실기기 screencap 증거와 함께**.

## 0. 작업 원칙

```text
1. UI 변경은 동작을 바꾸지 않는다. scheduler/store/notification 로직 수정 금지.
   (유일한 예외: UI-M4의 알림 스몰 아이콘 교체)
2. 마일스톤 하나 끝날 때마다: 변경 화면 screencap 전/후 비교 저장 →
   visual_improvement_review.md의 해당 V-항목에 [완료 + 증거 파일명] 기록 → 커밋.
3. UI-M2(구조 개편) 후에는 qc_user_facing_checklist.md의 B1~B6, F1 항목을 실기기로
   재검증한다. 화면 구조가 바뀌므로 기존 통과 기록은 무효다.
4. 하드코딩된 한국어 문자열은 만지는 김에 strings.xml로 옮긴다 (신규 문자열 포함).
5. uiautomator 테스트가 쓰는 텍스트/컨텐츠 설명이 바뀌면 관련 테스트도 같이 갱신한다.
```

## UI-M1 — 깨짐 수정 (P1, 반나절)

### [x] T1. 설정 필드 클리핑 (V-1)

```text
위치: android .../MainActivity.kt — 설정 섹션의 NumberField/TimeField Row (~800행 부근)
할 일:
- 한 줄 Row → 2열 grid 또는 세로 Column으로 변경
- 라벨에 단위 포함: "하루 목표(개)" "큐 상한(개)" "미루기(분)" "잠자는 시작" "잠자는 끝" "시간대"
- 필드 minWidth 보장, 라벨·값 모두 줄바꿈/잘림 금지
완료 기준(실기기 screencap):
- 모든 라벨이 가로 1줄, 값 "10/20/60/23:00/09:00/Asia/Seoul"이 전부 완전하게 보임
- 폴더블 접힘(좁은 화면)과 펼침 양쪽에서 확인
```

완료(2026-07-03): HALF_OPENED/OPENED 상태에서 라벨과 값이 잘림 없이 보임. 증거: `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_half_opened_full.png`, `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_fields_opened.png`

### [x] T2. [복습 재조정] 버튼 오버플로 (V-2)

```text
위치: 같은 파일 — [설정 저장][오늘 편성][복습 재조정] 버튼 Row
할 일: 세 버튼 동일 높이 알약형, 좁으면 FlowRow/세로 스택. 라벨 줄바꿈 금지
완료 기준: 세 버튼이 같은 형태로, "복습 재조정"이 한 줄로 보이는 screencap
```

완료(2026-07-03): 세 버튼을 같은 전체폭 알약형 세로 스택으로 통일. HALF_OPENED/OPENED 모두 `복습 재조정`이 한 줄로 보임. 증거: `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_buttons_half_opened.png`, `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_buttons_opened_full.png`

## UI-M2 — 정보 구조 개편 (P2, 1~2일)

### [x] T3. 하단 탭 3개 + 화면 분리 (V-3)

```text
할 일:
- Scaffold + NavigationBar: [내 단어장] [추천 단어장] [설정]
- 내 단어장 탭: 상단 요약(오늘 due N · 취약 N · 전체 N) + 카드 리스트 + FAB(+)
- 추천 단어장 탭: wordbook=recommended 카드만 (분리 자체가 QC-G9 요건)
- 설정 탭: T1의 설정 + 추천 토글 + [오늘 편성][복습 재조정] + "동기화" 하위 진입
- 단어 추가(FAB)와 PROCESS_TEXT/공유 진입 → ModalBottomSheet로 통일.
  저장/취소 시 시트만 닫힘. 외부 앱에서 진입한 경우 저장 후 액티비티 종료(원래 앱 복귀)
완료 기준(실기기 screencap 각 1장):
- 탭 3개 전환 화면, FAB→bottom sheet, 공유 진입 시 sheet만 뜨는 모습
- Chrome에서 단어 선택→저장→Chrome 복귀가 끊기지 않음 (QC-B4 재검증)
```

완료(2026-07-03): `내 단어장`/`추천 단어장`/`설정` 하단 탭으로 화면을 분리하고, FAB/PROCESS_TEXT/SHARE 진입을 단어 추가 bottom sheet로 통일했다. Chrome PROCESS_TEXT 저장 후 원래 Chrome 화면으로 복귀함을 재검증했다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/recommendation_tab.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/settings_top.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_preview_visible.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/share_entry_sheet.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_after_save_return.png`

UI-M2 후 필수 재검증: QC-B1~B6, QC-F1 통과. 증거: `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B2_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B3_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_after_save_focus.txt`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_store.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/F1_relaunch_store.xml`

### [x] T4. Cloud sync를 설정 하위로 + 디버그 게이팅 (V-4)

```text
할 일:
- 설정 탭 > "동기화" 화면으로 이동
- 릴리스 노출: 로그인 상태 1줄 + 마지막 동기화 시각 + [로그인/로그아웃] [지금 동기화]
- Web API key / Functions URL / ID token 직접 입력 필드는 BuildConfig.DEBUG에서만 노출
완료 기준: 메인(내 단어장) 화면에 Firebase 관련 필드가 전혀 없음 screencap
```

완료(2026-07-03): Cloud sync는 설정 탭의 `동기화` 하위 화면으로 이동했다. `내 단어장`/설정 메인 화면에는 Firebase Web API key, ID token, Functions URL 직접 입력 필드가 보이지 않고, debug 빌드의 sync 화면에서만 `Debug sync fields` 아래에 노출된다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/settings_top.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/sync_screen.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/sync_screen.xml`

### [x] T5. 카드 메타를 사용자 언어로 (V-5)

```text
할 일:
- "wordbook=… status=… due=ISO" 제거 → 상태 뱃지 + "다음 복습: 상대시간" 표기
  뱃지: 새 단어=파랑 / 배우는 중=보라 / 복습=초록 / 재학습=주황 / 완료=회색 /
        뜻 없음·알림 보류=빨강 테두리
- 상대시간 규칙: 오늘이면 "오늘 HH:mm", 1일 뒤 "내일", 그 외 "N일 뒤" / 지났으면 "지금"
- 카드 기본 표시 = 단어(굵게)+뜻 한 줄. 예문·문맥·이벤트 이력은 카드 탭 → 상세 (V-11)
- 디버그 빌드에서만 기존 raw 문자열을 상세 하단에 표시
완료 기준: 목록 screencap에 ISO 타임스탬프·"wordbook=" 문자열이 없음
```

완료(2026-07-03): 카드 목록의 raw `wordbook=`, `status=`, `due=ISO` 메타를 제거하고 상태 뱃지와 `다음 복습: 지금/오늘 HH:mm/내일/N일 뒤` 표기로 바꿨다. raw 메타는 debug 빌드의 카드 상세 하단에만 남긴다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_saved.png`

## UI-M3 — 컴포넌트 다듬기 (P3, 1일)

### [x] T6. 상태 메시지 Snackbar (V-6)

완료(2026-07-03): add sheet와 설정 메시지를 snackbar 형태로 표시하고, `뜻을 찾지 못했습니다`는 error container 색과 `직접 입력` 액션을 사용한다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T6_unknown_snackbar.png`

### [x] T7. 후보 chip 정규화/가로 스크롤 (V-7)

완료(2026-07-03): 후보를 정규화된 소문자 assist chip으로 표시하고 LazyRow로 가로 스크롤하게 바꿨다. URL 조각 제거는 QC 프로토콜 F-2에서 이미 처리한 기능 수정과 중복하지 않았다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T7_candidate_chips.png`

### [x] T8. 미리보기 카드 위계 (V-8)

완료(2026-07-03): preview를 단어, 원형, 품사/정의, 뜻, 예문, 출처, 소스 라벨 순서의 카드로 바꿨다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T8_preview_card.png`

### [x] T9. 입력 시트 기본 필드 축소 (V-9)

완료(2026-07-03): add sheet 기본 상태는 `단어 또는 문장` 1개 필드와 `미리보기`/`클립보드`/`직접 입력` 버튼만 보이고, 뜻/예문은 직접 입력 또는 preview 카드에서 수정한다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T9_input_sheet_default.png`

### [x] T10. 오늘 남은 복습 요약 (V-10)

완료(2026-07-03): 내 단어장 상단 요약을 `오늘 남은 복습 N개 · 취약 N · 전체 N`로 바꿨다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T10_summary_user_tab.png`

### [x] T11. 대비와 다크 모드 확인 (V-12)

완료(2026-07-03): 앱 theme가 system dark mode를 따르게 하고, 카드 메타/뱃지 텍스트를 Material3 대비 색으로 지정했다. 다크/라이트 모드 모두 실기기에서 확인했다. 증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T11_dark_mode_user_tab.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T11_light_mode_restored.png`

## UI-M4 — 알림 비주얼 (리뷰 문서 4장)

```text
T12: 알림 스몰 아이콘이 기본값이면 전용 아이콘으로 교체 (복습용/추천용 시각 구분)
T13: 실기기에서 알림 4종 screencap 확보 후 리뷰 4장의 원칙과 대조:
     첫 알림(뜻 없음) / 못외웠다 교체(BigText 뜻+예문) / 외웠다 ✓ confirmation /
     추천 소개형("오늘의 새 단어:") — 잠금 화면 버전 포함
     원칙과 다르면 텍스트/스타일만 수정 (스케줄링 로직 금지)
완료 기준: 4종+잠금화면 screencap이 리뷰 4장의 원칙과 일치
```

## UI-M5 — Mac (리뷰 5장, Android 완료 후)

```text
T14: 메뉴 바 팝오버를 guide 15.2 mockup 구조로 (오늘 due/새 단어/취약 + 3버튼)
T15: 캡처 팝업 위계: 단어 크게 → 뜻 → 예문 → 출처, Enter=저장
T16: Cloud sync 필드 디버그 게이팅 (T4와 동일 규칙)
주의: 수정 후 반드시 package_app.sh 재실행 → .app로 확인 (swift run은 알림 검증 불가)
```

## 순서와 충돌 관리

```text
권장 순서: UI-M1 → (기능 F-2 수정) → UI-M2 → QC B/F 재검증 → UI-M3 → UI-M4 → UI-M5
UI-M2는 MainActivity 대수술이므로, 진행 중인 기능 QC와 브랜치를 분리할 것.
각 마일스톤 완료 시 STATUS.md 갱신 + 이 문서의 해당 T-항목에 [x] 표시.
```

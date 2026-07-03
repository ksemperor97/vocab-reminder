# 비주얼 개선 리뷰 — 실기기 관찰 기반

근거: 2026-07-03, Galaxy SM-F766N 실기기 화면을 직접 관찰(scrcpy)한 결과.
관찰 범위는 **Android 메인 화면 전체**(입력→후보→설정→Cloud sync→단어 목록). 알림 비주얼과 Mac 앱 UI는 아직 미관찰이며 7장에 확인 항목으로 남긴다.
우선순위: P1(깨짐·즉시 수정) > P2(구조 개편) > P3(다듬기).

---

## 1. P1 — 레이아웃 깨짐 (관찰된 그대로)

### V-1. 설정 필드 라벨/값 클리핑

관찰: 설정 행의 필드 폭이 좁아 세 번째 필드부터 라벨이 **한 글자씩 세로로** 꺾인다.

```text
관찰된 상태:
[하루 목표: 10] [큐 상한: 20] [미|루|기: €] [잠자는 시작: 23:00] [잠자는 끝: 09:(] [시|간|대: A]
                              ↑ 라벨 세로 깨짐,        ↑ 값 "09:00"이 "09:("로 잘림
                                값 "60"이 "€"처럼 보임
```

제안: 필드를 한 줄에 우겨넣지 말고 2열 그리드 또는 세로 리스트로. 각 필드에 단위를 라벨에 포함("미루기(분)", "하루 목표(개)"). 최소 폭 보장, `maxLines=1` + 말줄임 금지(설정값은 잘리면 안 됨).

완료(2026-07-03, Codex UI-M1): 설정 필드를 세로 리스트로 바꿔 라벨과 값을 분리했다. HALF_OPENED/OPENED 모두 라벨 `하루 목표(개)`, `큐 상한(개)`, `미루기(분)`, `잠자는 시작`, `잠자는 끝`, `시간대`와 값 `10`, `20`, `60`, `23:00`, `09:00`, `Asia/Seoul`이 잘림 없이 보인다.

증거: 전 `/tmp/vocab_ui_20260703_130300/UI-M1/before/settings_fields_half_opened.png`, 후 `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_half_opened_full.png`, `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_fields_opened.png`, 상태 `/tmp/vocab_ui_20260703_130300/UI-M1/after/device_state_half_opened_after.txt`, `/tmp/vocab_ui_20260703_130300/UI-M1/after/device_state_opened_after.txt`

### V-2. [복습 재조정] 버튼 텍스트 오버플로

관찰: 다른 버튼은 알약형인데 이 버튼만 원형에 가깝게 줄어들며 "복습/재조/정"으로 3줄 꺾임.

제안: 세 버튼([설정 저장][오늘 편성][복습 재조정])을 같은 높이의 알약형으로 통일하고, 컨테이너가 좁으면 세로 스택으로 전환. 버튼 라벨은 절대 줄바꿈되지 않게.

완료(2026-07-03, Codex UI-M1): 세 액션을 같은 전체폭 알약형 버튼 세로 스택으로 통일했다. HALF_OPENED/OPENED 모두 `설정 저장`, `오늘 편성`, `복습 재조정`이 한 줄로 보인다.

증거: 전 `/tmp/vocab_ui_20260703_130300/UI-M1/before/settings_fields_half_opened.png`, 후 `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_buttons_half_opened.png`, `/tmp/vocab_ui_20260703_130300/UI-M1/after/settings_buttons_opened_full.png`

---

## 2. P2 — 정보 구조 (지금은 "개발자 패널"이지 제품 UI가 아님)

### V-3. 모든 것이 하나의 스크롤 컬럼에 있음

관찰: 단어 입력, 후보 선택, 설정, Firebase 로그인, 단어 목록, 복습 이벤트가 한 화면에 세로로 나열. UX 문서가 그리는 앱(입력은 3초 미니 시트, 목록/설정 분리)과 구조 자체가 다르다.

제안 구조 (UX doc 2.4, 9장, QC-G9 근거):

```text
하단 탭 3개:
[내 단어장]   카드 리스트 + 상단 요약(오늘 due N · 취약 N) + FAB(+ 단어 추가)
[추천 단어장] 추천 카드 리스트 (내 단어장과 분리 — QC-G9)
[설정]        하루 목표/큐 상한/미루기/잠자는 시간/추천 토글 + 고급(동기화)

단어 추가/캡처(PROCESS_TEXT·공유 포함) → 전체 화면이 아니라 bottom sheet.
저장 후 시트만 닫히고 원래 문맥 유지 (UX 2.4 "읽던 앱으로 복귀"와 일치)
```

완료(2026-07-03, Codex UI-M2): Android 메인을 `내 단어장`/`추천 단어장`/`설정` 하단 탭으로 분리했다. 내 단어장에는 due/취약/전체 요약과 카드 목록, FAB만 남기고, 추천 단어장은 recommended 카드 전용 화면으로 분리했다. 단어 추가, PROCESS_TEXT, SHARE 진입은 같은 bottom sheet를 사용하며 저장 후 시트가 닫힌다. 외부 앱에서 진입한 PROCESS_TEXT/SHARE는 저장 후 원래 Chrome으로 복귀함을 QC-B4/B5에서 확인했다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/recommendation_tab.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/settings_top.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B1_preview_visible.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/share_entry_sheet.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B4_after_save_return.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B5_post_patch_after_save.png`

### V-4. Cloud sync(Firebase key/token 입력)가 메인 화면 한복판에 노출

관찰: Web API key, email/password, ID token, Functions URL 필드가 단어 목록보다 위에 있다.

제안: 설정 > "동기화" 하위 화면으로 이동. 토큰/URL 직접 붙여넣기 필드는 디버그 빌드에서만 노출. 릴리스 사용자에게 보일 것은 "로그인 상태 + 마지막 동기화 시각" 한 줄이면 된다.

완료(2026-07-03, Codex UI-M2): Cloud sync UI를 설정 탭의 `동기화` 하위 화면으로 이동했다. 메인/내 단어장과 설정 메인 화면에는 Firebase 입력 필드가 없고, debug 빌드에서만 동기화 화면의 `Debug sync fields` 아래에 Web API key, ID token, Functions URL, local export/import 버튼이 보인다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/settings_top.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/sync_screen.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/sync_screen.xml`

### V-5. 카드 메타데이터가 디버그 문자열

관찰:

```text
wordbook=user · status=new · interval=0d · due=2026-07-03T00:18:31.110434Z
```

제안: 사용자 언어로 변환.

```text
[새 단어]  다음 복습: 오늘 09:18        ← 상태는 뱃지, 시각은 상대/현지 표기
뱃지 색: 새 단어=파랑 · 배우는 중=보라 · 복습=초록 · 재학습=주황 · 완료=회색 · 알림 보류=빨강 테두리
```

ISO 타임스탬프·필드명 노출은 디버그 모드 토글로만.

완료(2026-07-03, Codex UI-M2): 카드 목록은 단어, 뜻, 상태 뱃지, `다음 복습: ...`만 기본 표시한다. 목록 XML에서 `wordbook=`, `status=`, `due=` 및 ISO timestamp가 보이지 않음을 확인했다. raw 메타는 debug 빌드에서 카드 상세 하단에만 남겼다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M2/user_tab_default.xml`, `/tmp/vocab_ui_20260703_130300/UI-M2/qc_recheck/B6_saved.png`

---

## 3. P3 — 컴포넌트 다듬기

```text
V-6. 상태 메시지("문장이 들어왔습니다...", "뜻을 찾지 못했습니다...")가 버튼 아래 일반
     텍스트로 남아 있음 → 스낵바 또는 색 있는 인라인 콜아웃으로. 오류(뜻 없음)는 경고색.

V-7. 후보 chip: URL 조각 노출은 기능 버그(F-2)로 수정하되, 비주얼로도 chip에 소문자
     정규화된 형태를 보여주고 선택 시 즉시 미리보기로 전환. 후보 7개 이상이면 wrap이
     아니라 가로 스크롤.

V-8. 미리보기 결과("aaaaaa → aaaaaa" 식 한 줄)가 밋밋함 → UX 2.1 팝오버 형식의 카드로:
     단어 → lemma / 뜻 / 품사 / 예문 / 출처 순. 뜻을 찾은 소스(사전/로컬)를 작은 라벨로.

V-9. 단어/뜻/예문 입력 필드가 항상 3개 다 펼쳐져 있음 → 기본은 "단어 또는 문장" 하나만
     보이고, 미리보기 후 뜻/예문은 채워진 카드에서 수정하는 흐름이 UX 문서와 일치.

V-10. 요약 라인 "저장된 단어 1개 · 취약 단어 0개" → 여기에 "오늘 남은 복습 N개"를 추가
      (menu bar 15.2와 동일한 3종 요약). 탭 구조 도입 시 상단 고정.

V-11. 카드 리스트: 단어(굵게)-뜻-예문이 같은 위계로 보임 → 단어/뜻만 기본 표시, 예문·
      문맥·이벤트는 카드 탭 시 상세로. 리스트 밀도 확보.

V-12. 전반 스타일: 연보라 컨테이너 + 흰 배경 조합은 무난하나 대비가 낮은 텍스트(회색
      메타 라인)가 많음 → Material3 dynamic color 유지하되 본문 대비 WCAG AA 확인,
      다크 모드 실기기 확인(미관찰).
```

완료(2026-07-03, Codex UI-M3): V-6~V-12를 반영했다. 상태 메시지는 snackbar로 바꾸고 뜻 없음 메시지에는 error 색과 `직접 입력` 액션을 붙였다. 후보는 정규화된 소문자 chip 가로 스크롤로 표시한다. 미리보기는 단어, 원형, 품사/정의, 뜻, 예문, 출처, 소스 라벨 순서의 카드로 정리했고, 입력 시트 기본 상태는 `단어 또는 문장` 1개 필드만 보이게 줄였다. 내 단어장 요약은 `오늘 남은 복습 N개 · 취약 N · 전체 N`로 바꿨고, 앱은 system dark mode를 따르며 메타/뱃지 텍스트는 Material3 대비 색을 사용한다.

증거: `/tmp/vocab_ui_20260703_130300/UI-M3/T6_unknown_snackbar.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T7_candidate_chips.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T8_preview_card.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T9_input_sheet_default.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T10_summary_user_tab.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T11_dark_mode_user_tab.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T11_light_mode_restored.png`

---

## 4. 알림 비주얼 원칙 (구현 검증 시 함께 볼 것)

```text
- 첫 알림: 제목=단어만. 본문은 "무슨 뜻이었지?" 한 줄. 뜻 절대 노출 금지 (UX 3.1)
- 못외웠다 교체 알림: BigTextStyle로 뜻 1줄 + 예문 1줄. 예문은 이탤릭/보조색 구분
- 외웠다 confirmation: "단어 ✓" + 뜻 + "다음 복습: ..." — 체크마크로 성공 피드백
- 추천 소개형: "오늘의 새 단어:" 프리픽스로 복습 알림과 첫눈에 구분되게 (아이콘 색 분리 고려)
- 앱 아이콘/알림 스몰 아이콘이 현재 기본값인지 확인 — 기본 아이콘이면 교체 필요
```

---

## 5. Mac 앱 (미관찰 — 방향만)

```text
- 메뉴 바 팝오버는 15.2 mockup(오늘 due/새 단어/취약 단어 + 3버튼)을 기준으로.
- 캡처 팝오버는 필드 나열이 아니라 "단어 크게 → 뜻 → 예문 → 출처" 위계로, Enter=저장.
- Cloud sync 필드 노출 문제(V-4)는 Mac에도 동일하게 적용.
```

---

## 6. 우선순위 요약

| ID | 내용 | 우선순위 | 성격 |
|---|---|---|---|
| V-1 | 설정 필드 라벨/값 클리핑 | P1 | 깨짐 |
| V-2 | 복습 재조정 버튼 오버플로 | P1 | 깨짐 |
| V-3 | 탭 구조 도입 + 캡처 bottom sheet | P2 | 구조 |
| V-4 | Cloud sync를 설정 하위/디버그로 이동 | P2 | 구조 |
| V-5 | 카드 메타 디버그 문자열 → 뱃지+상대시간 | P2 | 구조 |
| V-6~12 | 컴포넌트 다듬기 | P3 | 폴리시 |

## 7. 미관찰 — 스크린샷 확보 후 이 문서에 추가할 것

```text
[ ] 알림 3종 (첫 알림 / 못외웠다 교체 / 외웠다 confirmation) — 잠금 화면 포함
[ ] 추천 소개형 알림
[x] 후보 선택 → 저장까지의 미리보기 실제 모습 (정상 단어로) — `/tmp/vocab_ui_20260703_130300/UI-M3/T7_candidate_chips.png`, `/tmp/vocab_ui_20260703_130300/UI-M3/T8_preview_card.png`
[x] 다크 모드 전체 화면 — `/tmp/vocab_ui_20260703_130300/UI-M3/T11_dark_mode_user_tab.png`
[ ] 폴더블(SM-F766N) 펼침 화면에서의 레이아웃 (좁은 화면 접힘 상태 vs 펼침)
[ ] Mac 메뉴 바 팝오버 / 카드 목록 / 캡처 팝업
```

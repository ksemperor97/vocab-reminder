# Codex 실기기 QC/디버깅 프로토콜

대상: USB 연결된 Galaxy (SM-F766N, Android 16) + Mac.
검증 항목의 원본은 `docs/qc_user_facing_checklist.md`다. 이 문서는 그것을 **어떻게** 실행하고 **무엇을 증거로 남길지** 정한다.

## 0. 절대 규칙

```text
1. 통과 판정은 반드시 기기에서 얻은 증거(스크린샷/uiautomator dump/dumpsys 출력)와 함께 기록한다.
   단위 테스트/Robolectric 통과는 이 문서에서 증거로 인정하지 않는다.
2. 실패한 항목은 즉시 고치려 들지 말고, 먼저 qc_user_facing_checklist.md에 [F]로 기록한다.
   섹션 하나를 끝낸 뒤 실패 항목을 모아서 수정 → 해당 항목만 재검증한다.
3. 검증 절차가 불가능하면 [B]로 남기고 이유를 적는다. 조용히 건너뛰지 않는다.
4. 기기 날짜/설정을 바꿨으면 반드시 원상 복구하고, 복구했음을 기록한다.
5. 진행 상황은 qc_user_facing_checklist.md의 체크 표시와 STATUS.md에 반영한다.
```

## 1. 이미 발견된 버그 (2026-07-03, 실기기/실행 확인 — 우선 처리)

```text
F-1 [수정됨, 회귀 확인 완료] Mac 앱이 swift run에서 즉시 크래시.
    원인: 번들 없는 프로세스에서 UNUserNotificationCenter.current() 접근.
    수정: MacNotificationScheduler에 번들 가드 추가됨.
    확인: (a) swift run 정상 실행 확인
          (b) mac/VocabReminderMac/package_app.sh로 .app 빌드 → open으로 실행 →
              signed .app에서 알림 전달 확인 (QC-H1/H6)
          (c) 코드 수정 후 .app 재패키징 수행

F-2 [수정됨, 회귀 확인 완료] URL을 캡처하면 URL 조각이 단어 후보로 나온다.
    재현: "https://m.ppomppu.co.kr/new/bbs_view.php?id=..." 를 공유/입력
    실제: 후보 chips = [https][ppomppu][new][bbs][view][php][extref]
    기대: URL은 단어 후보에서 제외 (guide 10.7 정규화에 URL/도메인/쿼리 토큰 필터 추가)
    수정 위치: CaptureNormalizer.kt (+ Mac CaptureAndDictionary.swift 동일 로직, 공용 벡터 추가)
    확인: URL share에서 후보 선택 UI가 뜨지 않고 URL 토큰 후보가 사라짐 (QC-B3a)

F-3 [테스트 노하우, 앱 버그 아님] 기기 IME가 한국어 상태면 키 이벤트 기반 입력이
    한글로 조합된다 ("elicit" → "aaaaaa").
    → 텍스트 입력은 반드시 `adb shell input text '...'` (직접 커밋)로 하고,
      입력 직후 uiautomator dump로 필드 값을 확인한 뒤 진행한다.
```

## 2. adb 실행 레시피

```sh
ADB=adb   # 또는 ~/Library/Android/sdk/platform-tools/adb
PKG=com.vocabreminder.android

# 화면 확인 (모든 판정의 기본 증거)
$ADB exec-out screencap -p > /tmp/qc_$(date +%H%M%S).png

# UI 트리 (텍스트/버튼 좌표 확인)
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml

# 탭/스와이프/텍스트
$ADB shell input tap X Y
$ADB shell input swipe X1 Y1 X2 Y2 300
$ADB shell input text 'elicit'          # ASCII만. 한글은 아래 클립보드 경유

# 앱 실행/초기화
$ADB shell am start -n $PKG/.MainActivity
$ADB shell pm clear $PKG                 # 상태 리셋 (섹션 시작 전 깨끗한 상태 필요 시)

# 알림 검사 (제목/본문/action 확인)
$ADB shell dumpsys notification --noredact | grep -A 30 vocab
# 알림 셰이드 열기/닫기 + action 버튼 탭:
$ADB shell cmd statusbar expand-notifications
#   → uiautomator dump로 "외웠다"/"못외웠다"/"나중에 하기" 버튼 bounds 찾아 tap
$ADB shell cmd statusbar collapse

# capture 경로 테스트
$ADB shell am start -a android.intent.action.PROCESS_TEXT -t text/plain \
    --es android.intent.extra.PROCESS_TEXT "tentative" $PKG
$ADB shell am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "The results are tentative, but robust." $PKG

# WorkManager 잡 즉시 실행 (10분/60분 대기 없이 알림 트리거)
$ADB shell dumpsys jobscheduler | grep -i $PKG      # job id 확인
$ADB shell cmd jobscheduler run -f $PKG <JOB_ID>
```

### 2.1 앱 내부 상태 조작 (시간 이동 대체)

debug 빌드이므로 `run-as`로 로컬 저장소를 직접 읽고 쓸 수 있다. 기기 날짜 변경보다 이것을 우선 사용한다.

```sh
$ADB shell run-as $PKG ls files/                       # 저장 파일 확인
$ADB exec-out run-as $PKG cat files/<store>.json > /tmp/store.json
# due_at을 과거로, interval을 14/90일로, 카드 50개 대량 생성 등 수정 후:
$ADB push /tmp/store.json /sdcard/ && \
$ADB shell run-as $PKG cp /sdcard/store.json files/<store>.json
# 수정 후 앱 프로세스 재시작: am force-stop → am start
```

이 방법으로 만들 수 있는 시나리오: interval 14/90일 카드의 lapse-회복(QC-D5), due 15개 초과 편성(QC-F3), 내 단어장 50개(QC-G2), mastered 진입(QC 부록).
편의를 위해 **디버그 전용 seed 스크립트**(`tools/qc_seed.sh <scenario>`)를 만들어 두는 것을 권장한다 — 시나리오: `overdue1`, `due15`, `cards50`, `interval14`, `interval90`.

### 2.2 한글 입력 (뜻 수정 테스트용)

```sh
# 클립보드에 한글을 넣고 앱의 [클립보드] 버튼 경로를 쓰거나,
# 뜻 필드는 ASCII로 대체 입력해도 판정에는 지장 없음 (표시 경로만 확인하면 됨)
```

## 3. 실행 순서

```text
1. F-1 회귀 확인 (Mac) → F-2 수정 + 공용 벡터 추가 + 단위테스트 → 커밋
2. Galaxy: qc_user_facing_checklist.md의 A → B → C → D → E → F → G 순서로.
   - 각 항목: 조작 → screencap → 판정 기록 (증거 파일명을 항목 옆에 적는다)
   - C/D/E의 "n분 뒤" 항목: jobscheduler run -f로 강제 실행하되,
     최소 1회는 실제 대기(10분)로 자연 발화도 확인한다 (QC-C1)
3. Mac: H 구간 (.app 번들로. swift run은 알림 검증 불가)
4. 동기화: I 구간 (자동 HTTPS tunnel release 배포 후 서버 경유로 검증.
   로컬 export/import 왕복은 과거 임시 검증이며 최종 통과로 치지 않는다)
5. J 구간 (강제 종료/재부팅/비행기 모드 — 재부팅 리스케줄 미구현이면 [F]로 기록 후 구현)
6. 전체 실패 목록 정리 → 수정 → 실패 항목만 재검증 → STATUS.md 갱신
```

## 4. 판정 시 특히 의심할 것 (설계 문서와 어긋나기 쉬운 지점)

```text
- 첫 알림에 뜻이 보이면 안 된다 (잠금 화면 포함) — dumpsys로 본문 확인
- [외웠다] confirmation은 뜻을 보여주고 ~5초 뒤 스스로 사라져야 한다 (setTimeoutAfter)
- [못외웠다]는 "같은 notification id 교체"여야 한다 — 새 알림이 하나 더 생기면 실패
- [나중에 하기]는 review_event(unavailable)를 남기되 interval/status 불변이어야 한다
  → run-as로 store를 열어 이벤트/카드 값을 직접 대조
- 자동 재조정은 due_at만 바꿔야 한다 — interval/lapse_count가 바뀌면 실패
- 추천 단어 첫 알림은 소개형(뜻 표시)이고, 복습이 목표를 채우는 날에는 추천이 0이어야 한다
- 뜻 없는 카드("뜻 없음 · 알림 보류")에 알림이 나가면 실패
```

## 5. 보고 형식

섹션 완료마다:

```text
Section: (예: C. 복습 알림 코어)
Passed: QC-C1, C2, C4 (증거: /tmp/qc_*.png 목록)
Failed: QC-C3 — 증상 / 재현 / 기대(UX·guide 근거)
Blocked: (있으면 + 이유)
Fixed in this pass: (커밋 해시)
```

전체 종료 조건: A~J에서 [ ]가 없고, [F]는 전부 수정·재검증됐으며, [B]는 사유가 기록된 상태.

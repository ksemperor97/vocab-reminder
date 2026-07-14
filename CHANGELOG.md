# VocabReminder changelog

## 0.2.0 - 2026-07-14

- Mac mini sync runtime을 소스 checkout과 Dropbox에서 분리하고, 고정 Node 실행 환경·원자적 배포·자동 rollback·SQLite backup/restore 검증을 추가했다.
- quick tunnel을 연결 상태로 감시하고 장애 시 자동 재발급·Worker 게시·stable endpoint 검증을 수행한다.
- Android와 Mac에 background dictionary enrichment, crash-safe append-only 뜻 후보, 카드 전체 이력과 후보 복구 UI를 추가했다.
- Android 첫 실행 안내와 상태 진단, Mac 캡처 Service 설치 진단을 추가했다.
- release 서명·secret 경계·request ID·redacted diagnostics·통합 QC를 제품 gate로 추가했다.

현재 개인 소유 장비 sideload용 release다. Google Play 배포, Developer ID/notarization, 운영 token 회전, 재부팅·24시간 soak는 별도 승인 gate를 통과해야 한다.

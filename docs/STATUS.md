## 현재 마일스톤: M5-M7 로컬 MVP 구현 완료, 실서비스 연결 대기
## 완료: M0-M4 로컬 구현, M5 양방향 sync callable 계약 + Android/Mac 로컬 import/export + Android callable sync client path + duplicate lemma import merge, M6 Android/Mac 로컬 검증, M7 generated ranked recommendation catalogue MVP + 추천 시작 안내 1회 상태 + 추천 액션(started/known/dismissed) instrumentation coverage + Mac recommendation_feedback sync preservation + 추천 off 공용 scheduler vector
## 진행 중: M5 Firebase 실계정 Auth/Functions URL 연결 및 UI 트리거, M7 full-scale recommendation definition/translation pipeline
## 막힌 것 / 사용자 확인 필요: Firebase 프로젝트 생성/키 발급은 사용자 계정이 필요하므로 README에 절차만 문서화됨. Android는 Firebase SDK 설정 파일 없이 빌드 가능한 callable sync client를 갖고 있으며, 실제 sync 실행에는 Firebase Auth ID token과 Functions base URL이 필요함. 추천 MVP는 ISC 라이선스 `subtlex-word-frequencies` 기반 149-entry generated catalogue로 구현됨. 전체 74,286-word list의 뜻/번역까지 포함한 운영 데이터 파이프라인은 아직 남아 있음.
## 마지막 갱신: 2026-07-02

# 공부 집중 사이트 차단기

hosts 파일로 유튜브·SNS·커뮤니티 등 딴짓 사이트를 차단합니다.

- 실행: `dist/StudyBlocker.exe` 더블클릭 (관리자 권한 요청창에서 "예")
- 집중 모드: 시간을 정하면 그 시간 동안 해제 불가, 창을 닫아도 차단 유지·재실행 시 이어짐
- 차단 목록은 exe 옆 `sites.txt`에서 수정하거나 메뉴에서 추가/삭제
- 재빌드: `cd blocker && GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o ../dist/StudyBlocker.exe .`

## 안드로이드 (APK)

- 설치: `dist/StudyBlocker.apk`를 폰으로 옮겨 설치 ("출처를 알 수 없는 앱 설치" 허용 필요)
- 처음 차단을 켤 때 VPN 연결 요청이 뜨면 "확인" (DNS 질의만 처리하며, 다른 트래픽은 그대로 통과)
- 앱 차단(유튜브·인스타 앱 등): 설정 > 접근성에서 '공부 집중 차단기'를 켜면, 차단 중 목록의 앱을 열 때 홈 화면으로 돌려보냄. '차단할 앱 선택'에서 앱 추가/삭제
- 집중 모드/수동 차단/사이트 추가·삭제는 Windows 버전과 동일하게 동작
- 재빌드: `cd android && ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

# 공부 집중 사이트 차단기

hosts 파일로 유튜브·SNS·커뮤니티 등 딴짓 사이트를 차단합니다.

- 실행: `dist/StudyBlocker.exe` 더블클릭 (관리자 권한 요청창에서 "예")
- 집중 모드: 시간을 정하면 그 시간 동안 해제 불가, 창을 닫아도 차단 유지·재실행 시 이어짐
- 차단 목록은 exe 옆 `sites.txt`에서 수정하거나 메뉴에서 추가/삭제
- 재빌드: `cd blocker && GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o ../dist/StudyBlocker.exe .`

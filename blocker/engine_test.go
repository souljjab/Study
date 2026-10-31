package main

import (
	"path/filepath"
	"slices"
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 31, 9, 0, 0, 0, time.Local)

func do(t *testing.T, e *Engine, action, payload string, now time.Time) {
	t.Helper()
	if err := e.Do(action, []byte(payload), now); err != nil {
		t.Fatalf("%s: %v", action, err)
	}
}

func TestPomodoroCycleAndBreakUnblock(t *testing.T) {
	e := NewEngine("", t0)
	var events []string
	e.OnPhase = func(k string) { events = append(events, k) }
	do(t, e, "toggleRun", "", t0)
	if !e.Enforcing() || len(e.BlockedDomains()) == 0 {
		t.Fatal("타이머 시작 시 차단이 걸려야 함")
	}
	e.Tick(t0.Add(25 * time.Minute))
	v := e.View(t0.Add(25 * time.Minute))
	if v.Timer.Phase != "break" || v.Timer.Remaining != 300 {
		t.Fatalf("25분 후 5분 휴식이어야 함: %+v", v.Timer)
	}
	if e.Enforcing() || e.BlockedDomains() != nil {
		t.Fatal("휴식 중엔 차단 해제(기본 설정)")
	}
	if len(v.Sessions) != 1 || v.Sessions[0].Dur != 1500 || v.TodayFocus != 1500 {
		t.Fatalf("세션 기록/집중 시간 오류: %+v %d", v.Sessions, v.TodayFocus)
	}
	e.Tick(t0.Add(30 * time.Minute))
	v = e.View(t0.Add(30 * time.Minute))
	if v.Timer.Phase != "focus" || v.Timer.Session != 2 || !e.Enforcing() {
		t.Fatalf("휴식 후 2세션 집중: %+v", v.Timer)
	}
	if !slices.Equal(events, []string{"break", "focus"}) {
		t.Fatalf("이벤트: %v", events)
	}
}

func TestLongBreakAfterFourSessions(t *testing.T) {
	e := NewEngine("", t0)
	do(t, e, "toggleRun", "", t0)
	now := t0.Add((25*4 + 5*3) * time.Minute)
	e.Tick(now)
	v := e.View(now)
	if v.Timer.Phase != "break" || v.Timer.Total != 15*60 {
		t.Fatalf("4세션 후 긴 휴식 15분: %+v", v.Timer)
	}
}

func TestFreeTimerEndsAndTurnsOffFocus(t *testing.T) {
	e := NewEngine("", t0)
	do(t, e, "setMode", `{"mode":"free"}`, t0)
	do(t, e, "freeMin", `{"min":25}`, t0)
	do(t, e, "toggleRun", "", t0)
	e.Tick(t0.Add(25 * time.Minute))
	v := e.View(t0.Add(25 * time.Minute))
	if v.Timer.Running || v.FocusOn || v.Timer.Remaining != 0 {
		t.Fatalf("자유 타이머 종료 후 집중 모드 꺼짐: %+v focus=%v", v.Timer, v.FocusOn)
	}
	if len(v.Sessions) != 1 || v.Sessions[0].Mode != "자유" {
		t.Fatalf("세션: %+v", v.Sessions)
	}
}

func TestLockPolicy(t *testing.T) {
	e := NewEngine("", t0)
	do(t, e, "settings", `{"patch":{"policy":"lock"}}`, t0)
	do(t, e, "toggleRun", "", t0)
	for _, a := range []struct{ action, payload string }{
		{"toggleFocus", ""}, {"reset", ""}, {"skip", ""},
		{"toggleCat", `{"pid":1,"cat":"SNS"}`}, {"setAll", `{"pid":1,"on":false}`},
		{"setActive", `{"pid":3}`}, {"settings", `{"patch":{"policy":"instant"}}`},
		{"addAllow", `{"domain":"youtube.com"}`},
	} {
		if err := e.Do(a.action, []byte(a.payload), t0); err != errLocked {
			t.Errorf("%s %s: 잠금 중 거부돼야 함, got %v", a.action, a.payload, err)
		}
	}
	if e.QuitAllowed() {
		t.Error("잠금 중 종료 불가")
	}
	// 차단을 늘리는 동작은 허용
	do(t, e, "toggleCat", `{"pid":1,"cat":"쇼핑"}`, t0)
	// 일시정지하면 잠금이 풀린다(디자인: 타이머가 도는 동안만 잠금)
	do(t, e, "toggleRun", "", t0)
	do(t, e, "toggleFocus", "", t0)
}

func TestDomainsAndAllow(t *testing.T) {
	e := NewEngine("", t0)
	do(t, e, "toggleFocus", "", t0)
	d := e.BlockedDomains()
	if !slices.Contains(d, "youtube.com") || !slices.Contains(d, "namu.wiki") || slices.Contains(d, "news.naver.com") {
		t.Fatalf("기본 목록 도메인 오류: %v", d)
	}
	do(t, e, "addAllow", `{"domain":"https://www.youtube.com/"}`, t0)
	if slices.Contains(e.BlockedDomains(), "youtube.com") {
		t.Fatal("허용 목록은 차단에서 빠져야 함")
	}
	if err := e.Do("addAllow", []byte(`{"domain":"youtube.com/@ebsi"}`), t0); err == nil {
		t.Fatal("경로 허용은 거부")
	}
	if err := e.Do("addSite", []byte(`{"pid":1,"domain":"namu.wiki"}`), t0); err == nil {
		t.Fatal("중복 거부")
	}
	do(t, e, "addSite", `{"pid":1,"domain":"https://Example.org/path","cat":"기타"}`, t0)
	if !slices.Contains(e.BlockedDomains(), "example.org") {
		t.Fatal("추가한 사이트 차단")
	}
}

func TestPersistAndCatchUp(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	e := NewEngine(path, t0)
	do(t, e, "toggleRun", "", t0)
	e.Tick(t0.Add(10 * time.Minute))
	e.Save(t0.Add(10 * time.Minute))
	// 프로그램이 꺼진 동안 19분이 흐름 → 다시 켜면 휴식 1분 남음
	e2 := NewEngine(path, t0.Add(29*time.Minute))
	v := e2.View(t0.Add(29 * time.Minute))
	if v.Timer.Phase != "break" || v.Timer.Remaining != 60 || !v.Timer.Running {
		t.Fatalf("따라잡기 실패: %+v", v.Timer)
	}
	if v.TodayFocus != 1500 {
		t.Fatalf("집중 시간 %d", v.TodayFocus)
	}
}

func TestHitsAndTop(t *testing.T) {
	e := NewEngine("", t0)
	do(t, e, "toggleRun", "", t0)
	for i := 0; i < 3; i++ {
		e.RecordHit("youtube.com", t0)
	}
	e.RecordHit("instagram.com", t0)
	v := e.View(t0)
	if v.TodayHits != 4 || v.Top[0].Domain != "youtube.com" || v.Timer.SessionBlocked != 4 {
		t.Fatalf("집계 오류: %+v", v)
	}
}

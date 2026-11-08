package main

import (
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func TestEmergencyUnlockValidation(t *testing.T) {
	e := NewEngine("", t0)
	if err := e.Do("emergencyUnlock", []byte(`{"reason":"급한 과제 제출이 필요해요","min":10}`), t0); err == nil {
		t.Fatal("막고 있는 게 없으면 거부")
	}
	do(t, e, "toggleRun", "", t0)
	for _, bad := range []string{
		`{"reason":"그냥","min":10}`,             // 사유가 짧음
		`{"reason":"          ","min":10}`,     // 공백만
		`{"reason":"학교 공지 확인이 필요해요","min":0}`,  // 0분
		`{"reason":"학교 공지 확인이 필요해요","min":61}`, // 60분 초과
	} {
		if err := e.Do("emergencyUnlock", []byte(bad), t0); err == nil {
			t.Errorf("거부돼야 함: %s", bad)
		}
	}
}

func TestEmergencyUnlockPausesBlockingAndLogs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.json")
	e := NewEngine(path, t0)
	var ev []string
	e.OnPhase = func(k string) { ev = append(ev, k) }
	do(t, e, "settings", `{"patch":{"policy":"lock","focusMin":90}}`, t0)
	do(t, e, "toggleRun", "", t0)
	now := t0.Add(5 * time.Minute)
	e.Tick(now)
	do(t, e, "emergencyUnlock", `{"reason":"  학교   공지 확인이  필요해요 ","min":10}`, now)
	if e.BlockedDomains() != nil || e.Enforcing() {
		t.Fatal("해제 중엔 차단이 멈춰야 함")
	}
	v := e.View(now)
	if v.UnlockUntil != now.Add(10*time.Minute).Unix()*1000 || len(v.Unlocks) != 1 || v.Unlocks[0].Reason != "학교 공지 확인이 필요해요" {
		t.Fatalf("기록: %+v until=%d", v.Unlocks, v.UnlockUntil)
	}
	if !v.Timer.Running {
		t.Fatal("타이머는 계속 돈다")
	}
	// 해제 중에도 잠금 규칙은 그대로
	for _, a := range []struct{ action, payload string }{
		{"toggleFocus", ""}, {"settings", `{"patch":{"policy":"instant"}}`}, {"toggleCat", `{"pid":1,"cat":"SNS"}`},
	} {
		if err := e.Do(a.action, []byte(a.payload), now); err != errLocked {
			t.Errorf("%s: 해제 중에도 잠금 거부, got %v", a.action, err)
		}
	}
	if e.QuitAllowed() {
		t.Error("해제 중에도 종료 불가")
	}
	if err := e.Do("emergencyUnlock", []byte(`{"reason":"한 번 더 풀어 주세요 제발요","min":5}`), now); err == nil {
		t.Error("겹쳐서 해제 불가")
	}
	// 10분 지나면 저절로 다시 차단
	e.Tick(now.Add(10 * time.Minute))
	if !e.Enforcing() || e.BlockedDomains() == nil {
		t.Fatal("시간이 지나면 다시 차단")
	}
	if len(ev) != 1 || ev[0] != "unlockEnd" {
		t.Fatalf("이벤트 %v", ev)
	}
	v = e.View(now.Add(10 * time.Minute))
	if v.Unlocks[0].Actual != 600 || v.Unlocks[0].Ongoing || v.UnlockUntil != 0 {
		t.Fatalf("실제 시간: %+v", v.Unlocks[0])
	}
	// 두 번째: 3분 만에 직접 다시 차단 → 기록은 최신순
	later := now.Add(20 * time.Minute)
	e.Tick(later)
	do(t, e, "emergencyUnlock", `{"reason":"부모님께 급히 연락해야 해요","min":30}`, later)
	e.Tick(later.Add(3 * time.Minute))
	do(t, e, "endUnlock", "", later.Add(3*time.Minute))
	if !e.Enforcing() {
		t.Fatal("다시 차단")
	}
	e.Save(later.Add(3 * time.Minute))
	e2 := NewEngine(path, later.Add(4*time.Minute))
	v = e2.View(later.Add(4 * time.Minute))
	if len(v.Unlocks) != 2 || v.Unlocks[0].Minutes != 30 || v.Unlocks[0].Actual != 180 || v.Unlocks7d != 2 {
		t.Fatalf("저장된 기록: %+v", v.Unlocks)
	}
}

func TestEmergencyUnlockDuringSchedule(t *testing.T) {
	now := at(2, 10, 0)
	e := NewEngine("", now)
	do(t, e, "settings", `{"patch":{"policy":"lock"}}`, now)
	do(t, e, "addSchedule", `{"sched":{"days":[1],"start":"09:00","end":"12:00","presetId":1}}`, now)
	id := e.View(now).Schedules[0].ID
	do(t, e, "emergencyUnlock", `{"reason":"온라인 강의 링크가 유튜브예요","min":15}`, now)
	if e.BlockedDomains() != nil {
		t.Fatal("예약 차단도 멈춤")
	}
	if err := e.Do("removeSchedule", []byte(fmt.Sprintf(`{"id":%d}`, id)), now); err != errLocked {
		t.Fatalf("해제 중에도 적용 중인 예약은 못 지움: %v", err)
	}
	e.Tick(now.Add(15 * time.Minute))
	if e.BlockedDomains() == nil {
		t.Fatal("15분 뒤 예약 차단 복귀")
	}
}

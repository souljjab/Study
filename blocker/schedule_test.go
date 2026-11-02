package main

import (
	"fmt"
	"slices"
	"testing"
	"time"
)

// 2026-11-02는 월요일.
func at(day, hh, mm int) time.Time { return time.Date(2026, 11, day, hh, mm, 0, 0, time.Local) }

func TestScheduleActiveAt(t *testing.T) {
	weekday := Schedule{Days: []int{1, 2, 3, 4, 5}, Start: "09:00", End: "12:00", Enabled: true}
	night := Schedule{Days: []int{0}, Start: "23:00", End: "07:00", Enabled: true} // 일요일 밤 시작
	cases := []struct {
		s    Schedule
		now  time.Time
		want bool
	}{
		{weekday, at(2, 9, 0), true},   // 월 09:00 시작 포함
		{weekday, at(2, 11, 59), true}, // 월 11:59
		{weekday, at(2, 12, 0), false}, // 끝 시각은 제외
		{weekday, at(1, 10, 0), false}, // 일요일
		{night, at(1, 23, 30), true},   // 일 23:30
		{night, at(2, 6, 59), true},    // 월 새벽: 일요일에 시작한 예약
		{night, at(2, 7, 0), false},    // 월 07:00 끝
		{night, at(2, 23, 30), false},  // 월 밤은 요일 아님
		{Schedule{Days: []int{1}, Start: "09:00", End: "12:00"}, at(2, 10, 0), false}, // 꺼진 예약
	}
	for i, c := range cases {
		if got := c.s.activeAt(c.now); got != c.want {
			t.Errorf("#%d %s-%s %v: got %v", i, c.s.Start, c.s.End, c.now.Format("Mon 15:04"), got)
		}
	}
	if got := weekday.nextStart(at(2, 13, 0)); got != "내일 09:00" {
		t.Errorf("next %q", got)
	}
	if got := weekday.nextStart(at(6, 13, 0)); got != "월 09:00" { // 금 13:00 → 월
		t.Errorf("next(금) %q", got)
	}
	if got := weekday.nextStart(at(2, 8, 0)); got != "오늘 09:00" {
		t.Errorf("next(아침) %q", got)
	}
}

func TestScheduleBlocksAndEvents(t *testing.T) {
	e := NewEngine("", at(2, 8, 59))
	var ev []string
	e.OnPhase = func(k string) { ev = append(ev, k) }
	do(t, e, "addSchedule", `{"name":"오전 수업","sched":{"days":[1,2,3,4,5],"start":"09:00","end":"12:00","presetId":3}}`, at(2, 8, 59))
	if e.BlockedDomains() != nil {
		t.Fatal("시작 전엔 차단 없음")
	}
	e.Tick(at(2, 9, 0))
	d := e.BlockedDomains()
	if !slices.Contains(d, "coupang.com") || slices.Contains(d, "dcinside.com") {
		t.Fatalf("예약 목록(코딩: SNS·영상·쇼핑)으로 차단돼야 함: %v", d)
	}
	v := e.View(at(2, 9, 0))
	if !v.Enforcing || len(v.ActiveScheds) != 1 || !slices.Contains(v.Enforced, int64(3)) || v.FocusOn {
		t.Fatalf("view: %+v", v)
	}
	// 집중 모드를 켜면 두 목록이 합쳐진다
	do(t, e, "toggleFocus", "", at(2, 9, 1))
	if d := e.BlockedDomains(); !slices.Contains(d, "dcinside.com") || !slices.Contains(d, "coupang.com") {
		t.Fatal("집중 모드 목록 + 예약 목록")
	}
	do(t, e, "toggleFocus", "", at(2, 9, 2))
	e.Tick(at(2, 12, 0))
	if e.BlockedDomains() != nil || e.Enforcing() {
		t.Fatal("끝나면 풀림")
	}
	if len(ev) != 2 || ev[0] != "schedStart|오전 수업|12:00" || ev[1] != "schedEnd" {
		t.Fatalf("이벤트 %v", ev)
	}
}

func TestScheduleLockPolicy(t *testing.T) {
	now := at(2, 10, 0)
	e := NewEngine("", now)
	do(t, e, "settings", `{"patch":{"policy":"lock"}}`, now)
	do(t, e, "addSchedule", `{"sched":{"days":[1],"start":"09:00","end":"12:00","presetId":1}}`, now)
	id := e.View(now).Schedules[0].ID
	for _, a := range []struct{ action, payload string }{
		{"removeSchedule", fmt.Sprintf(`{"id":%d}`, id)},
		{"updateSchedule", fmt.Sprintf(`{"id":%d,"sched":{"enabled":false}}`, id)},
		{"updateSchedule", fmt.Sprintf(`{"id":%d,"sched":{"end":"10:30"}}`, id)},
		{"toggleCat", `{"pid":1,"cat":"SNS"}`},
		{"settings", `{"patch":{"policy":"instant"}}`},
	} {
		if err := e.Do(a.action, []byte(a.payload), now); err != errLocked {
			t.Errorf("%s %s: 잠금 중 거부돼야 함, got %v", a.action, a.payload, err)
		}
	}
	if e.QuitAllowed() {
		t.Error("예약 잠금 중 종료 불가")
	}
	// 이름 변경, 다른(쓰이지 않는) 목록 수정, 집중 모드 토글은 된다
	do(t, e, "updateSchedule", fmt.Sprintf(`{"id":%d,"sched":{"name":"오전"}}`, id), now)
	do(t, e, "toggleCat", `{"pid":3,"cat":"SNS"}`, now)
	do(t, e, "toggleFocus", "", now)
	do(t, e, "toggleFocus", "", now)
	if err := e.Do("deletePreset", []byte(`{"pid":1}`), now); err == nil {
		t.Error("예약이 쓰는 목록은 삭제 불가")
	}
	// 시간이 지나면 잠금도 풀린다
	later := at(2, 12, 1)
	e.Tick(later)
	if err := e.Do("removeSchedule", []byte(fmt.Sprintf(`{"id":%d}`, id)), later); err != nil {
		t.Fatal(err)
	}
}

func TestScheduleValidation(t *testing.T) {
	e := NewEngine("", at(2, 8, 0))
	for _, bad := range []string{
		`{"sched":{"start":"9:00","end":"12:00"}}`,
		`{"sched":{"start":"25:00","end":"12:00"}}`,
		`{"sched":{"start":"10:00","end":"10:00"}}`,
	} {
		if err := e.Do("addSchedule", []byte(bad), at(2, 8, 0)); err == nil {
			t.Errorf("거부돼야 함: %s", bad)
		}
	}
	do(t, e, "addSchedule", `{"sched":{"days":[5,1,1,9],"start":"23:00","end":"07:00"}}`, at(2, 8, 0))
	if s := e.View(at(2, 8, 0)).Schedules[0]; !slices.Equal(s.Days, []int{1, 5}) || s.Name != "예약" {
		t.Fatalf("정리 실패: %+v", s)
	}
}

package main

import (
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

// Schedule은 요일·시간대에 맞춰 자동으로 켜지는 차단이다.
type Schedule struct {
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	Days     []int  `json:"days"`  // 0=일 … 6=토
	Start    string `json:"start"` // "HH:MM"
	End      string `json:"end"`   // "HH:MM", Start보다 이르면 다음 날까지
	PresetID int64  `json:"presetId"`
	Enabled  bool   `json:"enabled"`
}

var weekdayKo = []string{"일", "월", "화", "수", "목", "금", "토"}

func parseHM(s string) (int, bool) {
	var h, m int
	if len(s) != 5 || s[2] != ':' {
		return 0, false
	}
	if _, err := fmt.Sscanf(s, "%02d:%02d", &h, &m); err != nil || h > 23 || m > 59 {
		return 0, false
	}
	return h*60 + m, true
}

// activeAt은 now가 이 예약의 시간대 안인지 알려 준다.
// 밤을 넘기는 예약(23:00~07:00)은 시작한 요일에 속한다.
func (s Schedule) activeAt(now time.Time) bool {
	if !s.Enabled {
		return false
	}
	st, ok1 := parseHM(s.Start)
	en, ok2 := parseHM(s.End)
	if !ok1 || !ok2 || st == en {
		return false
	}
	m := now.Hour()*60 + now.Minute()
	wd := int(now.Weekday())
	if st < en {
		return slices.Contains(s.Days, wd) && m >= st && m < en
	}
	return (slices.Contains(s.Days, wd) && m >= st) || (slices.Contains(s.Days, (wd+6)%7) && m < en)
}

// nextStart는 다음 시작 시각을 "오늘 13:00", "내일 09:00", "월 09:00" 꼴로 돌려준다.
func (s Schedule) nextStart(now time.Time) string {
	st, ok := parseHM(s.Start)
	if !ok || !s.Enabled || len(s.Days) == 0 {
		return ""
	}
	m := now.Hour()*60 + now.Minute()
	for d := 0; d <= 7; d++ {
		wd := (int(now.Weekday()) + d) % 7
		if !slices.Contains(s.Days, wd) || (d == 0 && st <= m) {
			continue
		}
		switch d {
		case 0:
			return "오늘 " + s.Start
		case 1:
			return "내일 " + s.Start
		default:
			return weekdayKo[wd] + " " + s.Start
		}
	}
	return ""
}

func (s *Schedule) validate() error {
	st, ok1 := parseHM(s.Start)
	en, ok2 := parseHM(s.End)
	if !ok1 || !ok2 {
		return errors.New("시간을 HH:MM 형식으로 입력하세요.")
	}
	if st == en {
		return errors.New("시작과 끝 시간이 같아요.")
	}
	days := []int{}
	for _, d := range s.Days {
		if d >= 0 && d <= 6 && !slices.Contains(days, d) {
			days = append(days, d)
		}
	}
	slices.Sort(days)
	s.Days = days
	s.Name = strings.TrimSpace(s.Name)
	if s.Name == "" {
		s.Name = "예약"
	}
	return nil
}

// activeSchedules는 지금 켜져 있는 예약들이다.
func (e *Engine) activeSchedules() []Schedule {
	var out []Schedule
	for _, s := range e.st.Schedules {
		if s.activeAt(e.now) && e.preset(s.PresetID) != nil {
			out = append(out, s)
		}
	}
	return out
}

func (e *Engine) schedule(id int64) *Schedule {
	for i := range e.st.Schedules {
		if e.st.Schedules[i].ID == id {
			return &e.st.Schedules[i]
		}
	}
	return nil
}

// scheduleKey는 예약 시작/종료를 알아채려고 지금 켜진 예약 ID를 묶는다.
func (e *Engine) scheduleKey() string {
	var b strings.Builder
	for _, s := range e.activeSchedules() {
		fmt.Fprintf(&b, "%d,", s.ID)
	}
	return b.String()
}

type schedulePatch struct {
	Name     *string `json:"name"`
	Days     *[]int  `json:"days"`
	Start    *string `json:"start"`
	End      *string `json:"end"`
	PresetID *int64  `json:"presetId"`
	Enabled  *bool   `json:"enabled"`
}

// doSchedule은 예약 추가·수정·삭제를 처리한다.
// 지금 적용 중인 예약을 끄거나 바꾸는 것은 '차단 해제'로 보고 잠금 정책을 따른다.
func (e *Engine) doSchedule(action string, p payload) error {
	switch action {
	case "addSchedule":
		s := Schedule{Name: p.Name, Days: []int{1, 2, 3, 4, 5}, Start: "09:00", End: "12:00", PresetID: e.st.ActivePreset, Enabled: true}
		if p.Sched != nil {
			if p.Sched.Days != nil {
				s.Days = *p.Sched.Days
			}
			if p.Sched.Start != nil {
				s.Start = *p.Sched.Start
			}
			if p.Sched.End != nil {
				s.End = *p.Sched.End
			}
			if p.Sched.PresetID != nil && e.preset(*p.Sched.PresetID) != nil {
				s.PresetID = *p.Sched.PresetID
			}
		}
		if err := s.validate(); err != nil {
			return err
		}
		e.st.NextID++
		s.ID = e.st.NextID
		e.st.Schedules = append(e.st.Schedules, s)
	case "updateSchedule":
		s := e.schedule(p.ID)
		if s == nil || p.Sched == nil {
			return nil
		}
		next := *s
		next.Days = append([]int{}, s.Days...)
		q := p.Sched
		if q.Name != nil {
			next.Name = *q.Name
		}
		if q.Days != nil {
			next.Days = *q.Days
		}
		if q.Start != nil {
			next.Start = *q.Start
		}
		if q.End != nil {
			next.End = *q.End
		}
		if q.PresetID != nil {
			if e.preset(*q.PresetID) == nil {
				return nil
			}
			next.PresetID = *q.PresetID
		}
		if q.Enabled != nil {
			next.Enabled = *q.Enabled
		}
		if err := next.validate(); err != nil {
			return err
		}
		// 이름만 바꾸거나 새로 켜는 건 언제든 된다. 적용 중인 예약을 바꾸는 건 해제로 본다.
		onlyHarmless := next.Days != nil && slices.Equal(next.Days, s.Days) && next.Start == s.Start &&
			next.End == s.End && next.PresetID == s.PresetID && (next.Enabled || !s.Enabled)
		if !onlyHarmless && s.activeAt(e.now) && e.lockedSchedule() {
			return errLocked
		}
		*s = next
	case "removeSchedule":
		for i := range e.st.Schedules {
			if e.st.Schedules[i].ID == p.ID {
				if e.st.Schedules[i].activeAt(e.now) && e.lockedSchedule() {
					return errLocked
				}
				e.st.Schedules = append(e.st.Schedules[:i], e.st.Schedules[i+1:]...)
				break
			}
		}
	}
	return nil
}

// ScheduleView는 UI에 보내는 예약 정보(상태 계산 포함)다.
type ScheduleView struct {
	Schedule
	Active bool   `json:"active"`
	Next   string `json:"next"`
}

func (e *Engine) scheduleViews() []ScheduleView {
	out := []ScheduleView{}
	for _, s := range e.st.Schedules {
		v := ScheduleView{Schedule: s, Active: s.activeAt(e.now) && e.preset(s.PresetID) != nil}
		v.Days = append([]int{}, s.Days...)
		if !v.Active {
			v.Next = s.nextStart(e.now)
		}
		out = append(out, v)
	}
	return out
}

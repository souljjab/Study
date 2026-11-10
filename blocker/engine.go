package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// errLocked는 '잠금' 정책으로 해제를 거부했을 때 돌려준다. UI가 잠금 안내를 띄운다.
var errLocked = errors.New("locked")

type Site struct {
	ID      int64  `json:"id"`
	Domain  string `json:"domain"`
	Cat     string `json:"cat"`
	Blocked bool   `json:"blocked"`
}

type Allow struct {
	ID     int64  `json:"id"`
	Domain string `json:"domain"`
}

type Preset struct {
	ID    int64    `json:"id"`
	Name  string   `json:"name"`
	Icon  string   `json:"icon"`
	Cats  []string `json:"cats"`
	Sites []Site   `json:"sites"`
	Allow []Allow  `json:"allow"`
}

type Settings struct {
	FocusMin     int    `json:"focusMin"`
	BreakMin     int    `json:"breakMin"`
	LongBreakMin int    `json:"longBreakMin"`
	AutoLink     bool   `json:"autoLink"`
	BreakUnblock bool   `json:"breakUnblock"`
	Policy       string `json:"policy"` // instant | wait | lock
	WaitSec      int    `json:"waitSec"`
	BlockMsg     string `json:"blockMsg"`
	Launch       bool   `json:"launch"`
	Sound        bool   `json:"sound"`
}

type Session struct {
	Date    string `json:"date"`
	Time    string `json:"time"`
	Mode    string `json:"mode"`
	Dur     int    `json:"dur"`
	Blocked int    `json:"blocked"`
}

type Timer struct {
	Mode           string `json:"mode"` // pomo | free | watch
	FreeMin        int    `json:"freeMin"`
	Phase          string `json:"phase"` // focus | break
	Remaining      int    `json:"remaining"`
	Total          int    `json:"total"`
	Elapsed        int    `json:"elapsed"`
	Running        bool   `json:"running"`
	Session        int    `json:"session"`
	SessionStart   string `json:"sessionStart"`
	SessionBlocked int    `json:"sessionBlocked"`
}

// Persist는 디스크에 저장되는 전체 상태다.
type Persist struct {
	Version      int                       `json:"version"`
	Onboarded    bool                      `json:"onboarded"`
	FocusOn      bool                      `json:"focusOn"`
	Timer        Timer                     `json:"timer"`
	Presets      []Preset                  `json:"presets"`
	ActivePreset int64                     `json:"activePreset"`
	Settings     Settings                  `json:"settings"`
	Sessions     []Session                 `json:"sessions"`
	DayFocus     map[string]int            `json:"dayFocus"`
	Hits         map[string]map[string]int `json:"hits"` // 날짜 → 도메인 → 막은 횟수
	Schedules    []Schedule                `json:"schedules"`
	Unlocks      []Unlock                  `json:"unlocks"`     // 긴급 잠금 해제 기록(지우지 않음)
	UnlockUntil  int64                     `json:"unlockUntil"` // 긴급 해제가 끝나는 시각(유닉스 초)
	NextID       int64                     `json:"nextId"`
	SavedAt      int64                     `json:"savedAt"`
}

func defaultState() Persist {
	p := Persist{
		Version: 1,
		Timer:   Timer{Mode: "pomo", FreeMin: 50, Phase: "focus", Remaining: 1500, Total: 1500, Session: 1},
		Settings: Settings{
			FocusMin: 25, BreakMin: 5, LongBreakMin: 15, AutoLink: true, BreakUnblock: true,
			Policy: "instant", WaitSec: 15, BlockMsg: "지금은 공부할 시간이에요.", Launch: true, Sound: true,
		},
		DayFocus: map[string]int{},
		Hits:     map[string]map[string]int{},
		NextID:   100,
	}
	site := func(d, cat string, blocked bool) Site { p.NextID++; return Site{p.NextID, d, cat, blocked} }
	allow := func(d string) Allow { p.NextID++; return Allow{p.NextID, d} }
	p.Presets = []Preset{
		{ID: 1, Name: "기본", Icon: "ph-books", Cats: []string{"SNS", "영상", "커뮤니티"},
			Sites: []Site{site("namu.wiki", "기타", true), site("everytime.kr", "커뮤니티", true),
				site("comic.naver.com", "기타", true), site("news.naver.com", "뉴스", false)},
			Allow: []Allow{allow("ko.wikipedia.org"), allow("papago.naver.com")}},
		{ID: 2, Name: "시험 기간", Icon: "ph-exam", Cats: append([]string{}, catOrder...),
			Sites: []Site{site("namu.wiki", "기타", true), site("everytime.kr", "커뮤니티", true),
				site("comic.naver.com", "기타", true), site("music.youtube.com", "영상", true)},
			Allow: []Allow{allow("ebsi.co.kr")}},
		{ID: 3, Name: "코딩", Icon: "ph-code", Cats: []string{"SNS", "영상", "쇼핑"},
			Sites: []Site{site("namu.wiki", "기타", true)},
			Allow: []Allow{allow("github.com"), allow("stackoverflow.com"), allow("developer.mozilla.org")}},
	}
	p.ActivePreset = 1
	return p
}

type Engine struct {
	mu       sync.Mutex
	st       Persist
	path     string
	lastTick time.Time
	// OnPhase는 집중/휴식이 바뀌거나 타이머가 끝날 때 불린다(알림음). 잠금 밖에서 호출된다.
	OnPhase  func(kind string)
	events   []string
	now      time.Time // 마지막으로 받은 현재 시각(예약 판단용)
	schedK   string    // 직전 틱에 켜져 있던 예약
	unlockOn bool      // 직전 틱에 긴급 해제 중이었는지
}

func dateKey(t time.Time) string { return t.Format("2006-01-02") }
func hm(t time.Time) string      { return t.Format("15:04") }

func NewEngine(path string, now time.Time) *Engine {
	e := &Engine{path: path, st: defaultState(), lastTick: now, now: now}
	if path != "" {
		if b, err := os.ReadFile(path); err == nil {
			var p Persist
			if json.Unmarshal(b, &p) == nil && len(p.Presets) > 0 {
				if p.DayFocus == nil {
					p.DayFocus = map[string]int{}
				}
				if p.Hits == nil {
					p.Hits = map[string]map[string]int{}
				}
				e.st = p
				// 꺼져 있던 동안 흐른 시간만큼 타이머를 따라잡는다.
				if p.SavedAt > 0 && p.Timer.Running {
					e.lastTick = time.Unix(p.SavedAt, 0)
				}
			}
		}
	}
	e.Tick(now)
	return e
}

func (e *Engine) save(now time.Time) {
	if e.path == "" {
		return
	}
	e.st.SavedAt = now.Unix()
	e.prune(now)
	b, err := json.MarshalIndent(e.st, "", " ")
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(e.path), 0o755)
	tmp := e.path + ".tmp"
	if os.WriteFile(tmp, b, 0o644) == nil {
		_ = os.Rename(tmp, e.path)
	}
}

func (e *Engine) prune(now time.Time) {
	cut := dateKey(now.AddDate(0, 0, -35))
	for k := range e.st.DayFocus {
		if k < cut {
			delete(e.st.DayFocus, k)
		}
	}
	for k := range e.st.Hits {
		if k < cut {
			delete(e.st.Hits, k)
		}
	}
	keep := e.st.Sessions[:0]
	for _, s := range e.st.Sessions {
		if s.Date >= cut {
			keep = append(keep, s)
		}
	}
	e.st.Sessions = keep
}

func (e *Engine) Save(now time.Time) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.save(now)
}

// Tick은 마지막 틱 이후 흐른 초만큼 타이머를 진행한다.
func (e *Engine) Tick(now time.Time) {
	e.mu.Lock()
	n := int(now.Sub(e.lastTick) / time.Second)
	if n > 7*24*3600 {
		e.lastTick = now.Add(-7 * 24 * time.Hour)
		n = 7 * 24 * 3600
	}
	for i := 0; i < n; i++ {
		e.lastTick = e.lastTick.Add(time.Second)
		e.tickOnce(e.lastTick)
	}
	e.now = now
	if u := e.unlocked(); e.unlockOn && !u {
		e.events = append(e.events, "unlockEnd")
		e.unlockOn = false
	} else if u {
		e.unlockOn = true
	}
	if k := e.scheduleKey(); k != e.schedK {
		e.schedEvents(e.schedK, k)
		e.schedK = k
	}
	ev := e.events
	e.events = nil
	if len(ev) > 0 {
		e.save(now)
	}
	cb := e.OnPhase
	e.mu.Unlock()
	if cb != nil {
		for _, k := range ev {
			cb(k)
		}
	}
}

func (e *Engine) record(at time.Time, mode string, dur int) {
	t := &e.st.Timer
	start := t.SessionStart
	if start == "" {
		start = hm(at)
	}
	e.st.Sessions = append(e.st.Sessions, Session{dateKey(at), start, mode, dur, t.SessionBlocked})
	t.SessionStart = ""
	t.SessionBlocked = 0
}

func (e *Engine) tickOnce(at time.Time) {
	t := &e.st.Timer
	s := e.st.Settings
	if !t.Running {
		return
	}
	if t.Mode == "watch" {
		t.Elapsed++
		e.st.DayFocus[dateKey(at)]++
		return
	}
	if t.Phase == "focus" {
		e.st.DayFocus[dateKey(at)]++
	}
	if t.Remaining > 1 {
		t.Remaining--
		return
	}
	if t.Mode == "pomo" {
		if t.Phase == "focus" {
			b := s.BreakMin
			if t.Session%4 == 0 {
				b = s.LongBreakMin
			}
			e.record(at, "뽀모도로", t.Total)
			t.Phase, t.Remaining, t.Total = "break", b*60, b*60
			e.events = append(e.events, "break")
			return
		}
		f := s.FocusMin * 60
		t.Phase, t.Remaining, t.Total = "focus", f, f
		t.Session = t.Session%4 + 1
		t.SessionStart = hm(at)
		e.events = append(e.events, "focus")
		return
	}
	e.record(at, "자유", t.Total)
	t.Running = false
	t.Remaining = 0
	if s.AutoLink {
		e.st.FocusOn = false
	}
	e.events = append(e.events, "done")
}

// focusEnforcing은 집중 모드(타이머/수동)로 차단 중인지다. 뽀모도로 휴식엔 풀릴 수 있다.
func (e *Engine) focusEnforcing() bool {
	t := e.st.Timer
	return e.st.FocusOn && !(t.Phase == "break" && e.st.Settings.BreakUnblock && t.Mode == "pomo")
}

// enforcing은 집중 모드든 예약이든 지금 실제로 막고 있는지다(긴급 해제 중이면 아님).
func (e *Engine) enforcing() bool {
	return !e.unlocked() && (e.focusEnforcing() || len(e.activeSchedules()) > 0)
}

// lockedFocus는 '잠금' 정책에서 타이머가 도는 동안이다.
func (e *Engine) lockedFocus() bool {
	return e.focusEnforcing() && e.st.Settings.Policy == "lock" && e.st.Timer.Running
}

// lockedSchedule은 '잠금' 정책에서 예약 시간대 안인 동안이다.
func (e *Engine) lockedSchedule() bool {
	return e.st.Settings.Policy == "lock" && len(e.activeSchedules()) > 0
}

// locked는 '잠금' 정책이 적용 중이라 차단을 풀 수 없는 상태다.
func (e *Engine) locked() bool { return e.lockedFocus() || e.lockedSchedule() }

// presetEnforced는 이 목록이 지금 차단에 쓰이는지다(집중 모드의 목록이거나 켜진 예약의 목록).
func (e *Engine) presetEnforced(id int64) bool {
	if e.focusEnforcing() && id == e.active().ID {
		return true
	}
	for _, s := range e.activeSchedules() {
		if s.PresetID == id {
			return true
		}
	}
	return false
}

func (e *Engine) schedEvents(prev, cur string) {
	for _, s := range e.activeSchedules() {
		if !strings.Contains(","+prev, fmt.Sprintf(",%d,", s.ID)) {
			e.events = append(e.events, "schedStart|"+s.Name+"|"+s.End)
		}
	}
	if cur == "" && prev != "" {
		e.events = append(e.events, "schedEnd")
	}
}

func (e *Engine) preset(id int64) *Preset {
	for i := range e.st.Presets {
		if e.st.Presets[i].ID == id {
			return &e.st.Presets[i]
		}
	}
	return nil
}

func (e *Engine) active() *Preset {
	if p := e.preset(e.st.ActivePreset); p != nil {
		return p
	}
	return &e.st.Presets[0]
}

// BlockedDomains는 지금 hosts에 써야 할 도메인이다. 차단 중이 아니면 nil.
func (e *Engine) BlockedDomains() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.unlocked() {
		return nil
	}
	set := map[string]bool{}
	if e.focusEnforcing() {
		for _, d := range presetDomains(e.active()) {
			set[d] = true
		}
	}
	for _, s := range e.activeSchedules() {
		for _, d := range presetDomains(e.preset(s.PresetID)) {
			set[d] = true
		}
	}
	if len(set) == 0 {
		return nil
	}
	out := make([]string, 0, len(set))
	for d := range set {
		out = append(out, d)
	}
	sort.Strings(out)
	return out
}

func presetDomains(p *Preset) []string {
	set := map[string]bool{}
	for _, c := range p.Cats {
		if ci := catalog[c]; ci != nil {
			for _, d := range ci.Domains {
				set[d] = true
			}
		}
	}
	for _, s := range p.Sites {
		if s.Blocked {
			set[s.Domain] = true
		}
	}
	for _, a := range p.Allow {
		delete(set, a.Domain)
	}
	out := make([]string, 0, len(set))
	for d := range set {
		out = append(out, d)
	}
	sort.Strings(out)
	return out
}

// RecordHit은 차단된 도메인으로의 접속 시도를 기록한다.
func (e *Engine) RecordHit(domain string, now time.Time) {
	e.mu.Lock()
	defer e.mu.Unlock()
	k := dateKey(now)
	if e.st.Hits[k] == nil {
		e.st.Hits[k] = map[string]int{}
	}
	e.st.Hits[k][domain]++
	if e.st.Timer.Running {
		e.st.Timer.SessionBlocked++
	}
}

func normalizeDomain(raw string) string {
	s := strings.ToLower(strings.TrimSpace(raw))
	s = strings.TrimPrefix(s, "https://")
	s = strings.TrimPrefix(s, "http://")
	s = strings.TrimPrefix(s, "www.")
	s = strings.TrimSuffix(s, "/")
	return s
}

func validHost(d string) bool {
	if !strings.Contains(d, ".") || strings.HasPrefix(d, ".") || strings.HasSuffix(d, ".") {
		return false
	}
	for _, r := range d {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-' || r == '.') {
			return false
		}
	}
	return true
}

type payload struct {
	Mode   string          `json:"mode"`
	Min    int             `json:"min"`
	Pid    int64           `json:"pid"`
	ID     int64           `json:"id"`
	Cat    string          `json:"cat"`
	Cats   []string        `json:"cats"`
	Domain string          `json:"domain"`
	Name   string          `json:"name"`
	On     bool            `json:"on"`
	Start  bool            `json:"start"`
	Patch  json.RawMessage `json:"patch"`
	Sched  *schedulePatch  `json:"sched"`
	Reason string          `json:"reason"`
}

// Do는 UI 동작을 처리한다. 반환 에러 메시지는 UI에 그대로 보인다.
func (e *Engine) Do(action string, raw []byte, now time.Time) error {
	var p payload
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &p); err != nil {
			return err
		}
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.now = now
	err := e.do(action, p, now)
	if err == nil {
		e.save(now)
	}
	return err
}

func (e *Engine) do(action string, p payload, now time.Time) error {
	t := &e.st.Timer
	s := &e.st.Settings
	lengthFor := func(mode string) int {
		if mode == "free" {
			return t.FreeMin * 60
		}
		return s.FocusMin * 60
	}
	switch action {
	case "setMode":
		if t.Running || (p.Mode != "pomo" && p.Mode != "free" && p.Mode != "watch") {
			return nil
		}
		l := lengthFor(p.Mode)
		*t = Timer{Mode: p.Mode, FreeMin: t.FreeMin, Phase: "focus", Remaining: l, Total: l, Session: 1}
	case "freeMin":
		if t.Running || p.Min < 1 || p.Min > 600 {
			return nil
		}
		t.FreeMin, t.Remaining, t.Total = p.Min, p.Min*60, p.Min*60
	case "toggleRun":
		if t.Running {
			t.Running = false
			return nil
		}
		if t.Mode != "watch" && t.Remaining == 0 {
			t.Remaining = t.Total
		}
		t.Running = true
		if s.AutoLink {
			e.st.FocusOn = true
		}
		if t.SessionStart == "" {
			t.SessionStart = hm(now)
		}
	case "reset":
		if s.AutoLink && e.st.FocusOn && e.lockedFocus() {
			return errLocked
		}
		if t.Mode == "watch" {
			if t.Elapsed >= 60 {
				e.record(now, "스톱워치", t.Elapsed)
			}
			t.Running, t.Elapsed, t.SessionStart, t.SessionBlocked = false, 0, "", 0
		} else {
			l := lengthFor(t.Mode)
			t.Running, t.Phase, t.Remaining, t.Total, t.Session, t.SessionStart = false, "focus", l, l, 1, ""
		}
		if s.AutoLink {
			e.st.FocusOn = false
		}
	case "skip":
		if t.Mode != "pomo" {
			return nil
		}
		if t.Phase == "focus" {
			if e.lockedFocus() {
				return errLocked
			}
			b := s.BreakMin
			if t.Session%4 == 0 {
				b = s.LongBreakMin
			}
			t.Phase, t.Remaining, t.Total = "break", b*60, b*60
		} else {
			f := s.FocusMin * 60
			t.Phase, t.Remaining, t.Total = "focus", f, f
			t.Session = t.Session%4 + 1
		}
	case "toggleFocus":
		if !e.st.FocusOn {
			e.st.FocusOn = true
			return nil
		}
		if e.lockedFocus() {
			return errLocked
		}
		e.st.FocusOn = false
		if s.AutoLink {
			t.Running = false
		}
	case "setActive":
		if e.preset(p.Pid) == nil {
			return nil
		}
		if p.Pid != e.st.ActivePreset && e.lockedFocus() {
			return errLocked
		}
		e.st.ActivePreset = p.Pid
	case "toggleCat":
		pr := e.preset(p.Pid)
		if pr == nil || catalog[p.Cat] == nil {
			return nil
		}
		for i, c := range pr.Cats {
			if c == p.Cat {
				if e.presetEnforced(pr.ID) && e.locked() {
					return errLocked
				}
				pr.Cats = append(pr.Cats[:i], pr.Cats[i+1:]...)
				return nil
			}
		}
		pr.Cats = append(pr.Cats, p.Cat)
	case "toggleSite", "removeSite":
		pr := e.preset(p.Pid)
		if pr == nil {
			return nil
		}
		for i := range pr.Sites {
			if pr.Sites[i].ID != p.ID {
				continue
			}
			if pr.Sites[i].Blocked && e.presetEnforced(pr.ID) && e.locked() {
				return errLocked
			}
			if action == "removeSite" {
				pr.Sites = append(pr.Sites[:i], pr.Sites[i+1:]...)
			} else {
				pr.Sites[i].Blocked = !pr.Sites[i].Blocked
			}
			return nil
		}
	case "setAll":
		pr := e.preset(p.Pid)
		if pr == nil {
			return nil
		}
		if !p.On && e.presetEnforced(pr.ID) && e.locked() {
			return errLocked
		}
		if p.On {
			pr.Cats = append([]string{}, catOrder...)
		} else {
			pr.Cats = []string{}
		}
		for i := range pr.Sites {
			pr.Sites[i].Blocked = p.On
		}
	case "addSite":
		pr := e.preset(p.Pid)
		if pr == nil {
			return nil
		}
		d := strings.SplitN(normalizeDomain(p.Domain), "/", 2)[0]
		if !validHost(d) {
			return errors.New("올바른 도메인을 입력하세요. 예: namu.wiki")
		}
		for _, x := range pr.Sites {
			if x.Domain == d {
				return fmt.Errorf("%s는 이미 목록에 있어요.", d)
			}
		}
		cat := p.Cat
		if cat == "" {
			cat = "기타"
		}
		e.st.NextID++
		pr.Sites = append([]Site{{e.st.NextID, d, cat, true}}, pr.Sites...)
	case "addAllow":
		pid := p.Pid
		if pid == 0 {
			pid = e.st.ActivePreset
		}
		pr := e.preset(pid)
		if pr == nil {
			return nil
		}
		d := normalizeDomain(p.Domain)
		if strings.Contains(d, "/") {
			// hosts 방식은 도메인 단위로만 막고 풀 수 있다.
			return errors.New("경로 단위 허용은 지원하지 않아요. 도메인만 입력하세요. 예: ebsi.co.kr")
		}
		if !validHost(d) {
			return errors.New("올바른 주소를 입력하세요. 예: ko.wikipedia.org")
		}
		if e.presetEnforced(pr.ID) && e.locked() {
			return errLocked
		}
		for _, x := range pr.Allow {
			if x.Domain == d {
				return fmt.Errorf("%s는 이미 허용 목록에 있어요.", d)
			}
		}
		e.st.NextID++
		pr.Allow = append([]Allow{{e.st.NextID, d}}, pr.Allow...)
	case "removeAllow":
		pr := e.preset(p.Pid)
		if pr == nil {
			return nil
		}
		for i := range pr.Allow {
			if pr.Allow[i].ID == p.ID {
				pr.Allow = append(pr.Allow[:i], pr.Allow[i+1:]...)
				return nil
			}
		}
	case "addPreset":
		e.st.NextID++
		e.st.Presets = append(e.st.Presets, Preset{
			ID: e.st.NextID, Name: fmt.Sprintf("새 목록 %d", len(e.st.Presets)+1), Icon: "ph-list-bullets",
			Cats: []string{}, Sites: []Site{}, Allow: []Allow{},
		})
	case "renamePreset":
		if pr := e.preset(p.Pid); pr != nil && strings.TrimSpace(p.Name) != "" {
			pr.Name = strings.TrimSpace(p.Name)
		}
	case "deletePreset":
		if len(e.st.Presets) <= 1 || p.Pid == e.st.ActivePreset {
			return errors.New("사용 중인 목록은 지울 수 없어요.")
		}
		for _, sc := range e.st.Schedules {
			if sc.PresetID == p.Pid {
				return errors.New("예약에서 쓰는 목록은 지울 수 없어요.")
			}
		}
		for i := range e.st.Presets {
			if e.st.Presets[i].ID == p.Pid {
				e.st.Presets = append(e.st.Presets[:i], e.st.Presets[i+1:]...)
				break
			}
		}
	case "settings":
		return e.applySettings(p.Patch)
	case "addSchedule", "updateSchedule", "removeSchedule":
		return e.doSchedule(action, p)
	case "finishOnboarding":
		e.st.Onboarded = true
		if p.Cats == nil {
			return nil
		}
		pr := &e.st.Presets[0]
		cats := []string{}
		for _, c := range p.Cats {
			if catalog[c] != nil {
				cats = append(cats, c)
			}
		}
		pr.Cats = cats
		e.st.ActivePreset = pr.ID
		mode := p.Mode
		if mode != "free" && mode != "watch" {
			mode = "pomo"
		}
		l := lengthFor(mode)
		*t = Timer{Mode: mode, FreeMin: t.FreeMin, Phase: "focus", Remaining: l, Total: l, Session: 1}
		if p.Start {
			t.Running = true
			e.st.FocusOn = true
			t.SessionStart = hm(now)
		}
	case "emergencyUnlock", "endUnlock":
		return e.doUnlock(action, p)
	case "skipOnboarding":
		e.st.Onboarded = true
	default:
		return fmt.Errorf("알 수 없는 동작: %s", action)
	}
	return nil
}

func (e *Engine) applySettings(raw json.RawMessage) error {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		return err
	}
	s := &e.st.Settings
	t := &e.st.Timer
	clamp := func(v, lo, hi int) int { return max(lo, min(hi, v)) }
	for k, v := range m {
		var i int
		var b bool
		var str string
		switch k {
		case "focusMin", "breakMin", "longBreakMin", "waitSec":
			if json.Unmarshal(v, &i) != nil {
				continue
			}
		case "autoLink", "breakUnblock", "launch", "sound":
			if json.Unmarshal(v, &b) != nil {
				continue
			}
		case "policy", "blockMsg":
			if json.Unmarshal(v, &str) != nil {
				continue
			}
		}
		switch k {
		case "focusMin":
			s.FocusMin = clamp(i, 5, 90)
			if t.Mode == "pomo" && t.Phase == "focus" && !t.Running && t.Remaining == t.Total {
				t.Remaining, t.Total = s.FocusMin*60, s.FocusMin*60
			}
		case "breakMin":
			s.BreakMin = clamp(i, 1, 30)
		case "longBreakMin":
			s.LongBreakMin = clamp(i, 5, 60)
		case "waitSec":
			s.WaitSec = clamp(i, 5, 300)
		case "autoLink":
			s.AutoLink = b
		case "breakUnblock":
			if b && e.lockedFocus() {
				return errLocked
			}
			s.BreakUnblock = b
		case "policy":
			if str != "instant" && str != "wait" && str != "lock" {
				continue
			}
			// 잠금 중에 정책을 느슨하게 바꿔 빠져나가는 것을 막는다.
			if str != "lock" && e.locked() {
				return errLocked
			}
			s.Policy = str
		case "blockMsg":
			if strings.TrimSpace(str) != "" {
				s.BlockMsg = str
			}
		case "launch":
			s.Launch = b
		case "sound":
			s.Sound = b
		}
	}
	return nil
}

type topItem struct {
	Domain string `json:"domain"`
	Count  int    `json:"count"`
}

// View는 UI에 보내는 상태 스냅숏이다.
type View struct {
	Onboarded    bool           `json:"onboarded"`
	FocusOn      bool           `json:"focusOn"`
	Enforcing    bool           `json:"enforcing"`
	Locked       bool           `json:"locked"`
	LockReason   string         `json:"lockReason"` // timer | schedule
	LockUntil    string         `json:"lockUntil"`
	Schedules    []ScheduleView `json:"schedules"`
	ActiveScheds []int64        `json:"activeSchedules"`
	Enforced     []int64        `json:"enforcedPresets"`
	UnlockUntil  int64          `json:"unlockUntil"` // 밀리초, 긴급 해제 중이 아니면 0
	Unlocks      []UnlockView   `json:"unlocks"`
	Unlocks7d    int            `json:"unlocks7d"`
	NextUnlockAt int64          `json:"nextUnlockAt"` // 밀리초, 지금 가능하면 0
	Timer        Timer          `json:"timer"`
	Presets      []Preset       `json:"presets"`
	ActivePreset int64          `json:"activePreset"`
	Settings     Settings       `json:"settings"`
	Cats         []*catInfo     `json:"cats"`
	Week         []int          `json:"week"` // 최근 7일 집중 분, 마지막이 오늘
	TodayFocus   int            `json:"todayFocus"`
	Sessions     []Session      `json:"sessions"` // 오늘
	TodayHits    int            `json:"todayHits"`
	Top          []topItem      `json:"top"`
	Now          int64          `json:"now"`
}

func (e *Engine) View(now time.Time) View {
	e.mu.Lock()
	defer e.mu.Unlock()
	if now.After(e.now) {
		e.now = now
	}
	today := dateKey(now)
	v := View{
		Onboarded: e.st.Onboarded, FocusOn: e.st.FocusOn, Enforcing: e.enforcing(), Locked: e.locked(),
		Timer: e.st.Timer, ActivePreset: e.active().ID, Settings: e.st.Settings, Cats: catList(),
		TodayFocus: e.st.DayFocus[today], Now: now.UnixMilli(), Sessions: []Session{}, Top: []topItem{},
		Schedules: e.scheduleViews(), ActiveScheds: []int64{}, Enforced: []int64{},
		Unlocks: e.unlockViews(), Unlocks7d: e.unlockCount7d(),
	}
	if e.unlocked() {
		v.UnlockUntil = e.st.UnlockUntil * 1000
	}
	if next := e.nextUnlockAt(); next > now.Unix() {
		v.NextUnlockAt = next * 1000
	}
	if e.lockedFocus() {
		v.LockReason = "timer"
	} else if e.lockedSchedule() {
		v.LockReason = "schedule"
	}
	for _, sc := range e.activeSchedules() {
		v.ActiveScheds = append(v.ActiveScheds, sc.ID)
		if v.LockUntil == "" || sc.End > v.LockUntil {
			v.LockUntil = sc.End
		}
	}
	for _, p := range e.st.Presets {
		if e.presetEnforced(p.ID) {
			v.Enforced = append(v.Enforced, p.ID)
		}
	}
	b, _ := json.Marshal(e.st.Presets)
	_ = json.Unmarshal(b, &v.Presets)
	for i := 6; i >= 0; i-- {
		v.Week = append(v.Week, e.st.DayFocus[dateKey(now.AddDate(0, 0, -i))]/60)
	}
	for _, s := range e.st.Sessions {
		if s.Date == today {
			v.Sessions = append(v.Sessions, s)
		}
	}
	agg := map[string]int{}
	for i := 0; i < 7; i++ {
		for d, n := range e.st.Hits[dateKey(now.AddDate(0, 0, -i))] {
			agg[d] += n
		}
	}
	for _, n := range e.st.Hits[today] {
		v.TodayHits += n
	}
	for d, n := range agg {
		v.Top = append(v.Top, topItem{d, n})
	}
	sort.Slice(v.Top, func(i, j int) bool {
		if v.Top[i].Count != v.Top[j].Count {
			return v.Top[i].Count > v.Top[j].Count
		}
		return v.Top[i].Domain < v.Top[j].Domain
	})
	if len(v.Top) > 5 {
		v.Top = v.Top[:5]
	}
	return v
}

// QuitAllowed는 프로그램을 끝내도 되는지 알려 준다(잠금 중엔 불가).
func (e *Engine) QuitAllowed() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return !e.locked()
}

// Enforcing은 현재 차단이 걸려 있는지 알려 준다.
func (e *Engine) Enforcing() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.enforcing()
}

// LaunchSetting은 '컴퓨터 시작 시 실행' 값.
func (e *Engine) LaunchSetting() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.st.Settings.Launch
}

func (e *Engine) SoundOn() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.st.Settings.Sound
}

func (e *Engine) Onboarded() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.st.Onboarded
}

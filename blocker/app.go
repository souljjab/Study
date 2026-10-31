package main

import (
	"encoding/json"
	"html"
	"strings"
	"sync"
	"time"
)

// App은 엔진·hosts·접속 감지를 묶고 UI와 주고받는다. 창 종류(WebView2, 개발 서버)와 무관하다.
type App struct {
	eng *Engine

	mu         sync.Mutex
	appliedKey string
	hostsErr   string
	attemptsOn bool
	hitDeb     *debouncer // 같은 사이트의 연속 연결을 한 번으로
	popupDeb   *debouncer // 차단 화면을 너무 자주 띄우지 않게
	lastSave   time.Time
	lastLaunch *bool
	blockedSet map[string]bool

	// 창 쪽에서 채운다.
	OnEvent  func(ev map[string]any) // UI로 보낼 이벤트(차단 화면, 종료 요청)
	OnLaunch func(on bool) error     // '컴퓨터 시작 시 실행' 반영
	OnHide   func()
	OnQuit   func()
	OnPopup  func() // 차단 화면을 띄울 때 창을 앞으로
}

func NewApp(eng *Engine) *App {
	return &App{eng: eng, hitDeb: newDebouncer(5 * time.Second), popupDeb: newDebouncer(60 * time.Second)}
}

// Sync는 엔진 상태대로 hosts를 맞춘다. 누가 hosts를 고쳐도 다시 쓴다.
func (a *App) Sync() {
	doms := a.eng.BlockedDomains()
	key := strings.Join(doms, ",")
	a.mu.Lock()
	defer a.mu.Unlock()
	set := make(map[string]bool, len(doms))
	for _, d := range doms {
		set[d] = true
	}
	a.blockedSet = set
	if key == a.appliedKey && hostsBlockIntact(doms) {
		return
	}
	if err := applyHosts(doms); err != nil {
		a.hostsErr = err.Error()
		return
	}
	a.hostsErr = ""
	a.appliedKey = key
}

// ClearHosts는 종료할 때 차단을 모두 지운다.
func (a *App) ClearHosts() {
	a.mu.Lock()
	defer a.mu.Unlock()
	_ = applyHosts(nil)
	a.appliedKey = ""
}

func (a *App) SetAttemptsOn(on bool) {
	a.mu.Lock()
	a.attemptsOn = on
	a.mu.Unlock()
}

// HandleHit은 127.0.0.1로 돌아온 접속의 사이트 이름을 받는다.
func (a *App) HandleHit(host string) {
	a.mu.Lock()
	match := matchBlocked(host, a.blockedSet)
	a.mu.Unlock()
	now := time.Now()
	if match == "" || !a.hitDeb.Allow(match, now) {
		return
	}
	a.eng.RecordHit(match, now)
	if a.popupDeb.Allow(match, now) && a.OnEvent != nil {
		if a.OnPopup != nil {
			a.OnPopup()
		}
		a.OnEvent(map[string]any{"type": "blocked", "domain": match})
	}
}

// BlockPageHTML은 http:// 로 들어온 접속에 보여 줄 간단한 차단 페이지다.
func (a *App) BlockPageHTML(host string) string {
	msg := html.EscapeString(a.eng.View(time.Now()).Settings.BlockMsg)
	return `<!doctype html><meta charset="utf-8"><title>차단됨</title>` +
		`<body style="margin:0;min-height:100vh;display:flex;align-items:center;padding:0 12vw;` +
		`background:radial-gradient(90% 70% at 15% 20%,#2b2741,#161826 70%);color:#e9e9ed;font-family:'Malgun Gothic',system-ui,sans-serif">` +
		`<div><div style="font-size:12px;letter-spacing:.08em;color:#b5abfc">` + html.EscapeString(host) + ` 차단됨</div>` +
		`<h1 style="font-weight:500;font-size:40px;margin:12px 0">` + msg + `</h1></div></body>`
}

type callResult struct {
	State json.RawMessage `json:"state"`
	Error string          `json:"error,omitempty"`
}

// Call은 UI의 동작 요청을 처리하고 새 상태를 돌려준다.
func (a *App) Call(action string, payload []byte) callResult {
	var err error
	switch action {
	case "state":
	case "hideWindow":
		if a.OnHide != nil {
			a.OnHide()
		}
	case "quit":
		if !a.eng.QuitAllowed() {
			err = errLocked
		} else if a.OnQuit != nil {
			go a.OnQuit()
		}
	default:
		err = a.eng.Do(action, payload, time.Now())
		a.Sync()
		a.syncLaunch()
	}
	r := callResult{State: a.StateJSON()}
	if err != nil {
		r.Error = err.Error()
	}
	return r
}

func (a *App) syncLaunch() {
	on := a.eng.LaunchSetting()
	a.mu.Lock()
	changed := a.lastLaunch == nil || *a.lastLaunch != on
	a.lastLaunch = &on
	a.mu.Unlock()
	if changed && a.OnLaunch != nil && a.eng.Onboarded() {
		_ = a.OnLaunch(on)
	}
}

// StateJSON은 UI에 보낼 상태다(엔진 상태 + 창 쪽 정보).
func (a *App) StateJSON() json.RawMessage {
	v := a.eng.View(time.Now())
	a.mu.Lock()
	extra := struct {
		View
		HostsError string `json:"hostsError"`
		AttemptsOn bool   `json:"attemptsOn"`
	}{v, a.hostsErr, a.attemptsOn}
	a.mu.Unlock()
	b, _ := json.Marshal(extra)
	return b
}

// Step은 1초마다 불린다: 타이머 진행, hosts 동기화, 주기적 저장.
func (a *App) Step(now time.Time) {
	a.eng.Tick(now)
	a.Sync()
	if now.Sub(a.lastSave) > 30*time.Second {
		a.eng.Save(now)
		a.lastSave = now
	}
}

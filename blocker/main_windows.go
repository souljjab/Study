//go:build windows

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync/atomic"
	"time"

	webview2 "github.com/jchv/go-webview2"
)

const windowTitle = "몰입"

// 창·트레이·메시지 루프는 모두 메인 OS 스레드에서 돌아야 한다.
func init() { runtime.LockOSThread() }

func main() {
	startHidden := slices.Contains(os.Args[1:], "--tray")
	afterUpdate := slices.Contains(os.Args[1:], "--after-update")
	if afterUpdate {
		// 새 버전으로 바뀐 직후: 옛 프로그램이 끝나기를 기다린다.
		if !waitSingleInstance(30 * time.Second) {
			return
		}
		go cleanupOldExe()
	} else if !singleInstance() {
		// 이미 떠 있으면 그 창을 앞으로 가져온다.
		if h := findMainWindow(); h != 0 {
			bringToFront(h)
		}
		return
	}
	dataDir := filepath.Join(os.Getenv("APPDATA"), "Mollip")
	eng := NewEngine(filepath.Join(dataDir, "state.json"), time.Now())
	app := NewApp(eng)

	// hosts로 127.0.0.1에 돌아온 접속을 받아 '막은 접속 시도'를 센다.
	hits := StartHitListener([]string{"127.0.0.1:443", "[::1]:443"}, []string{"127.0.0.1:80", "[::1]:80"},
		app.HandleHit, app.BlockPageHTML)
	app.SetAttemptsOn(hits.Count() > 0)
	app.Sync()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		messageBox("화면을 띄우지 못했어요: "+err.Error(), windowTitle, 0x10)
		return
	}
	serveUI(ln, app, false)

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  filepath.Join(dataDir, "webview"),
		WindowOptions: webview2.WindowOptions{
			Title: windowTitle, Width: 1200, Height: 820, IconId: 1, Center: true,
		},
	})
	if w == nil {
		if messageBox("이 프로그램은 Microsoft Edge WebView2 런타임이 필요해요.\n설치 페이지를 열까요?", windowTitle, 0x4|0x30) == 6 {
			openURL("https://go.microsoft.com/fwlink/p/?LinkId=2124703")
		}
		app.ClearHosts()
		return
	}
	hwnd := uintptr(w.Window())
	if startHidden {
		hideWindow(hwnd)
	}

	tray := NewTray(windowTitle)
	var toldTray atomic.Bool
	interceptClose(hwnd, func() {
		hideWindow(hwnd)
		if !toldTray.Swap(true) {
			tray.Notify(windowTitle, "트레이에서 계속 실행돼요. 아이콘을 누르면 다시 열려요.")
		}
	})
	eval := func(js string) { w.Dispatch(func() { w.Eval(js) }) }
	sendEvent := func(ev map[string]any) {
		b, _ := json.Marshal(ev)
		eval("window.__event && window.__event(" + string(b) + ")")
	}
	tray.OnOpen = func() { bringToFront(hwnd) }
	tray.OnQuit = func() {
		// 정책(대기/잠금)을 지키도록 종료 확인은 화면에서 한다.
		bringToFront(hwnd)
		sendEvent(map[string]any{"type": "quitRequest"})
	}
	app.OnEvent = sendEvent
	app.OnPopup = func() { w.Dispatch(func() { bringToFront(hwnd) }) }
	app.OnHide = func() { w.Dispatch(func() { hideWindow(hwnd) }) }
	app.OnQuit = func() { w.Dispatch(w.Terminate) }
	app.OnLaunch = setAutostart

	// 자동 업데이트: 받아 두고, 설정에서 누르거나(또는 트레이에 있고 타이머가 멈췄을 때) 바꾼다.
	updater := NewUpdater(filepath.Join(dataDir, "update"))
	app.Updater = updater
	var updating atomic.Bool
	app.OnApplyUpdate = func() error {
		path := updater.ReadyPath()
		if path == "" || updating.Load() {
			return nil
		}
		args := []string{"--after-update"}
		if !isWindowVisible(hwnd) {
			args = append(args, "--tray")
		}
		eng.Save(time.Now())
		if err := installUpdate(path, args...); err != nil {
			return err
		}
		// 차단(hosts)은 그대로 둔 채 끝낸다. 새 버전이 바로 이어받는다.
		updating.Store(true)
		w.Dispatch(w.Terminate)
		return nil
	}
	if afterUpdate {
		w.Dispatch(func() { tray.Notify(windowTitle, "새 버전 "+appVersion+"으로 업데이트했어요.") })
	}
	app.syncLaunch()
	eng.OnPhase = func(kind string) {
		if eng.SoundOn() {
			beep()
		}
		msg := map[string]string{"break": "집중 끝! 잠깐 쉬어요.", "focus": "휴식 끝. 다시 집중할 시간이에요.",
			"done": "집중 시간을 마쳤어요. 수고했어요.", "schedEnd": "예약 차단이 끝났어요.",
			"unlockEnd": "긴급 해제 시간이 끝나 다시 차단해요."}[kind]
		if parts := strings.Split(kind, "|"); parts[0] == "schedStart" && len(parts) == 3 {
			msg = fmt.Sprintf("예약 차단 시작: %s (%s까지)", parts[1], parts[2])
		}
		w.Dispatch(func() { tray.Notify(windowTitle, msg) })
	}

	_ = w.Bind("api", func(action, payload string) callResult {
		return app.Call(action, []byte(payload))
	})
	w.Navigate(fmt.Sprintf("http://%s/", ln.Addr().String()))

	go func() {
		started := time.Now()
		notified, tried := "", ""
		for now := range time.Tick(time.Second) {
			app.Step(now)
			if s := eng.View(now).Settings; !s.ManualUpdate {
				// 켜고 1분 뒤 처음 확인하고, 그 뒤로 6시간마다 확인한다.
				if now.Sub(started) > time.Minute && now.Sub(updater.LastCheck()) > 6*time.Hour {
					go updater.Check(context.Background())
				}
				// 창이 숨겨져 있고 타이머가 멈춰 있을 때만 알아서 바꾼다(공부를 방해하지 않게).
				if p := updater.ReadyPath(); p != "" && p != tried && !isWindowVisible(hwnd) && !eng.View(now).Timer.Running {
					tried = p // 실패해도 같은 파일로 매초 다시 시도하지 않는다
					_ = app.OnApplyUpdate()
				}
			}
			if uv := updater.View(); uv.State == "ready" && uv.Latest != notified {
				notified = uv.Latest
				w.Dispatch(func() {
					tray.Notify(windowTitle, "새 버전 "+uv.Latest+"을 받았어요. 설정에서 업데이트할 수 있어요.")
				})
			}
			eval("window.__push && window.__push(" + string(app.StateJSON()) + ")")
			v := eng.View(now)
			tip := windowTitle + " · 집중 모드 꺼짐"
			if v.Enforcing {
				tip = windowTitle + " · 차단 중"
			} else if v.FocusOn {
				tip = windowTitle + " · 휴식 중"
			}
			if v.Timer.Running {
				left := v.Timer.Remaining
				if v.Timer.Mode == "watch" {
					left = v.Timer.Elapsed
				}
				tip += fmt.Sprintf(" %02d:%02d", left/60, left%60)
			}
			w.Dispatch(func() { tray.SetTip(tip) })
		}
	}()

	w.Run()

	tray.Remove()
	hits.Close()
	if !updating.Load() {
		app.ClearHosts()
	}
	eng.Save(time.Now())
}

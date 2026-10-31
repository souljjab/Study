package main

import (
	"embed"
	"encoding/json"
	"io"
	"io/fs"
	"net"
	"net/http"
	"sync"
)

//go:embed ui
var uiFiles embed.FS

func uiFS() fs.FS {
	sub, _ := fs.Sub(uiFiles, "ui")
	return sub
}

// serveUI는 내장 UI 파일과 (devAPI가 true면) HTTP API를 제공한다.
// Windows 창은 정적 파일만 쓰고, API는 WebView2 바인딩으로 부른다.
func serveUI(ln net.Listener, a *App, devAPI bool) {
	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(uiFS())))
	if devAPI {
		var mu sync.Mutex
		var events []map[string]any
		a.OnEvent = func(ev map[string]any) {
			mu.Lock()
			events = append(events, ev)
			mu.Unlock()
		}
		mux.HandleFunc("/api", func(w http.ResponseWriter, r *http.Request) {
			var req struct {
				Action  string          `json:"action"`
				Payload json.RawMessage `json:"payload"`
			}
			b, _ := io.ReadAll(r.Body)
			if json.Unmarshal(b, &req) != nil {
				http.Error(w, "bad request", 400)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(a.Call(req.Action, req.Payload))
		})
		mux.HandleFunc("/api/poll", func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			ev := events
			events = nil
			mu.Unlock()
			if ev == nil {
				ev = []map[string]any{}
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"state": a.StateJSON(), "events": ev})
		})
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 다른 웹페이지가 이 로컬 서버를 부르지 못하게 Host를 확인한다.
		if h, _, _ := net.SplitHostPort(r.Host); h != "127.0.0.1" && h != "localhost" {
			http.Error(w, "forbidden", 403)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		mux.ServeHTTP(w, r)
	})}
	go srv.Serve(ln)
}

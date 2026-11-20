//go:build !windows

package main

import (
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"
)

// Windows가 아닌 곳에서는 UI 개발·확인용 서버로 돈다.
// 예: STUDY_HOSTS=/tmp/hosts go run . -addr 127.0.0.1:8765 -demo
func main() {
	addr := flag.String("addr", "127.0.0.1:8765", "listen address")
	state := flag.String("state", filepath.Join(os.TempDir(), "mollip-dev.json"), "state file")
	demo := flag.Bool("demo", false, "seed demo data")
	hitPorts := flag.Bool("hits", false, "listen for blocked hits on 127.0.0.1:8443/8080")
	updateURL := flag.String("update-url", "", "update manifest URL (for testing the updater UI)")
	flag.Parse()
	if *demo {
		os.Remove(*state)
	}
	eng := NewEngine(*state, time.Now())
	if *demo {
		seedDemo(eng, time.Now())
	}
	a := NewApp(eng)
	if *updateURL != "" {
		a.Updater = NewUpdater(filepath.Join(os.TempDir(), "mollip-dev-update"))
		a.Updater.ManifestURL = *updateURL
		a.OnApplyUpdate = func() error {
			return fmt.Errorf("개발 서버에서는 설치하지 않아요(받아 둔 파일: %s)", a.Updater.ReadyPath())
		}
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	serveUI(ln, a, true)
	if *hitPorts {
		h := StartHitListener([]string{"127.0.0.1:8443"}, []string{"127.0.0.1:8080"}, a.HandleHit, a.BlockPageHTML)
		a.SetAttemptsOn(h.Count() > 0)
	}
	a.Sync()
	fmt.Println("UI: http://" + *addr + "/")
	for now := range time.Tick(time.Second) {
		a.Step(now)
	}
}

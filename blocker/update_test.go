package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestNewerVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"2.1.0", "2.0.0", true}, {"2.10.0", "2.9.9", true}, {"2.1", "2.1.0", false},
		{"2.1.0", "2.1.0", false}, {"2.0.9", "2.1.0", false}, {"3", "2.99.99", true},
	}
	for _, c := range cases {
		if got := newerVersion(c.a, c.b); got != c.want {
			t.Errorf("newer(%s,%s)=%v", c.a, c.b, got)
		}
	}
}

func TestAllowedUpdateURL(t *testing.T) {
	for raw, want := range map[string]bool{
		"https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/StudyBlocker.exe": true,
		"http://raw.githubusercontent.com/x":                                          false,
		"https://evil.example.com/StudyBlocker.exe":                                   false,
		"https://raw.githubusercontent.com.evil.com/x":                                false,
	} {
		if got := allowedUpdateURL(raw); got != want {
			t.Errorf("%s → %v", raw, got)
		}
	}
}

// 업데이트 서버 흉내: update.json과 새 exe를 준다.
func fakeServer(t *testing.T, version string, body []byte, sha string) *httptest.Server {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/update.json":
			fmt.Fprintf(w, `{"windows":{"version":%q,"url":%q,"sha256":%q,"size":%d,"notes":"새 기능"}}`,
				version, srv.URL+"/StudyBlocker.exe", sha, len(body))
		case "/StudyBlocker.exe":
			w.Write(body)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func sum(b []byte) string { s := sha256.Sum256(b); return hex.EncodeToString(s[:]) }

func TestUpdaterDownloadsAndVerifies(t *testing.T) {
	body := []byte(strings.Repeat("MZ new build ", 1000))
	srv := fakeServer(t, "2.2.0", body, sum(body))
	u := NewUpdater(t.TempDir())
	u.ManifestURL, u.Current = srv.URL+"/update.json", "2.1.0"
	if err := u.Check(context.Background()); err != nil {
		t.Fatal(err)
	}
	v := u.View()
	if v.State != "ready" || v.Latest != "2.2.0" || v.Notes != "새 기능" {
		t.Fatalf("view %+v", v)
	}
	got, _ := os.ReadFile(u.ReadyPath())
	if string(got) != string(body) {
		t.Fatal("내려받은 파일 내용")
	}
	// 같은 버전은 다시 받지 않는다
	if err := u.Check(context.Background()); err != nil || u.View().State != "ready" {
		t.Fatalf("재확인: %v %+v", err, u.View())
	}
}

func TestUpdaterRejectsBadHash(t *testing.T) {
	body := []byte("tampered")
	srv := fakeServer(t, "2.2.0", body, strings.Repeat("0", 64))
	dir := t.TempDir()
	u := NewUpdater(dir)
	u.ManifestURL, u.Current = srv.URL+"/update.json", "2.1.0"
	if err := u.Check(context.Background()); err == nil || u.View().State != "error" || u.ReadyPath() != "" {
		t.Fatalf("지문이 틀리면 거부: %v %+v", err, u.View())
	}
	if ents, _ := os.ReadDir(dir); len(ents) != 0 {
		t.Fatalf("받다 만 파일이 남음: %v", ents)
	}
}

func TestUpdaterLatest(t *testing.T) {
	srv := fakeServer(t, "2.1.0", []byte("x"), sum([]byte("x")))
	u := NewUpdater(t.TempDir())
	u.ManifestURL, u.Current = srv.URL+"/update.json", "2.1.0"
	if err := u.Check(context.Background()); err != nil || u.View().State != "latest" {
		t.Fatalf("최신: %v %+v", err, u.View())
	}
}

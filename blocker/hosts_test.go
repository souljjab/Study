package main

import (
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestHostsApplyAndRestore(t *testing.T) {
	p := filepath.Join(t.TempDir(), "hosts")
	orig := "127.0.0.1 localhost\n# mine\n"
	os.WriteFile(p, []byte(orig), 0o644)
	t.Setenv("STUDY_HOSTS", p)
	doms := []string{"youtube.com", "namu.wiki"}
	if err := applyHosts(doms); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(p)
	for _, want := range []string{"127.0.0.1 www.youtube.com", "::1 m.namu.wiki", "# mine"} {
		if !strings.Contains(string(b), want) {
			t.Errorf("hosts에 %q 없음:\n%s", want, b)
		}
	}
	if !hostsBlockIntact(doms) || hostsBlockIntact([]string{"x.com"}) {
		t.Error("intact 검사 오류")
	}
	applyHosts(doms) // 두 번 써도 구간은 하나
	b, _ = os.ReadFile(p)
	if strings.Count(string(b), beginMark) != 1 {
		t.Error("구간 중복")
	}
	applyHosts(nil)
	b, _ = os.ReadFile(p)
	if string(b) != orig {
		t.Errorf("원래 내용 복원 실패: %q", b)
	}
}

func TestMatchBlocked(t *testing.T) {
	set := map[string]bool{"youtube.com": true, "x.com": true, "news.naver.com": true}
	cases := map[string]string{
		"www.youtube.com": "youtube.com", "M.YouTube.com.": "youtube.com", "x.com": "x.com",
		"netflix.com": "", "naver.com": "", "n.news.naver.com": "news.naver.com",
	}
	for in, want := range cases {
		if got := matchBlocked(in, set); got != want {
			t.Errorf("%s → %q, want %q", in, got, want)
		}
	}
}

func TestParseSNIFromRealClientHello(t *testing.T) {
	c1, c2 := net.Pipe()
	go func() {
		tls.Client(c1, &tls.Config{ServerName: "www.youtube.com"}).Handshake()
	}()
	host, err := parseSNI(c2)
	c2.Close()
	c1.Close()
	if err != nil || host != "www.youtube.com" {
		t.Fatalf("got %q %v", host, err)
	}
}

func TestHitListener(t *testing.T) {
	hits := make(chan string, 4)
	h := StartHitListener([]string{"127.0.0.1:0"}, []string{"127.0.0.1:0"},
		func(s string) { hits <- s }, func(host string) string { return "<p>blocked " + host + "</p>" })
	defer h.Close()
	if h.Count() != 2 {
		t.Fatal("리스너 2개")
	}
	tlsAddr, httpAddr := h.lns[0].Addr().String(), h.lns[1].Addr().String()
	c, _ := net.Dial("tcp", tlsAddr)
	go tls.Client(c, &tls.Config{ServerName: "instagram.com"}).Handshake()
	if got := <-hits; got != "instagram.com" {
		t.Fatalf("tls hit %q", got)
	}
	c.Close()
	req, _ := http.NewRequest("GET", "http://"+httpAddr+"/", nil)
	req.Host = "namu.wiki"
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if got := <-hits; got != "namu.wiki" || !strings.Contains(string(body), "blocked namu.wiki") {
		t.Fatalf("http hit %q body %q", got, body)
	}
}

func TestDebouncer(t *testing.T) {
	d := newDebouncer(3 * time.Second)
	if !d.Allow("a", t0) || d.Allow("a", t0.Add(time.Second)) || !d.Allow("b", t0) || !d.Allow("a", t0.Add(4*time.Second)) {
		t.Fatal("debounce 오류")
	}
}

package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const (
	beginMark = "# >>> STUDY-BLOCKER BEGIN >>>"
	endMark   = "# <<< STUDY-BLOCKER END <<<"
)

// flushDNS는 OS별로 바뀐다(Windows: ipconfig /flushdns).
var flushDNS = func() {}

func hostsPath() string {
	if p := os.Getenv("STUDY_HOSTS"); p != "" {
		return p
	}
	if runtime.GOOS == "windows" {
		return filepath.Join(os.Getenv("SystemRoot"), "System32", "drivers", "etc", "hosts")
	}
	return "/etc/hosts"
}

// stripBlock은 hosts 내용에서 우리가 만든 구간을 지운다.
func stripBlock(content string) string {
	var kept []string
	skip := false
	for _, line := range strings.Split(content, "\n") {
		t := strings.TrimSpace(line)
		switch {
		case t == beginMark:
			skip = true
		case t == endMark:
			skip = false
		case !skip:
			kept = append(kept, strings.TrimRight(line, "\r"))
		}
	}
	return strings.TrimRight(strings.Join(kept, "\n"), "\n")
}

// hostVariants는 hosts가 와일드카드를 못 쓰므로 흔한 하위 주소를 함께 막는다.
func hostVariants(d string) []string {
	return []string{d, "www." + d, "m." + d}
}

func renderBlock(domains []string) string {
	var b strings.Builder
	b.WriteString(beginMark + "\n")
	for _, d := range domains {
		for _, h := range hostVariants(d) {
			b.WriteString("127.0.0.1 " + h + "\n::1 " + h + "\n")
		}
	}
	b.WriteString(endMark + "\n")
	return b.String()
}

// applyHosts는 domains로 차단 구간을 다시 쓴다. 비어 있으면 구간을 지운다.
func applyHosts(domains []string) error {
	data, err := os.ReadFile(hostsPath())
	if err != nil {
		return err
	}
	out := stripBlock(string(data)) + "\n"
	if len(domains) > 0 {
		out += renderBlock(domains)
	}
	if runtime.GOOS == "windows" {
		out = strings.ReplaceAll(out, "\n", "\r\n")
	}
	if err := os.WriteFile(hostsPath(), []byte(out), 0o644); err != nil {
		return err
	}
	flushDNS()
	return nil
}

// hostsBlockIntact는 우리가 쓴 구간이 지금 hosts에 그대로 있는지 확인한다.
func hostsBlockIntact(domains []string) bool {
	data, err := os.ReadFile(hostsPath())
	if err != nil {
		return false
	}
	s := strings.ReplaceAll(string(data), "\r\n", "\n")
	if len(domains) == 0 {
		return !strings.Contains(s, beginMark)
	}
	return strings.Contains(s, renderBlock(domains))
}

// matchBlocked는 host가 차단 목록의 어느 도메인에 속하는지 찾는다(하위 도메인 포함).
func matchBlocked(host string, set map[string]bool) string {
	h := strings.ToLower(strings.TrimSuffix(host, "."))
	for {
		if set[h] {
			return h
		}
		i := strings.IndexByte(h, '.')
		if i < 0 {
			return ""
		}
		h = h[i+1:]
	}
}

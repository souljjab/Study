// 공부 집중 사이트 차단기: hosts 파일에 차단 목록을 기록해 지정한 사이트 접속을 막는다.
package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	beginMark = "# >>> STUDY-BLOCKER BEGIN >>>"
	endMark   = "# <<< STUDY-BLOCKER END <<<"
)

var defaultSites = []string{
	"youtube.com", "youtu.be", "twitch.tv", "chzzk.naver.com", "afreecatv.com",
	"netflix.com", "tving.com", "watcha.com", "wavve.com",
	"instagram.com", "facebook.com", "twitter.com", "x.com", "tiktok.com",
	"reddit.com", "dcinside.com", "fmkorea.com", "ruliweb.com", "theqoo.net",
	"webtoon.naver.com", "comic.naver.com", "namu.wiki",
}

func hostsPath() string {
	if p := os.Getenv("STUDY_HOSTS"); p != "" {
		return p
	}
	if runtime.GOOS == "windows" {
		return filepath.Join(os.Getenv("SystemRoot"), "System32", "drivers", "etc", "hosts")
	}
	return "/etc/hosts"
}

func dataDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}

func sitesFile() string { return filepath.Join(dataDir(), "sites.txt") }
func lockFile() string  { return filepath.Join(dataDir(), "lock.txt") }

// normalize는 URL 형태 입력에서 도메인만 뽑아낸다.
func normalize(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	if i := strings.Index(s, "://"); i >= 0 {
		s = s[i+3:]
	}
	if i := strings.IndexAny(s, "/?#:"); i >= 0 {
		s = s[:i]
	}
	s = strings.TrimPrefix(s, "www.")
	if !strings.Contains(s, ".") || strings.ContainsAny(s, " \t") {
		return ""
	}
	return s
}

func loadSites() []string {
	data, err := os.ReadFile(sitesFile())
	if err != nil {
		saveSites(defaultSites)
		return append([]string(nil), defaultSites...)
	}
	var out []string
	for _, line := range strings.Split(string(data), "\n") {
		if d := normalize(line); d != "" {
			out = append(out, d)
		}
	}
	return out
}

func saveSites(sites []string) {
	sort.Strings(sites)
	_ = os.WriteFile(sitesFile(), []byte(strings.Join(sites, "\n")+"\n"), 0o644)
}

func eol() string {
	if runtime.GOOS == "windows" {
		return "\r\n"
	}
	return "\n"
}

// stripBlock은 hosts 내용에서 우리가 만든 구간을 제거한다.
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

func isBlocking() bool {
	data, err := os.ReadFile(hostsPath())
	return err == nil && strings.Contains(string(data), beginMark)
}

func applyBlock(sites []string) error {
	data, err := os.ReadFile(hostsPath())
	if err != nil {
		return err
	}
	var b strings.Builder
	b.WriteString(stripBlock(string(data)))
	b.WriteString("\n" + beginMark + "\n")
	for _, s := range sites {
		for _, h := range []string{s, "www." + s} {
			fmt.Fprintf(&b, "127.0.0.1 %s\n::1 %s\n", h, h)
		}
	}
	b.WriteString(endMark + "\n")
	out := strings.ReplaceAll(b.String(), "\n", eol())
	if err := os.WriteFile(hostsPath(), []byte(out), 0o644); err != nil {
		return err
	}
	flushDNS()
	return nil
}

func removeBlock() error {
	data, err := os.ReadFile(hostsPath())
	if err != nil {
		return err
	}
	out := strings.ReplaceAll(stripBlock(string(data))+"\n", "\n", eol())
	if err := os.WriteFile(hostsPath(), []byte(out), 0o644); err != nil {
		return err
	}
	flushDNS()
	return nil
}

func flushDNS() {
	if runtime.GOOS == "windows" {
		_ = exec.Command("ipconfig", "/flushdns").Run()
	}
}

func readLock() (time.Time, bool) {
	data, err := os.ReadFile(lockFile())
	if err != nil {
		return time.Time{}, false
	}
	sec, err := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64)
	if err != nil {
		return time.Time{}, false
	}
	return time.Unix(sec, 0), true
}

func writeLock(end time.Time) {
	_ = os.WriteFile(lockFile(), []byte(strconv.FormatInt(end.Unix(), 10)), 0o644)
}

func canWriteHosts() bool {
	f, err := os.OpenFile(hostsPath(), os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		return false
	}
	f.Close()
	return true
}

var in = bufio.NewReader(os.Stdin)

func prompt(msg string) string {
	fmt.Print(msg)
	line, _ := in.ReadString('\n')
	return strings.TrimSpace(line)
}

// runLock은 종료 시각까지 차단을 유지하며, 중간에 hosts가 되돌려지면 다시 적용한다.
func runLock(end time.Time) {
	fmt.Println("\n🔒 집중 모드 시작! 종료 시각까지 차단 해제가 불가능합니다.")
	fmt.Println("   (이 창을 닫아도 차단은 유지되고, 다시 실행하면 남은 시간이 이어집니다)")
	for time.Now().Before(end) {
		if !isBlocking() {
			if err := applyBlock(loadSites()); err != nil {
				fmt.Println("\n차단 재적용 실패:", err)
			}
		}
		left := time.Until(end).Round(time.Second)
		fmt.Printf("\r남은 시간 %02d:%02d:%02d  딴짓 금지! 공부하자 📚   ",
			int(left.Hours()), int(left.Minutes())%60, int(left.Seconds())%60)
		time.Sleep(2 * time.Second)
	}
	_ = removeBlock()
	_ = os.Remove(lockFile())
	fmt.Println("\n\n🎉 집중 시간 종료! 차단이 해제되었습니다. 수고했어요.")
}

func addSites(sites []string) []string {
	raw := prompt("추가할 사이트 주소(여러 개는 띄어쓰기로 구분): ")
	for _, f := range strings.Fields(raw) {
		d := normalize(f)
		if d == "" {
			fmt.Println("  무시됨:", f)
			continue
		}
		dup := false
		for _, s := range sites {
			dup = dup || s == d
		}
		if !dup {
			sites = append(sites, d)
			fmt.Println("  추가:", d)
		}
	}
	saveSites(sites)
	return sites
}

func removeSites(sites []string) []string {
	for i, s := range sites {
		fmt.Printf("%3d. %s\n", i+1, s)
	}
	raw := prompt("삭제할 번호(여러 개는 띄어쓰기로 구분): ")
	del := map[int]bool{}
	for _, f := range strings.Fields(raw) {
		if n, err := strconv.Atoi(f); err == nil && n >= 1 && n <= len(sites) {
			del[n-1] = true
		}
	}
	var out []string
	for i, s := range sites {
		if !del[i] {
			out = append(out, s)
		}
	}
	saveSites(out)
	return out
}

func main() {
	setupConsole()
	if !canWriteHosts() {
		if !elevate() {
			fmt.Println("hosts 파일을 수정할 권한이 없습니다. 관리자 권한(sudo)으로 다시 실행하세요.")
			prompt("엔터를 누르면 종료합니다.")
		}
		return
	}

	// 이전 집중 모드가 남아 있으면 이어서 진행
	if end, ok := readLock(); ok {
		if time.Now().Before(end) {
			fmt.Println("진행 중이던 집중 모드를 이어갑니다.")
			runLock(end)
			prompt("엔터를 누르면 종료합니다.")
			return
		}
		_ = removeBlock()
		_ = os.Remove(lockFile())
	}

	sites := loadSites()
	for {
		state := "해제됨"
		if isBlocking() {
			state = "차단 중"
		}
		fmt.Printf("\n===== 공부 집중 차단기 (%s / 사이트 %d개) =====\n", state, len(sites))
		fmt.Println("1. 집중 모드 시작 (타이머, 시간 내 해제 불가)")
		fmt.Println("2. 지금 바로 차단 (수동)")
		fmt.Println("3. 차단 해제 (수동 차단일 때만)")
		fmt.Println("4. 차단 목록 보기")
		fmt.Println("5. 사이트 추가")
		fmt.Println("6. 사이트 삭제")
		fmt.Println("0. 종료")
		switch prompt("선택> ") {
		case "1":
			n, err := strconv.Atoi(prompt("집중할 시간(분): "))
			if err != nil || n <= 0 || n > 24*60 {
				fmt.Println("1~1440 사이의 숫자를 입력하세요.")
				continue
			}
			end := time.Now().Add(time.Duration(n) * time.Minute)
			if err := applyBlock(sites); err != nil {
				fmt.Println("차단 실패:", err)
				continue
			}
			writeLock(end)
			runLock(end)
			prompt("엔터를 누르면 종료합니다.")
			return
		case "2":
			if err := applyBlock(sites); err != nil {
				fmt.Println("차단 실패:", err)
			} else {
				fmt.Println("차단했습니다. (브라우저를 완전히 재시작하면 확실합니다)")
			}
		case "3":
			if err := removeBlock(); err != nil {
				fmt.Println("해제 실패:", err)
			} else {
				fmt.Println("해제했습니다.")
			}
		case "4":
			for i, s := range sites {
				fmt.Printf("%3d. %s\n", i+1, s)
			}
		case "5":
			sites = addSites(sites)
		case "6":
			sites = removeSites(sites)
		case "0":
			return
		}
	}
}

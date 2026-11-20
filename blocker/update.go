package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 공개 저장소의 기본 브랜치에 있는 업데이트 정보.
const defaultManifestURL = "https://raw.githubusercontent.com/souljjab/Study/HEAD/dist/update.json"

// 내려받기를 허용하는 곳. update.json이 다른 곳을 가리켜도 받지 않는다.
var updateHosts = map[string]bool{
	"raw.githubusercontent.com": true, "github.com": true, "objects.githubusercontent.com": true,
	"127.0.0.1": true, // 테스트용
}

type Release struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
	Size    int64  `json:"size"`
	Notes   string `json:"notes"`
}

type Manifest struct {
	Windows *Release `json:"windows"`
}

// newerVersion은 a가 b보다 새 버전인지 알려 준다("2.10.0" > "2.9.1").
func newerVersion(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < max(len(pa), len(pb)); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

func allowedUpdateURL(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	h := u.Hostname()
	return updateHosts[h] && (u.Scheme == "https" || h == "127.0.0.1")
}

// UpdateView는 화면에 보여 줄 업데이트 상태다.
type UpdateView struct {
	Current string `json:"current"`
	State   string `json:"state"` // idle | checking | latest | downloading | ready | error
	Latest  string `json:"latest"`
	Notes   string `json:"notes"`
	Error   string `json:"error"`
	Checked int64  `json:"checked"` // 마지막 확인(밀리초)
}

// Updater는 새 버전을 확인하고, 내려받아 지문(SHA-256)이 맞는지 확인해 둔다.
type Updater struct {
	ManifestURL string
	Current     string
	Dir         string
	Client      *http.Client

	mu        sync.Mutex
	state     string
	latest    string
	notes     string
	err       string
	readyPath string
	checkedAt time.Time
}

func NewUpdater(dir string) *Updater {
	return &Updater{ManifestURL: defaultManifestURL, Current: appVersion, Dir: dir, state: "idle",
		Client: &http.Client{Timeout: 5 * time.Minute}}
}

func (u *Updater) View() UpdateView {
	u.mu.Lock()
	defer u.mu.Unlock()
	v := UpdateView{Current: u.Current, State: u.state, Latest: u.latest, Notes: u.notes, Error: u.err}
	if !u.checkedAt.IsZero() {
		v.Checked = u.checkedAt.UnixMilli()
	}
	return v
}

// ReadyPath는 내려받아 검증까지 끝난 새 실행 파일 경로다. 없으면 "".
func (u *Updater) ReadyPath() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.state != "ready" {
		return ""
	}
	return u.readyPath
}

func (u *Updater) LastCheck() time.Time {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.checkedAt
}

func (u *Updater) set(state, errMsg string) {
	u.mu.Lock()
	u.state, u.err = state, errMsg
	u.mu.Unlock()
}

// Check는 새 버전이 있으면 내려받아 검증한다. 이미 받아 둔 버전이면 다시 받지 않는다.
func (u *Updater) Check(ctx context.Context) error {
	u.mu.Lock()
	if u.state == "checking" || u.state == "downloading" {
		u.mu.Unlock()
		return nil
	}
	u.state, u.err = "checking", ""
	u.checkedAt = time.Now()
	u.mu.Unlock()

	rel, err := u.fetchManifest(ctx)
	if err != nil {
		u.set("error", err.Error())
		return err
	}
	if rel == nil || !newerVersion(rel.Version, u.Current) {
		u.set("latest", "")
		return nil
	}
	u.mu.Lock()
	u.latest, u.notes = rel.Version, rel.Notes
	already := u.readyPath != "" && strings.Contains(filepath.Base(u.readyPath), rel.Version)
	u.mu.Unlock()
	if already {
		u.set("ready", "")
		return nil
	}
	u.set("downloading", "")
	path, err := u.download(ctx, rel)
	if err != nil {
		u.set("error", err.Error())
		return err
	}
	u.mu.Lock()
	u.readyPath, u.state = path, "ready"
	u.mu.Unlock()
	return nil
}

func (u *Updater) get(ctx context.Context, raw string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := u.Client.Do(req)
	if err != nil {
		return nil, errors.New("업데이트 서버에 연결하지 못했어요.")
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("업데이트 서버 응답 오류(%d)", resp.StatusCode)
	}
	return resp, nil
}

func (u *Updater) fetchManifest(ctx context.Context) (*Release, error) {
	resp, err := u.get(ctx, u.ManifestURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var m Manifest
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&m); err != nil {
		return nil, errors.New("업데이트 정보를 읽지 못했어요.")
	}
	return m.Windows, nil
}

func (u *Updater) download(ctx context.Context, rel *Release) (string, error) {
	if !allowedUpdateURL(rel.URL) {
		return "", errors.New("허용되지 않은 주소라 내려받지 않았어요.")
	}
	want := strings.ToLower(strings.TrimSpace(rel.SHA256))
	if len(want) != 64 {
		return "", errors.New("업데이트 정보에 파일 지문이 없어요.")
	}
	resp, err := u.get(ctx, rel.URL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if err := os.MkdirAll(u.Dir, 0o755); err != nil {
		return "", err
	}
	final := filepath.Join(u.Dir, "StudyBlocker-"+rel.Version+".exe")
	part := final + ".part"
	f, err := os.Create(part)
	if err != nil {
		return "", err
	}
	limit := int64(128 << 20)
	if rel.Size > 0 {
		limit = rel.Size + 1
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, limit))
	f.Close()
	if err == nil && rel.Size > 0 && n != rel.Size {
		err = errors.New("내려받은 파일 크기가 맞지 않아요.")
	}
	if err == nil && hex.EncodeToString(h.Sum(nil)) != want {
		err = errors.New("내려받은 파일의 지문이 맞지 않아 버렸어요. 잠시 뒤 다시 시도해요.")
	}
	if err != nil {
		os.Remove(part)
		return "", err
	}
	if err := os.Rename(part, final); err != nil {
		return "", err
	}
	return final, nil
}

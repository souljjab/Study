package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// parseSNI는 TLS ClientHello에서 접속하려는 서버 이름을 꺼낸다.
func parseSNI(r io.Reader) (string, error) {
	hdr := make([]byte, 5)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return "", err
	}
	if hdr[0] != 0x16 {
		return "", errors.New("not a TLS handshake")
	}
	n := int(binary.BigEndian.Uint16(hdr[3:5]))
	if n > 1<<14+2048 {
		return "", errors.New("record too large")
	}
	b := make([]byte, n)
	if _, err := io.ReadFull(r, b); err != nil {
		return "", err
	}
	if len(b) < 4 || b[0] != 0x01 {
		return "", errors.New("not a ClientHello")
	}
	p := 4 + 2 + 32 // 핸드셰이크 헤더, 버전, random
	skip := func(lenBytes int) bool {
		if p+lenBytes > len(b) {
			return false
		}
		l := 0
		for i := 0; i < lenBytes; i++ {
			l = l<<8 | int(b[p+i])
		}
		p += lenBytes + l
		return p <= len(b)
	}
	if !skip(1) || !skip(2) || !skip(1) { // 세션 ID, 암호 스위트, 압축
		return "", errors.New("truncated ClientHello")
	}
	if p+2 > len(b) {
		return "", errors.New("no extensions")
	}
	end := p + 2 + int(binary.BigEndian.Uint16(b[p:]))
	p += 2
	if end > len(b) {
		end = len(b)
	}
	for p+4 <= end {
		typ := binary.BigEndian.Uint16(b[p:])
		l := int(binary.BigEndian.Uint16(b[p+2:]))
		p += 4
		if p+l > end {
			break
		}
		if typ == 0 && l >= 5 {
			e := b[p : p+l]
			nl := int(binary.BigEndian.Uint16(e[3:5]))
			if e[2] == 0 && 5+nl <= len(e) {
				return strings.ToLower(string(e[5 : 5+nl])), nil
			}
		}
		p += l
	}
	return "", errors.New("no SNI")
}

// HitListener는 hosts로 127.0.0.1에 돌려진 접속을 받아 어떤 사이트였는지 알린다.
type HitListener struct {
	lns []net.Listener
}

func (h *HitListener) Close() {
	for _, l := range h.lns {
		l.Close()
	}
}

func (h *HitListener) Count() int { return len(h.lns) }

// StartHitListener는 tlsAddrs(SNI 읽기)와 httpAddrs(차단 페이지 응답)에서 대기한다.
// 열 수 없는 주소는 건너뛴다(다른 프로그램이 포트를 쓰는 경우).
func StartHitListener(tlsAddrs, httpAddrs []string, onHit func(host string), page func(host string) string) *HitListener {
	h := &HitListener{}
	serve := func(l net.Listener, handle func(net.Conn)) {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(3 * time.Second))
				handle(c)
			}()
		}
	}
	for _, a := range tlsAddrs {
		if l, err := net.Listen("tcp", a); err == nil {
			h.lns = append(h.lns, l)
			go serve(l, func(c net.Conn) {
				if host, err := parseSNI(c); err == nil {
					onHit(host)
				}
			})
		}
	}
	for _, a := range httpAddrs {
		if l, err := net.Listen("tcp", a); err == nil {
			h.lns = append(h.lns, l)
			go serve(l, func(c net.Conn) {
				req, err := http.ReadRequest(bufio.NewReader(c))
				if err != nil {
					return
				}
				host := req.Host
				if hh, _, err := net.SplitHostPort(host); err == nil {
					host = hh
				}
				onHit(strings.ToLower(host))
				body := page(host)
				resp := "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
					"Connection: close\r\nContent-Length: " + itoa(len(body)) + "\r\n\r\n" + body
				_, _ = io.WriteString(c, resp)
			})
		}
	}
	return h
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

// debouncer는 브라우저가 한 번에 여러 연결을 여는 것을 한 번의 시도로 센다.
type debouncer struct {
	mu   sync.Mutex
	last map[string]time.Time
	gap  time.Duration
}

func newDebouncer(gap time.Duration) *debouncer {
	return &debouncer{last: map[string]time.Time{}, gap: gap}
}

func (d *debouncer) Allow(key string, now time.Time) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if t, ok := d.last[key]; ok && now.Sub(t) < d.gap {
		return false
	}
	d.last[key] = now
	return true
}

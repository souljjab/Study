package main

import "time"

// seedDemo는 화면 확인용으로 최근 기록을 채운다(개발 서버 전용).
func seedDemo(e *Engine, now time.Time) {
	e.mu.Lock()
	defer e.mu.Unlock()
	mins := []int{95, 140, 60, 180, 125, 30, 70}
	for i, m := range mins {
		e.st.DayFocus[dateKey(now.AddDate(0, 0, i-6))] = m * 60
	}
	today := dateKey(now)
	e.st.Hits[today] = map[string]int{"youtube.com": 14, "instagram.com": 9, "namu.wiki": 6, "everytime.kr": 4, "x.com": 3}
	e.st.Sessions = append(e.st.Sessions,
		Session{today, "09:10", "뽀모도로", 1500, 3}, Session{today, "09:40", "뽀모도로", 1500, 1},
		Session{today, "13:05", "자유", 1200, 4})
	e.st.Onboarded = true
}

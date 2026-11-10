package main

import (
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

// Unlock은 긴급 잠금 해제 한 번의 기록이다.
type Unlock struct {
	ID      int64  `json:"id"`
	At      int64  `json:"at"`      // 시작(유닉스 초)
	Minutes int    `json:"minutes"` // 요청한 해제 시간
	Reason  string `json:"reason"`
	EndedAt int64  `json:"endedAt"` // 직접 다시 차단한 시각, 시간이 다 돼 끝났으면 0
}

const (
	minUnlockReason = 10
	maxUnlockMin    = 30   // 한 번에 최대 30분
	unlockCooldown  = 3600 // 직전 긴급 해제를 시작하고 1시간이 지나야 다시 가능(초)
)

// validateUnlock은 긴급 해제 입력을 확인하고 다듬은 사유를 돌려준다.
func validateUnlock(reason string, minutes int) (string, error) {
	r := strings.Join(strings.Fields(reason), " ")
	if utf8.RuneCountInString(r) < minUnlockReason {
		return "", errors.New("사유를 10자 이상 적어 주세요.")
	}
	if utf8.RuneCountInString(r) > 300 {
		return "", errors.New("사유는 300자까지 적을 수 있어요.")
	}
	if minutes < 1 || minutes > maxUnlockMin {
		return "", errors.New("해제 시간은 1~30분 사이로 정하세요.")
	}
	return r, nil
}

// unlocked는 긴급 해제로 차단이 잠시 멈춘 동안이다.
// 이때도 잠금 규칙(목록·예약·설정 변경, 종료 거부)은 그대로 적용된다.
func (e *Engine) unlocked() bool {
	return e.st.UnlockUntil > 0 && e.now.Unix() < e.st.UnlockUntil
}

func (e *Engine) doUnlock(action string, p payload) error {
	now := e.now.Unix()
	switch action {
	case "emergencyUnlock":
		if e.unlocked() {
			return errors.New("이미 긴급 해제 중이에요.")
		}
		if next := e.nextUnlockAt(); now < next {
			return fmt.Errorf("긴급 해제는 1시간에 한 번만 할 수 있어요. %s부터 다시 가능해요.", time.Unix(next, 0).Format("15:04"))
		}
		if !e.focusEnforcing() && len(e.activeSchedules()) == 0 {
			return errors.New("지금은 막고 있는 게 없어요.")
		}
		r, err := validateUnlock(p.Reason, p.Min)
		if err != nil {
			return err
		}
		e.st.NextID++
		e.st.Unlocks = append(e.st.Unlocks, Unlock{ID: e.st.NextID, At: now, Minutes: p.Min, Reason: r})
		e.st.UnlockUntil = now + int64(p.Min)*60
		e.unlockOn = true
	case "endUnlock":
		if !e.unlocked() {
			return nil
		}
		e.st.UnlockUntil = now
		if n := len(e.st.Unlocks); n > 0 && e.st.Unlocks[n-1].EndedAt == 0 {
			e.st.Unlocks[n-1].EndedAt = now
		}
		e.unlockOn = false
	}
	return nil
}

// nextUnlockAt은 다음 긴급 해제가 가능한 시각(유닉스 초). 기록이 없으면 0.
func (e *Engine) nextUnlockAt() int64 {
	if n := len(e.st.Unlocks); n > 0 {
		return e.st.Unlocks[n-1].At + unlockCooldown
	}
	return 0
}

// UnlockView는 기록 화면용이다. Actual은 실제로 풀려 있던 초.
type UnlockView struct {
	Unlock
	Actual  int  `json:"actual"`
	Ongoing bool `json:"ongoing"`
}

func (e *Engine) unlockViews() []UnlockView {
	out := []UnlockView{}
	now := e.now.Unix()
	for i := len(e.st.Unlocks) - 1; i >= 0; i-- {
		u := e.st.Unlocks[i]
		end := u.At + int64(u.Minutes)*60
		if u.EndedAt > 0 && u.EndedAt < end {
			end = u.EndedAt
		}
		ongoing := i == len(e.st.Unlocks)-1 && e.unlocked()
		if ongoing && now < end {
			end = now
		}
		out = append(out, UnlockView{Unlock: u, Actual: int(max(0, end-u.At)), Ongoing: ongoing})
	}
	return out
}

// unlockCount7d는 최근 7일 동안 긴급 해제한 횟수다.
func (e *Engine) unlockCount7d() int {
	cut := e.now.Add(-7 * 24 * time.Hour).Unix()
	n := 0
	for _, u := range e.st.Unlocks {
		if u.At >= cut {
			n++
		}
	}
	return n
}

//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

const (
	wmTray     = wmApp + 2
	nimAdd     = 0
	nimModify  = 1
	nimDelete  = 2
	nifMessage = 0x1
	nifIcon    = 0x2
	nifTip     = 0x4
	nifInfo    = 0x10
	menuOpen   = 1
	menuQuit   = 2
)

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         [16]byte
	HBalloonIcon     uintptr
}

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     uintptr
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       uintptr
}

type point struct{ X, Y int32 }

// Tray는 알림 영역 아이콘. 메인 스레드(웹뷰와 같은 메시지 루프)에서 만든다.
type Tray struct {
	hwnd   uintptr
	nid    notifyIconData
	OnOpen func()
	OnQuit func()
}

var theTray *Tray

func copyUTF16(dst []uint16, s string) {
	src, _ := syscall.UTF16FromString(s)
	if len(src) > len(dst) {
		src = src[:len(dst)-1]
		src = append(src, 0)
	}
	copy(dst, src)
}

func NewTray(tip string) *Tray {
	t := &Tray{}
	theTray = t
	hinst, _, _ := pGetModuleHandleW.Call(0)
	wc := wndClassEx{
		LpfnWndProc:   syscall.NewCallback(trayWndProc),
		HInstance:     hinst,
		LpszClassName: u16("MollipTray"),
	}
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
	t.hwnd, _, _ = pCreateWindowExW.Call(0, uintptr(unsafe.Pointer(wc.LpszClassName)), uintptr(unsafe.Pointer(u16("Mollip tray"))),
		0, 0, 0, 0, 0, 0, 0, hinst, 0)
	cx, _, _ := pGetSystemMetrics.Call(49) // SM_CXSMICON
	cy, _, _ := pGetSystemMetrics.Call(50)
	icon, _, _ := pLoadImageW.Call(hinst, 1, 1, cx, cy, 0x8000) // 리소스 #1, IMAGE_ICON, LR_SHARED
	t.nid = notifyIconData{HWnd: t.hwnd, UID: 1, UFlags: nifMessage | nifIcon | nifTip, UCallbackMessage: wmTray, HIcon: icon}
	t.nid.CbSize = uint32(unsafe.Sizeof(t.nid))
	copyUTF16(t.nid.SzTip[:], tip)
	pShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid)))
	return t
}

func (t *Tray) SetTip(tip string) {
	t.nid.UFlags = nifMessage | nifIcon | nifTip
	copyUTF16(t.nid.SzTip[:], tip)
	pShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
}

// Notify는 풍선 알림을 띄운다.
func (t *Tray) Notify(title, msg string) {
	t.nid.UFlags = nifMessage | nifIcon | nifTip | nifInfo
	copyUTF16(t.nid.SzInfoTitle[:], title)
	copyUTF16(t.nid.SzInfo[:], msg)
	t.nid.DwInfoFlags = 0x1 // NIIF_INFO
	pShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
	t.nid.SzInfo = [256]uint16{}
}

func (t *Tray) Remove() {
	pShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
}

func trayWndProc(hwnd, msg, wp, lp uintptr) uintptr {
	t := theTray
	if t != nil && msg == wmTray {
		switch lp & 0xFFFF {
		case wmLButtonUp, wmLButtonDbl:
			if t.OnOpen != nil {
				t.OnOpen()
			}
		case wmRButtonUp:
			t.menu()
		}
		return 0
	}
	r, _, _ := pDefWindowProcW.Call(hwnd, msg, wp, lp)
	return r
}

func (t *Tray) menu() {
	m, _, _ := pCreatePopupMenu.Call()
	pAppendMenuW.Call(m, 0, menuOpen, uintptr(unsafe.Pointer(u16("몰입 열기"))))
	pAppendMenuW.Call(m, 0x800, 0, 0) // MF_SEPARATOR
	pAppendMenuW.Call(m, 0, menuQuit, uintptr(unsafe.Pointer(u16("종료"))))
	var p point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	pSetForegroundWindow.Call(t.hwnd)
	cmd, _, _ := pTrackPopupMenu.Call(m, 0x0100|0x0002, uintptr(p.X), uintptr(p.Y), 0, t.hwnd, 0) // TPM_RETURNCMD|TPM_RIGHTBUTTON
	pPostMessageW.Call(t.hwnd, wmNull, 0, 0)
	pDestroyMenu.Call(m)
	switch cmd {
	case menuOpen:
		if t.OnOpen != nil {
			t.OnOpen()
		}
	case menuQuit:
		if t.OnQuit != nil {
			t.OnQuit()
		}
	}
}

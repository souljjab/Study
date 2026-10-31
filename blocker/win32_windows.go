//go:build windows

package main

import (
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")

	pShowWindow          = user32.NewProc("ShowWindow")
	pSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	pSetWindowPos        = user32.NewProc("SetWindowPos")
	pIsIconic            = user32.NewProc("IsIconic")
	pFindWindowW         = user32.NewProc("FindWindowW")
	pMessageBoxW         = user32.NewProc("MessageBoxW")
	pMessageBeep         = user32.NewProc("MessageBeep")
	pSetWindowLongPtrW   = user32.NewProc("SetWindowLongPtrW")
	pCallWindowProcW     = user32.NewProc("CallWindowProcW")
	pRegisterClassExW    = user32.NewProc("RegisterClassExW")
	pCreateWindowExW     = user32.NewProc("CreateWindowExW")
	pDefWindowProcW      = user32.NewProc("DefWindowProcW")
	pLoadImageW          = user32.NewProc("LoadImageW")
	pGetSystemMetrics    = user32.NewProc("GetSystemMetrics")
	pCreatePopupMenu     = user32.NewProc("CreatePopupMenu")
	pAppendMenuW         = user32.NewProc("AppendMenuW")
	pTrackPopupMenu      = user32.NewProc("TrackPopupMenu")
	pDestroyMenu         = user32.NewProc("DestroyMenu")
	pGetCursorPos        = user32.NewProc("GetCursorPos")
	pPostMessageW        = user32.NewProc("PostMessageW")
	pShellNotifyIconW    = shell32.NewProc("Shell_NotifyIconW")
	pShellExecuteW       = shell32.NewProc("ShellExecuteW")
	pCreateMutexW        = kernel32.NewProc("CreateMutexW")
	pGetModuleHandleW    = kernel32.NewProc("GetModuleHandleW")
)

const (
	swHide         = 0
	swShow         = 5
	swRestore      = 9
	wmClose        = 0x0010
	wmNull         = 0x0000
	wmCommand      = 0x0111
	wmLButtonUp    = 0x0202
	wmLButtonDbl   = 0x0203
	wmRButtonUp    = 0x0205
	wmApp          = 0x8000
	swpNoSize      = 0x0001
	swpNoMove      = 0x0002
	swpShowWindow  = 0x0040
	createNoWindow = 0x08000000
)

func u16(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }

// bringToFront는 다른 앱이 앞에 있어도 창을 위로 올린다.
func bringToFront(hwnd uintptr) {
	if r, _, _ := pIsIconic.Call(hwnd); r != 0 {
		pShowWindow.Call(hwnd, swRestore)
	} else {
		pShowWindow.Call(hwnd, swShow)
	}
	topmost, notopmost := ^uintptr(0), ^uintptr(1) // HWND_TOPMOST(-1), HWND_NOTOPMOST(-2)
	pSetWindowPos.Call(hwnd, topmost, 0, 0, 0, 0, swpNoMove|swpNoSize|swpShowWindow)
	pSetWindowPos.Call(hwnd, notopmost, 0, 0, 0, 0, swpNoMove|swpNoSize|swpShowWindow)
	pSetForegroundWindow.Call(hwnd)
}

func hideWindow(hwnd uintptr) { pShowWindow.Call(hwnd, swHide) }

func messageBox(text, title string, flags uintptr) int {
	r, _, _ := pMessageBoxW.Call(0, uintptr(unsafe.Pointer(u16(text))), uintptr(unsafe.Pointer(u16(title))), flags)
	return int(r)
}

func openURL(url string) {
	pShellExecuteW.Call(0, uintptr(unsafe.Pointer(u16("open"))), uintptr(unsafe.Pointer(u16(url))), 0, 0, swShow)
}

func beep() { pMessageBeep.Call(0x40) } // MB_ICONASTERISK

// hiddenCmd는 콘솔 창을 띄우지 않고 명령을 실행한다.
func hiddenCmd(cmdline string, name string, args ...string) error {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	if cmdline != "" {
		c.SysProcAttr.CmdLine = cmdline
	}
	return c.Run()
}

func init() {
	flushDNS = func() { _ = hiddenCmd("", "ipconfig", "/flushdns") }
}

// setAutostart는 로그인 시 관리자 권한으로 트레이 모드 실행을 작업 스케줄러에 등록/해제한다.
// (시작 프로그램 레지스트리는 관리자 프로그램을 UAC 확인 없이 띄울 수 없다.)
func setAutostart(on bool) error {
	if !on {
		_ = hiddenCmd("", "schtasks", "/Delete", "/F", "/TN", "Mollip")
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return hiddenCmd(`schtasks /Create /F /TN Mollip /SC ONLOGON /RL HIGHEST /TR "\"`+exe+`\" --tray"`, "schtasks")
}

// singleInstance는 이미 실행 중이면 false를 돌려준다.
func singleInstance() bool {
	pCreateMutexW.Call(0, 0, uintptr(unsafe.Pointer(u16("Local\\MollipStudyBlocker"))))
	return syscall.GetLastError() != syscall.Errno(183) // ERROR_ALREADY_EXISTS
}

func findMainWindow() uintptr {
	h, _, _ := pFindWindowW.Call(uintptr(unsafe.Pointer(u16("webview"))), uintptr(unsafe.Pointer(u16(windowTitle))))
	return h
}

var origWndProc uintptr

// interceptClose는 창 닫기(X)를 '트레이로 숨기기'로 바꾼다.
func interceptClose(hwnd uintptr, onClose func()) {
	cb := syscall.NewCallback(func(h, msg, wp, lp uintptr) uintptr {
		if msg == wmClose {
			onClose()
			return 0
		}
		r, _, _ := pCallWindowProcW.Call(origWndProc, h, msg, wp, lp)
		return r
	})
	gwlpWndProc := ^uintptr(3) // -4
	origWndProc, _, _ = pSetWindowLongPtrW.Call(hwnd, gwlpWndProc, cb)
}

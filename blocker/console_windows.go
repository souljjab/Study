package main

import (
	"os"
	"syscall"
	"unsafe"
)

// setupConsole은 콘솔을 UTF-8로 바꿔 한글이 깨지지 않게 한다.
func setupConsole() {
	k := syscall.NewLazyDLL("kernel32.dll")
	k.NewProc("SetConsoleOutputCP").Call(65001)
	k.NewProc("SetConsoleCP").Call(65001)
}

// elevate는 관리자 권한(UAC)으로 자기 자신을 다시 실행한다.
func elevate() bool {
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	verb, _ := syscall.UTF16PtrFromString("runas")
	file, _ := syscall.UTF16PtrFromString(exe)
	cwd, _ := syscall.UTF16PtrFromString(dataDir())
	r, _, _ := syscall.NewLazyDLL("shell32.dll").NewProc("ShellExecuteW").Call(
		0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(file)), 0,
		uintptr(unsafe.Pointer(cwd)), 1)
	return r > 32
}

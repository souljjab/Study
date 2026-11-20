//go:build windows

package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"time"
)

func currentExe() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.EvalSymlinks(exe)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// installUpdate는 실행 중인 exe를 .old로 비켜 두고 새 exe를 제자리에 놓은 뒤 새 프로그램을 띄운다.
// (실행 중인 파일은 덮어쓸 수 없지만 이름은 바꿀 수 있다.) 실패하면 원래대로 되돌린다.
func installUpdate(newPath string, args ...string) error {
	exe, err := currentExe()
	if err != nil {
		return err
	}
	old := exe + ".old"
	_ = os.Remove(old)
	if err := os.Rename(exe, old); err != nil {
		return fmt.Errorf("실행 파일을 바꾸지 못했어요: %v", err)
	}
	restore := func() {
		_ = os.Remove(exe)
		_ = os.Rename(old, exe)
	}
	if err := copyFile(newPath, exe); err != nil {
		restore()
		return fmt.Errorf("새 버전을 복사하지 못했어요: %v", err)
	}
	cmd := exec.Command(exe, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: false}
	if err := cmd.Start(); err != nil {
		restore()
		return fmt.Errorf("새 버전을 실행하지 못했어요: %v", err)
	}
	_ = os.Remove(newPath)
	return nil
}

// cleanupOldExe는 업데이트 전 실행 파일(.old)을 지운다. 옛 프로그램이 아직 끝나는 중이면 잠시 뒤 다시 시도한다.
func cleanupOldExe() {
	exe, err := currentExe()
	if err != nil {
		return
	}
	for i := 0; i < 20; i++ {
		if err := os.Remove(exe + ".old"); err == nil || os.IsNotExist(err) {
			return
		}
		time.Sleep(3 * time.Second)
	}
}

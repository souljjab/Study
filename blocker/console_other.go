//go:build !windows

package main

func setupConsole() {}

func elevate() bool { return false }

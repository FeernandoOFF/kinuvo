package main

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

type stopper interface{ stop() error }

type tunnel struct {
	mu          sync.Mutex
	closed      bool
	conns       map[net.Conn]bool
	listener    net.Listener
	cancel      context.CancelFunc
	closeNative func() error
	lock        *os.File
}

func newTunnel(cancel context.CancelFunc) *tunnel {
	return &tunnel{conns: make(map[net.Conn]bool), cancel: cancel}
}

func hold(state string) (*os.File, error) {
	if state == "" {
		return nil, errors.New("state directory required")
	}
	if err := os.MkdirAll(state, 0700); err != nil {
		return nil, errors.New("cannot create state directory")
	}
	f, err := os.OpenFile(filepath.Join(state, "lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, errors.New("cannot open state lock")
	}
	if err = syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		return nil, errors.New("Tailcat is active for this home; stop the Server first")
	}
	return f, nil
}

func release(f *os.File) {
	if f != nil {
		syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
		f.Close()
	}
}

func (t *tunnel) track(c net.Conn) bool {
	t.mu.Lock()
	if t.closed || len(t.conns) >= 128 {
		t.mu.Unlock()
		go func() {
			defer func() {
				if v := recover(); v != nil {
					debugLog("native panic: %v", v)
				}
			}()
			c.Close()
		}()
		return false
	}
	t.conns[c] = true
	t.mu.Unlock()
	return true
}

func (t *tunnel) accept(dial func() (net.Conn, error)) {
	defer func() {
		if v := recover(); v != nil {
			debugLog("native panic: %v", v)
		}
	}()
	for {
		a, err := t.listener.Accept()
		if err != nil {
			return
		}
		if !t.track(a) {
			continue
		}
		go func() {
			defer func() {
				if v := recover(); v != nil {
					debugLog("native panic: %v", v)
				}
			}()
			defer func() { a.Close(); t.mu.Lock(); delete(t.conns, a); t.mu.Unlock() }()
			b, err := dial()
			if err != nil {
				debugLog("dial failed: %v", err)
				return
			}
			if !t.track(b) {
				return
			}
			defer func() { b.Close(); t.mu.Lock(); delete(t.conns, b); t.mu.Unlock() }()
			done := make(chan struct{})
			go func() {
				defer func() { recover(); close(done) }()
				io.Copy(b, a)
				if half, ok := b.(interface{ CloseWrite() error }); ok {
					half.CloseWrite()
				} else {
					b.Close()
				}
			}()
			io.Copy(a, b)
			if half, ok := a.(interface{ CloseWrite() error }); ok {
				half.CloseWrite()
			} else {
				a.Close()
			}
			<-done
		}()
	}
}

func (t *tunnel) stop() error {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil
	}
	t.closed = true
	conns := make([]net.Conn, 0, len(t.conns))
	for c := range t.conns {
		conns = append(conns, c)
	}
	t.mu.Unlock()
	t.cancel()
	done := make(chan error, 1)
	go func() {
		defer func() {
			if recover() != nil {
				done <- errors.New("Tailcat close failed; restart before resetting")
			}
		}()
		if t.listener != nil {
			t.listener.Close()
		}
		for _, c := range conns {
			go func() {
				defer func() {
					if v := recover(); v != nil {
						debugLog("native panic: %v", v)
					}
				}()
				c.Close()
			}()
		}
		if t.closeNative != nil {
			if err := t.closeNative(); err != nil {
				done <- errors.New("Tailcat close failed; restart before resetting")
				return
			}
		}
		release(t.lock)
		done <- nil
	}()
	select {
	case err := <-done:
		return err
	case <-time.After(2 * time.Second):
		return errors.New("Tailcat close timed out; restart the process before resetting")
	}
}

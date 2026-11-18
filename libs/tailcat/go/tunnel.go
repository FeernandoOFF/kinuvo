package main

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/jt-wang/tailpocket/connecthost"
)

type stopper interface{ stop() error }

type tunnel struct {
	mu          sync.Mutex
	closed      bool
	conns       map[net.Conn]bool
	links       map[net.Conn]net.Conn
	refuse      func(net.Conn) bool
	listener    net.Listener
	cancel      context.CancelFunc
	closeNative func() error
	lock        *os.File
}

func newTunnel(cancel context.CancelFunc) *tunnel {
	return &tunnel{conns: make(map[net.Conn]bool), links: make(map[net.Conn]net.Conn), cancel: cancel}
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
	if err := lockState(f); err != nil {
		f.Close()
		return nil, errors.New("Tailcat is active for this home; stop the Server first")
	}
	return f, nil
}

func release(f *os.File) {
	if f != nil {
		unlockState(f)
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
			defer func() { a.Close(); t.mu.Lock(); delete(t.conns, a); delete(t.links, a); t.mu.Unlock() }()
			if t.refuse != nil && t.refuse(a) {
				return
			}
			b, err := dial()
			if err != nil {
				debugLog("dial failed: %v", err)
				return
			}
			if !t.track(b) {
				return
			}
			defer func() {
				t.mu.Lock()
				delete(t.links, a)
				delete(t.conns, b)
				t.mu.Unlock()
				b.Close()
			}()
			t.mu.Lock()
			t.links[a] = b
			t.mu.Unlock()
			// Revocation can race the backend dial.
			if t.refuse != nil && t.refuse(a) {
				return
			}
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

func (t *tunnel) peerAt(port int) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.closed {
		for peer, backend := range t.links {
			if addr, ok := backend.LocalAddr().(*net.TCPAddr); ok && addr.Port == port {
				return connecthost.PhoneOf(peer)
			}
		}
	}
	return ""
}

// closePhone drops every connection a phone has open, and the Server connections bridged to them.
func (t *tunnel) closePhone(phone string) {
	t.mu.Lock()
	var drop []net.Conn
	for peer, backend := range t.links {
		if connecthost.PhoneOf(peer) == phone {
			delete(t.links, peer)
			drop = append(drop, peer, backend)
		}
	}
	t.mu.Unlock()
	for _, c := range drop {
		c.Close()
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

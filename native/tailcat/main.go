package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/tailscale/tailcat"
)

const virtualPort = 8787

type request struct {
	State   string `json:"state"`
	Port    int    `json:"port"`
	Address string `json:"address"`
	Handle  uint64 `json:"handle"`
}
type response struct {
	Handle  uint64 `json:"handle,omitempty"`
	Address string `json:"address,omitempty"`
	Port    int    `json:"port,omitempty"`
	Error   string `json:"error,omitempty"`
}
type tunnel struct {
	mu          sync.Mutex
	closed      bool
	conns       map[net.Conn]bool
	listener    net.Listener
	cancel      context.CancelFunc
	closeNative func() error
	lock        *os.File
}

var registry = struct {
	sync.Mutex
	next  uint64
	items map[uint64]*tunnel
}{items: make(map[uint64]*tunnel)}

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
		return nil, errors.New("Tailcat is active for this home; stop the Server before resetting")
	}
	return f, nil
}
func release(f *os.File) {
	if f != nil {
		syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
		f.Close()
	}
}
func save(state string, key *tailcat.PrivateKey) error {
	data, err := json.Marshal(key)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(state, "key-")
	if err != nil {
		return errors.New("cannot create key file")
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	if e := f.Close(); err == nil {
		err = e
	}
	if err == nil {
		err = os.Rename(f.Name(), filepath.Join(state, "key.json"))
	}
	if err != nil {
		return errors.New("cannot save key")
	}
	return nil
}
func serve(r request) (_ *tunnel, addr string, err error) {
	if r.Port < 1 || r.Port > 65535 {
		return nil, "", errors.New("invalid loopback port")
	}
	lock, err := hold(r.State)
	if err != nil {
		return nil, "", err
	}
	ctx, cancel := context.WithCancel(context.Background())
	t := &tunnel{conns: make(map[net.Conn]bool), cancel: cancel, lock: lock}
	ready := false
	defer func() {
		if !ready {
			t.stop()
		}
	}()
	key := tailcat.NewPrivateKey()
	data, e := os.ReadFile(filepath.Join(r.State, "key.json"))
	if e == nil {
		if json.Unmarshal(data, key) != nil || key.Private.IsZero() || key.Public.PresharedKey.IsZero() || len(key.Public.Region) != 1 {
			return nil, "", errors.New("invalid saved Tailcat key")
		}
		if os.Chmod(filepath.Join(r.State, "key.json"), 0600) != nil {
			return nil, "", errors.New("cannot secure saved key")
		}
	} else if !os.IsNotExist(e) {
		return nil, "", errors.New("cannot read saved key")
	}
	s := &tailcat.Server{Key: key.Private, PresharedKey: key.Public.PresharedKey, Logf: debugLog}
	if len(key.Public.Region) == 1 {
		s.Region = key.Public.Region[0]
	}
	t.closeNative = s.Close
	start, done := context.WithTimeout(ctx, 30*time.Second)
	defer done()
	t.listener, err = s.Listen(start, "tcp", fmt.Sprintf(":%d", virtualPort))
	if err != nil {
		debugLog("serve failed: %v", err)
		return nil, "", startupError(err)
	}
	addr = string(s.TailcatAddr())
	key.Public, err = tailcat.ParseAddr(tailcat.Addr(addr))
	if err != nil {
		return nil, "", errors.New("cannot read Tailcat address")
	}
	if err = save(r.State, key); err != nil {
		return nil, "", err
	}
	go t.accept(func() (net.Conn, error) {
		return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", fmt.Sprintf("127.0.0.1:%d", r.Port))
	})
	ready = true
	return t, addr, nil
}
func forward(r request) (*tunnel, int, error) {
	if _, err := tailcat.ParseAddr(tailcat.Addr(r.Address)); err != nil {
		return nil, 0, errors.New("invalid Tailcat address")
	}
	ctx, cancel := context.WithCancel(context.Background())
	client := tailcat.NewClient(tailcat.Addr(r.Address))
	client.Logf = debugLog
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		cancel()
		return nil, 0, errors.New("cannot bind forward listener")
	}
	t := &tunnel{conns: make(map[net.Conn]bool), listener: ln, cancel: cancel, closeNative: client.Close}
	go t.accept(func() (net.Conn, error) {
		ctx, done := context.WithTimeout(ctx, 30*time.Second)
		defer done()
		return client.DialTCPPort(ctx, virtualPort)
	})
	return t, ln.Addr().(*net.TCPAddr).Port, nil
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
		// Keep the state lock while upstream may still serve the old identity.
		return errors.New("Tailcat close timed out; restart the process before resetting")
	}
}
func dispatch(method string, r request) response {
	switch method {
	case "reset":
		lock, err := hold(r.State)
		if err != nil {
			return response{Error: err.Error()}
		}
		defer release(lock)
		if err = os.Remove(filepath.Join(r.State, "key.json")); err != nil && !os.IsNotExist(err) {
			return response{Error: "cannot remove saved key"}
		}
		return response{}
	case "stop":
		registry.Lock()
		t := registry.items[r.Handle]
		delete(registry.items, r.Handle)
		registry.Unlock()
		if t != nil {
			if err := t.stop(); err != nil {
				return response{Error: err.Error()}
			}
		}
		return response{}
	case "serve", "forward":
		var t *tunnel
		var err error
		result := response{}
		if method == "serve" {
			t, result.Address, err = serve(r)
		} else {
			t, result.Port, err = forward(r)
		}
		if err != nil {
			return response{Error: err.Error()}
		}
		registry.Lock()
		registry.next++
		result.Handle = registry.next
		registry.items[result.Handle] = t
		registry.Unlock()
		return result
	default:
		return response{Error: "unknown method"}
	}
}

//export kinuvo_tailcat_abi_version
func kinuvo_tailcat_abi_version() C.int { return 1 }

//export kinuvo_tailcat_call
func kinuvo_tailcat_call(method, params *C.char) (ret *C.char) {
	defer func() {
		if recover() != nil {
			ret = C.CString(`{"error":"Tailcat operation failed"}`)
		}
	}()
	var r request
	if method == nil || params == nil || json.Unmarshal([]byte(C.GoString(params)), &r) != nil {
		return C.CString(`{"error":"invalid request"}`)
	}
	data, _ := json.Marshal(dispatch(C.GoString(method), r))
	return C.CString(string(data))
}

//export kinuvo_tailcat_free
func kinuvo_tailcat_free(value *C.char) { C.free(unsafe.Pointer(value)) }

func main() {}

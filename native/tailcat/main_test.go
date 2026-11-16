package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
)

func TestSavedIdentityAndResetLock(t *testing.T) {
	state := t.TempDir()
	lock, err := hold(state)
	if err != nil {
		t.Fatal(err)
	}
	key := tailcat.NewPrivateKey()
	if err := save(state, key); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(state, "key.json")
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal(info.Mode())
	}
	data, _ := os.ReadFile(path)
	var restored tailcat.PrivateKey
	if err := json.Unmarshal(data, &restored); err != nil {
		t.Fatal(err)
	}
	if !restored.Private.Equal(key.Private) || !restored.Public.PresharedKey.Equal(key.Public.PresharedKey) {
		t.Fatal("identity changed")
	}
	if dispatch("reset", request{State: state}).Error == "" {
		t.Fatal("reset allowed while active")
	}
	release(lock)
	if result := dispatch("reset", request{State: state}); result.Error != "" {
		t.Fatal(result.Error)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("key retained")
	}
}

func TestStopIsBoundedAndRetainsLockUntilClosed(t *testing.T) {
	state := t.TempDir()
	lock, _ := hold(state)
	_, cancel := context.WithCancel(context.Background())
	unblock := make(chan struct{})
	tunnel := &tunnel{cancel: cancel, lock: lock, closeNative: func() error { <-unblock; return nil }}
	start := time.Now()
	if tunnel.stop() == nil || time.Since(start) > 3*time.Second {
		t.Fatal("stop was not bounded")
	}
	if dispatch("reset", request{State: state}).Error == "" {
		t.Fatal("released a live identity")
	}
	close(unblock)
	deadline := time.Now().Add(time.Second)
	for {
		f, err := hold(state)
		if err == nil {
			release(f)
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("lock retained after close")
		}
		time.Sleep(time.Millisecond)
	}
}

func TestInvalidInputs(t *testing.T) {
	if dispatch("serve", request{Port: 0}).Error == "" {
		t.Fatal("invalid port accepted")
	}
	if dispatch("forward", request{Address: "invalid"}).Error == "" {
		t.Fatal("invalid address accepted")
	}
}

func TestLiveForward(t *testing.T) {
	if os.Getenv("KINUVO_TAILCAT_LIVE") != "1" {
		t.Skip("opt-in public relay test")
	}
	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	go func() {
		conn, err := backend.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		conn.Write([]byte("hello"))
	}()
	server, addr, err := serve(request{State: t.TempDir(), Port: backend.Addr().(*net.TCPAddr).Port})
	if err != nil {
		t.Fatal(err)
	}
	defer server.stop()
	client, port, err := forward(request{Address: addr})
	if err != nil {
		t.Fatal(err)
	}
	defer client.stop()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(45 * time.Second))
	buf := make([]byte, 5)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("forward failed: %v", err)
	}
}

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/jt-wang/tailpocket/connecthost"
)

func TestSavedStateAndResetLock(t *testing.T) {
	dir := t.TempDir()
	lock, err := hold(dir)
	if err != nil {
		t.Fatal(err)
	}
	want := connecthost.State{Identity: `{"v":1}`, Allowed: []string{"nodekey:a", "nodekey:b"}}
	if err := saveState(dir, want); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, stateFile)
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal(info.Mode())
	}
	got, err := loadState(dir)
	if err != nil || got.Identity != want.Identity || !slices.Equal(got.Allowed, want.Allowed) {
		t.Fatalf("state changed: %+v %v", got, err)
	}
	if dispatch("reset", request{State: dir}).Error == "" {
		t.Fatal("reset allowed while active")
	}
	release(lock)
	os.WriteFile(filepath.Join(dir, "key.json"), []byte("{}"), 0600)
	if result := dispatch("reset", request{State: dir}); result.Error != "" {
		t.Fatal(result.Error)
	}
	for _, name := range []string{stateFile, "key.json"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Fatalf("%s retained", name)
		}
	}
}

func TestInvalidSavedState(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, stateFile), []byte("nope"), 0600)
	if _, err := loadState(dir); err == nil {
		t.Fatal("invalid state accepted")
	}
	if dispatch("host_start", request{State: dir, Port: 8080}).Error == "" {
		t.Fatal("started from invalid state")
	}
	lock, err := hold(dir)
	if err != nil {
		t.Fatalf("lock leaked: %v", err)
	}
	release(lock)
}

func TestRevokeOfflineEditsAllowedList(t *testing.T) {
	dir := t.TempDir()
	saveState(dir, connecthost.State{Identity: `{"v":1}`, Allowed: []string{"nodekey:a", "nodekey:b"}})
	if r := dispatch("revoke", request{State: dir, Phone: "nodekey:a"}); r.Error != "" {
		t.Fatal(r.Error)
	}
	got, _ := loadState(dir)
	if !slices.Equal(got.Allowed, []string{"nodekey:b"}) || got.Identity != `{"v":1}` {
		t.Fatalf("%+v", got)
	}
	if r := dispatch("revoke", request{State: t.TempDir(), Phone: "nodekey:a"}); r.Error != "" {
		t.Fatal("missing state is not an error")
	}
	lock, _ := hold(dir)
	defer release(lock)
	if dispatch("revoke", request{State: dir, Phone: "nodekey:b"}).Error == "" {
		t.Fatal("edited state of a running host")
	}
}

func TestStopIsBoundedAndRetainsLockUntilClosed(t *testing.T) {
	dir := t.TempDir()
	lock, _ := hold(dir)
	_, cancel := context.WithCancel(context.Background())
	unblock := make(chan struct{})
	tunnel := newTunnel(cancel)
	tunnel.lock = lock
	tunnel.closeNative = func() error { <-unblock; return nil }
	start := time.Now()
	if tunnel.stop() == nil || time.Since(start) > 3*time.Second {
		t.Fatal("stop was not bounded")
	}
	if dispatch("reset", request{State: dir}).Error == "" {
		t.Fatal("released a live identity")
	}
	close(unblock)
	deadline := time.Now().Add(time.Second)
	for {
		f, err := hold(dir)
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
	for name, call := range map[string]func() response{
		"port":    func() response { return dispatch("host_start", request{State: t.TempDir()}) },
		"handle":  func() response { return dispatch("host_pair", request{Handle: 99, Code: "abc"}) },
		"invite":  func() response { return dispatch("pair", request{Invite: "invalid"}) },
		"link":    func() response { return dispatch("forward", request{Link: "invalid"}) },
		"method":  func() response { return dispatch("nope", request{}) },
		"nostate": func() response { return dispatch("reset", request{}) },
	} {
		if call().Error == "" {
			t.Errorf("%s accepted", name)
		}
	}
	if dispatch("stop", request{Handle: 12345}).Error != "" {
		t.Fatal("stop is idempotent")
	}
}

func TestHostStartsWithoutPhonesAndRestartsFromState(t *testing.T) {
	dir := t.TempDir()
	start := func() response { return dispatch("host_start", request{State: dir, Name: "Test", Port: 9}) }
	first := start()
	if first.Error != "" || first.Handle == 0 {
		t.Fatal(first.Error)
	}
	if start().Error == "" {
		t.Fatal("second host for one state")
	}
	if r := dispatch("stop", request{Handle: first.Handle}); r.Error != "" {
		t.Fatal(r.Error)
	}
	if again := start(); again.Error != "" {
		t.Fatal(again.Error)
	} else {
		dispatch("stop", request{Handle: again.Handle})
	}
}

func TestLivePairForwardRevoke(t *testing.T) {
	if os.Getenv("KINUVO_TAILCAT_LIVE") != "1" {
		t.Skip("opt-in public relay test")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/hello", func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "hello") })
	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: mux}
	go server.Serve(backend)
	defer server.Close()

	dir := t.TempDir()
	host := dispatch("host_start", request{State: dir, Name: "Test", Port: backend.Addr().(*net.TCPAddr).Port})
	if host.Error != "" {
		t.Fatal(host.Error)
	}
	pairing := dispatch("host_pair", request{Handle: host.Handle, Code: "code1234", Payload: `{"code":"code1234"}`, TTL: 120})
	if pairing.Error != "" {
		t.Fatal(pairing.Error)
	}
	paired := dispatch("pair", request{Invite: pairing.Invite, Device: "Test phone", Op: 1, TimeoutMs: 60_000})
	if paired.Error != "" || paired.Payload != `{"code":"code1234"}` {
		t.Fatalf("pair: %+v", paired)
	}
	phone := dispatch("host_phone", request{Handle: host.Handle, Code: "code1234"})
	if phone.Phone == "" {
		t.Fatal("phone not bound to the pairing code")
	}
	if dispatch("host_phone", request{Handle: host.Handle, Code: "code1234"}).Phone != "" {
		t.Fatal("binding reusable")
	}
	opened := dispatch("forward", request{Link: paired.Link})
	if opened.Error != "" {
		t.Fatal(opened.Error)
	}
	get := func(port int) (string, error) {
		client := &http.Client{Timeout: 40 * time.Second, Transport: &http.Transport{DisableKeepAlives: true}}
		resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/hello", port))
		if err != nil {
			return "", err
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(resp.Body)
		return string(body), err
	}
	if body, err := get(opened.Port); err != nil || body != "hello" {
		t.Fatalf("forward: %q %v", body, err)
	}
	if r := dispatch("host_revoke", request{Handle: host.Handle, Phone: phone.Phone}); r.Error != "" {
		t.Fatal(r.Error)
	}
	if body, err := get(opened.Port); err == nil && body == "hello" {
		t.Fatal("revoked phone still reaches the Server")
	}
	dispatch("stop", request{Handle: opened.Handle})
	dispatch("stop", request{Handle: host.Handle})
	raw, _ := os.ReadFile(filepath.Join(dir, stateFile))
	var st connecthost.State
	if json.Unmarshal(raw, &st) != nil || len(st.Allowed) != 0 {
		t.Fatalf("state after revoke: %s", raw)
	}
}

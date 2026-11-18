package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"testing"
	"time"

	"github.com/jt-wang/tailpocket/connecthost"
	"tailscale.com/types/key"
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
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0600 {
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

func TestRevokeOfflineKeepsThePhoneAllowedButRevoked(t *testing.T) {
	dir := t.TempDir()
	saveState(dir, connecthost.State{Identity: `{"v":1}`, Allowed: []string{"nodekey:a", "nodekey:b"}})
	for range 2 {
		if r := dispatch("revoke", request{State: dir, Phone: "nodekey:a"}); r.Error != "" {
			t.Fatal(r.Error)
		}
	}
	got, _ := loadState(dir)
	if !slices.Equal(got.Allowed, []string{"nodekey:a", "nodekey:b"}) || got.Identity != `{"v":1}` {
		t.Fatalf("%+v", got)
	}
	if revoked, _ := loadRevoked(dir); !slices.Equal(revoked, []string{"nodekey:a"}) {
		t.Fatalf("revoked: %v", revoked)
	}
	if r := dispatch("revoke", request{State: dir, Phone: "nodekey:unknown"}); r.Error != "" {
		t.Fatal(r.Error)
	}
	if revoked, _ := loadRevoked(dir); len(revoked) != 1 {
		t.Fatalf("unknown phone recorded: %v", revoked)
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

func TestRevocationsSurviveRestartAndResetClearsThem(t *testing.T) {
	dir := t.TempDir()
	if err := saveRevoked(dir, []string{"nodekey:a"}); err != nil {
		t.Fatal(err)
	}
	started := dispatch("host_start", request{State: dir, Name: "Test", Port: 9})
	if started.Error != "" {
		t.Fatal(started.Error)
	}
	host := lookupHost(started.Handle)
	if !host.isRevoked("nodekey:a") || host.isRevoked("nodekey:b") || host.isRevoked("") {
		t.Fatal("revoked phones not loaded")
	}
	if r := dispatch("host_revoke", request{Handle: started.Handle, Phone: "nodekey:b"}); r.Error != "" || host.isRevoked("nodekey:b") {
		t.Fatalf("an unpaired key was recorded: %q", r.Error)
	}
	dispatch("stop", request{Handle: started.Handle})
	os.WriteFile(filepath.Join(dir, revokedFile), []byte("nope"), 0600)
	if dispatch("host_start", request{State: dir, Name: "Test", Port: 9}).Error == "" {
		t.Fatal("started from invalid revocations")
	}
	if r := dispatch("reset", request{State: dir}); r.Error != "" {
		t.Fatal(r.Error)
	}
	if _, err := os.Stat(filepath.Join(dir, revokedFile)); !os.IsNotExist(err) {
		t.Fatal("revocations retained")
	}
}

func TestPruneRemovesOldestOrphanAndKeepsBoundPhones(t *testing.T) {
	allowed := make([]string, connecthost.MaxPhones)
	for i := range allowed {
		allowed[i] = key.NewNode().Public().String()
	}
	var saved connecthost.State
	backend, err := connecthost.New(connecthost.Config{
		Allowed: allowed,
		Save:    func(s connecthost.State) error { saved = s; return nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	host := &hostTunnel{host: backend, revoked: []string{allowed[0]}}
	host.prune(context.Background(), allowed)
	if !slices.Equal(backend.Allowed(), allowed) {
		t.Fatal("pruned a bound phone")
	}

	bound := append(slices.Clone(allowed[:1]), allowed[3:]...)
	host.prune(context.Background(), bound)
	want := slices.Delete(slices.Clone(allowed), 1, 2)
	if !slices.Equal(backend.Allowed(), want) || !slices.Equal(saved.Allowed, want) {
		t.Fatal("pruning did not remove and persist exactly the oldest orphan")
	}
}

type fakeListener struct{ conns chan net.Conn }

func (l fakeListener) Accept() (net.Conn, error) {
	c, ok := <-l.conns
	if !ok {
		return nil, net.ErrClosed
	}
	return c, nil
}
func (l fakeListener) Close() error   { return nil }
func (l fakeListener) Addr() net.Addr { return &net.TCPAddr{} }

func TestRevokedPhoneIsAnsweredAndNeverProxied(t *testing.T) {
	dir := t.TempDir()
	host := &hostTunnel{tunnel: newTunnel(func() {}), state: dir}
	host.revoked = []string{"nodekey:revoked"}
	listener := fakeListener{conns: make(chan net.Conn)}
	host.listener = listener
	host.tunnel.refuse = host.refuseRevoked
	dials := make(chan struct{}, 4)
	backend, backendSide := net.Pipe()
	defer backend.Close()
	go host.accept(func() (net.Conn, error) { dials <- struct{}{}; return backendSide, nil })
	defer close(listener.conns)

	connect := func(phone string) net.Conn {
		client, server := net.Pipe()
		listener.conns <- &connecthost.Conn{Conn: server, Phone: phone}
		return client
	}

	web := connect("nodekey:revoked")
	web.SetDeadline(time.Now().Add(5 * time.Second))
	go io.WriteString(web, "GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
	reader := bufio.NewReader(web)
	resp, err := http.ReadResponse(reader, nil)
	if err != nil || resp.StatusCode != 101 || resp.Header.Get("Sec-WebSocket-Accept") != "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" {
		t.Fatalf("handshake: %v %+v", err, resp)
	}
	request := []byte(`{"type":"req","id":"7","method":"connect","params":{}}`)
	mask := []byte{1, 2, 3, 4}
	masked := make([]byte, len(request))
	for i := range request {
		masked[i] = request[i] ^ mask[i%4]
	}
	go web.Write(append(append([]byte{0x81, 0x80 | byte(len(request))}, mask...), masked...))
	opcode, payload, ok := readServerFrame(reader)
	if !ok || opcode != 1 {
		t.Fatalf("frame: %d %v", opcode, ok)
	}
	var answer struct {
		Type, ID string
		Ok       bool
		Error    struct{ Code, Message string }
	}
	if json.Unmarshal(payload, &answer) != nil || answer.Type != "res" || answer.ID != "7" || answer.Ok || answer.Error.Code != "forbidden" || answer.Error.Message != revokedMessage {
		t.Fatalf("answer: %s", payload)
	}
	if opcode, _, ok = readServerFrame(reader); !ok || opcode != 8 {
		t.Fatal("not closed after the answer")
	}

	plain := connect("nodekey:revoked")
	plain.SetDeadline(time.Now().Add(5 * time.Second))
	go io.WriteString(plain, "GET /api HTTP/1.1\r\nHost: x\r\n\r\n")
	if resp, err = http.ReadResponse(bufio.NewReader(plain), nil); err != nil || resp.StatusCode != 401 {
		t.Fatalf("plain request: %v %+v", err, resp)
	}

	select {
	case <-dials:
		t.Fatal("revoked phone reached the Server")
	default:
	}

	allowed := connect("nodekey:ok")
	select {
	case <-dials:
	case <-time.After(5 * time.Second):
		t.Fatal("paired phone not proxied")
	}
	go io.WriteString(allowed, "ping")
	buf := make([]byte, 4)
	backend.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(backend, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("proxy: %q %v", buf, err)
	}

	host.closePhone("nodekey:ok")
	allowed.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := allowed.Read(buf); err == nil {
		t.Fatal("connection of a newly revoked phone stayed open")
	}
	backend.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := backend.Read(buf); err == nil {
		t.Fatal("Server side of a newly revoked phone stayed open")
	}
}

func TestRevokingPhoneLetsItsOpenBridgeDeliverTheReply(t *testing.T) {
	phone := key.NewNode().Public().String()
	native, err := connecthost.New(connecthost.Config{
		Allowed: []string{phone},
		Save:    func(connecthost.State) error { return nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	defer native.Close()
	host := &hostTunnel{tunnel: newTunnel(func() {}), host: native, state: t.TempDir()}
	listener := fakeListener{conns: make(chan net.Conn)}
	defer close(listener.conns)
	host.listener = listener
	host.refuse = host.refuseRevoked
	backend, bridge := net.Pipe()
	defer backend.Close()
	backend.SetDeadline(time.Now().Add(5 * time.Second))
	go host.accept(func() (net.Conn, error) { return bridge, nil })

	connect := func() net.Conn {
		client, peer := net.Pipe()
		client.SetDeadline(time.Now().Add(5 * time.Second))
		listener.conns <- &connecthost.Conn{Conn: peer, Phone: phone}
		return client
	}
	client := connect()
	defer client.Close()
	go io.WriteString(client, "revoke")
	request := make([]byte, len("revoke"))
	if _, err := io.ReadFull(backend, request); err != nil || string(request) != "revoke" {
		t.Fatalf("request: %q %v", request, err)
	}
	if err := host.revoke(phone); err != nil {
		t.Fatal(err)
	}

	go io.WriteString(backend, "ok")
	reply := make([]byte, len("ok"))
	if _, err := io.ReadFull(client, reply); err != nil || string(reply) != "ok" {
		t.Fatalf("reply: %q %v", reply, err)
	}

	fresh := connect()
	defer fresh.Close()
	go io.WriteString(fresh, "GET /api HTTP/1.1\r\nHost: x\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(fresh), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("new connection was not refused: %d", resp.StatusCode)
	}
	if _, err := client.Read(reply); err != io.EOF {
		t.Fatalf("expected the old bridge to close after the reply: %v", err)
	}
}

func readServerFrame(r io.Reader) (byte, []byte, bool) {
	var head [2]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return 0, nil, false
	}
	payload := make([]byte, head[1]&0x7f)
	_, err := io.ReadFull(r, payload)
	return head[0] & 0x0f, payload, err == nil
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
	client := &http.Client{Timeout: 40 * time.Second, Transport: &http.Transport{DisableKeepAlives: true}}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/hello", opened.Port))
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("revoked phone is not refused with a 401: %v %v", resp, err)
	}
	resp.Body.Close()
	ws, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", opened.Port))
	if err != nil {
		t.Fatal(err)
	}
	ws.SetDeadline(time.Now().Add(40 * time.Second))
	io.WriteString(ws, "GET /ws HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
	wsReader := bufio.NewReader(ws)
	upgraded, err := http.ReadResponse(wsReader, nil)
	if err != nil || upgraded.StatusCode != 101 {
		t.Fatalf("revoked websocket upgrade: %v %v", upgraded, err)
	}
	ws.Close()
	dispatch("stop", request{Handle: opened.Handle})
	dispatch("stop", request{Handle: host.Handle})
	raw, _ := os.ReadFile(filepath.Join(dir, stateFile))
	var st connecthost.State
	if json.Unmarshal(raw, &st) != nil || !slices.Equal(st.Allowed, []string{phone.Phone}) {
		t.Fatalf("state after revoke: %s", raw)
	}
	if revoked, _ := loadRevoked(dir); !slices.Equal(revoked, []string{phone.Phone}) {
		t.Fatalf("revoked after revoke: %v", revoked)
	}
}

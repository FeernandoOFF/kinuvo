package connectlocal

import (
	"bufio"
	"context"
	"errors"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func loopback() []Binding {
	return []Binding{{IP: net.IPv4(127, 0, 0, 1).To4(), Mask: net.CIDRMask(8, 32)}}
}

type served struct {
	port chan int
	err  chan error
}

func serve(t *testing.T, ctx context.Context, opts HostOptions) served {
	t.Helper()
	s := served{port: make(chan int, 1), err: make(chan error, 1)}
	if opts.Bindings == nil {
		opts.Bindings = loopback()
	}
	if opts.Label == "" {
		opts.Label = DefaultLabel
	}
	if opts.Expires.IsZero() {
		opts.Expires = time.Now().Add(10 * time.Second)
	}
	opts.OnListening = func(port int) { s.port <- port }
	go func() { s.err <- Serve(ctx, opts) }()
	return s
}

func (s served) addr(t *testing.T) string {
	t.Helper()
	select {
	case p := <-s.port:
		return net.JoinHostPort("127.0.0.1", itoa(p))
	case err := <-s.err:
		t.Fatalf("serve ended: %v", err)
	case <-time.After(5 * time.Second):
		t.Fatal("serve did not listen")
	}
	return ""
}

func TestApprovedPairingDeliversPlaintextAndCodesMatch(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var hostCode, hostDevice atomic.Value
	s := serve(t, ctx, HostOptions{Plaintext: []byte(`{"v":1}`), Confirm: func(_ context.Context, code, device string) bool {
		hostCode.Store(code)
		hostDevice.Store(device)
		return true
	}})
	var phoneCode string
	got, err := Pair(ctx, s.addr(t), PhoneOptions{Label: DefaultLabel, Device: " Pixel\n9 ", OnCode: func(c string) { phoneCode = c }})
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != `{"v":1}` {
		t.Fatalf("plaintext %q", got)
	}
	if phoneCode == "" || phoneCode != hostCode.Load() {
		t.Fatalf("codes %q %v", phoneCode, hostCode.Load())
	}
	if hostDevice.Load() != "Pixel9" {
		t.Fatalf("device %q", hostDevice.Load())
	}
	if err := <-s.err; err != nil {
		t.Fatalf("Serve after approval: %v", err)
	}
}

func TestDeniedPhoneKeepsWindowOpenForTheNext(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var calls atomic.Int32
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), Confirm: func(context.Context, string, string) bool {
		return calls.Add(1) > 1
	}})
	addr := s.addr(t)
	if _, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "first"}); !errors.Is(err, ErrDenied) {
		t.Fatalf("first: %v", err)
	}
	if got, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "second"}); err != nil || string(got) != "ok" {
		t.Fatalf("second: %q %v", got, err)
	}
}

func TestBusyWhileAnotherPhoneWaits(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	release := make(chan struct{})
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), Confirm: func(ctx context.Context, _, _ string) bool {
		select {
		case <-release:
		case <-ctx.Done():
		}
		return true
	}})
	addr := s.addr(t)
	firstCode := make(chan struct{})
	first := make(chan error, 1)
	go func() {
		_, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "a", OnCode: func(string) { close(firstCode) }})
		first <- err
	}()
	<-firstCode
	if _, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "b"}); !errors.Is(err, ErrDenied) {
		t.Fatalf("second while busy: %v", err)
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
}

func TestExpiredWindow(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), Expires: time.Now().Add(300 * time.Millisecond),
		Confirm: func(ctx context.Context, _, _ string) bool { <-ctx.Done(); return true }})
	addr := s.addr(t)
	if _, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "slow"}); !errors.Is(err, ErrExpired) && !errors.Is(err, ErrDenied) && !errors.Is(err, ErrFailed) {
		t.Fatalf("got %v", err)
	}
	if err := <-s.err; !errors.Is(err, ErrNotApproved) {
		t.Fatalf("Serve: %v", err)
	}
}

func TestLabelMismatchFailsOnThePhone(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), Confirm: func(context.Context, string, string) bool { return true }})
	if _, err := Pair(ctx, s.addr(t), PhoneOptions{Label: "other-app-v1", Device: "x"}); !errors.Is(err, ErrFailed) {
		t.Fatalf("got %v", err)
	}
}

func TestSilentPeerIsDroppedAndOversizedLineRejected(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), HelloTimeout: 200 * time.Millisecond,
		Confirm: func(context.Context, string, string) bool { return true }})
	addr := s.addr(t)
	silent, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	_ = silent.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := bufio.NewReader(silent).ReadString('\n'); err == nil {
		t.Fatal("silent peer got a reply instead of a drop")
	}
	silent.Close()
	big, _ := net.Dial("tcp", addr)
	_, _ = big.Write([]byte(strings.Repeat("x", MaxLine+10) + "\n"))
	_ = big.SetReadDeadline(time.Now().Add(3 * time.Second))
	line, _ := bufio.NewReader(big).ReadString('\n')
	if strings.Contains(line, "publicKey") {
		t.Fatalf("oversized hello answered: %q", line)
	}
	big.Close()
	// The slot is free again: a real phone still pairs.
	if _, err := Pair(ctx, addr, PhoneOptions{Label: DefaultLabel, Device: "x"}); err != nil {
		t.Fatal(err)
	}
}

func TestServeStopsOnContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	s := serve(t, ctx, HostOptions{Plaintext: []byte("ok"), Confirm: func(context.Context, string, string) bool { return true }})
	s.addr(t)
	cancel()
	select {
	case err := <-s.err:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Serve did not stop")
	}
}

func TestServeRejectsNoBindings(t *testing.T) {
	err := Serve(context.Background(), HostOptions{Label: DefaultLabel, Plaintext: []byte("x"), Expires: time.Now().Add(time.Second),
		Bindings: []Binding{}, Confirm: func(context.Context, string, string) bool { return true }})
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("got %v", err)
	}
}

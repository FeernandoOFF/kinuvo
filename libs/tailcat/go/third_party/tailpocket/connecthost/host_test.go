package connecthost

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"

	"github.com/jt-wang/tailpocket/connectinvite"
	"github.com/jt-wang/tailpocket/connectlocal"
	"github.com/jt-wang/tailpocket/connectmdns"
)

// fakeNode stands in for a Tailcat server: tests hand it connections directly.
type fakeNode struct {
	spec    nodeSpec
	addr    string
	mu      sync.Mutex
	allowed []key.NodePublic
	closed  bool
	conns   []net.Conn // host sides of dialed connections; closing the node closes them, as Tailcat does
}

func (n *fakeNode) Address() string             { return n.addr }
func (n *fakeNode) Region() *tailcfg.DERPRegion { return n.spec.region }
func (n *fakeNode) Allow(k key.NodePublic) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.allowed = append(n.allowed, k)
}
func (n *fakeNode) Close() error {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.closed = true
	for _, c := range n.conns {
		_ = c.Close()
	}
	return nil
}

// dial opens a connection from phone to the node's main port, as Tailcat would: refused while the
// node is closed or the key was never allowed, otherwise handed to the host's OnTCP handler.
// It returns the phone's side.
func (n *fakeNode) dial(t *testing.T, phone string) net.Conn {
	t.Helper()
	var k key.NodePublic
	if err := k.UnmarshalText([]byte(phone)); err != nil {
		t.Fatal(err)
	}
	n.mu.Lock()
	if n.closed || !slices.Contains(n.allowed, k) {
		n.mu.Unlock()
		return nil
	}
	c, s := net.Pipe()
	n.conns = append(n.conns, s)
	n.mu.Unlock()
	go n.spec.onTCP(n.spec.port)(&addrConn{Conn: s, remote: &net.TCPAddr{IP: clientIP(k).AsSlice(), Port: 40000}})
	return c
}
func (n *fakeNode) isClosed() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.closed
}
func (n *fakeNode) allows(k string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, a := range n.allowed {
		if a.String() == k {
			return true
		}
	}
	return false
}

type fakeNet struct {
	mu    sync.Mutex
	nodes []*fakeNode
	fail  bool
}

// discoFor mirrors Tailcat, which derives the disco key from the node key: same key, same address.
var discoKeys sync.Map

func discoFor(k key.NodePrivate) key.DiscoPublic {
	v, _ := discoKeys.LoadOrStore(k.Public(), key.NewDisco().Public())
	return v.(key.DiscoPublic)
}

func (f *fakeNet) start(ctx context.Context, s nodeSpec) (node, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail {
		return nil, errors.New("start failed")
	}
	if s.region == nil {
		s.region = &tailcfg.DERPRegion{RegionID: 9, Nodes: []*tailcfg.DERPNode{{Name: "9a", RegionID: 9, HostName: "derp9.example.com"}}}
	}
	ci := tailcat.ConnInfo{ServerPublic: tailcat.NodePublic{NodePublic: s.key.Public()},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: discoFor(s.key)}, PresharedKey: s.psk, Region: []*tailcfg.DERPRegion{s.region}}
	n := &fakeNode{spec: s, addr: string(ci.Addr()), allowed: append([]key.NodePublic{}, s.allowed...)}
	f.nodes = append(f.nodes, n)
	return n, nil
}

func (f *fakeNet) byPort(port uint16) []*fakeNode {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []*fakeNode
	for _, n := range f.nodes {
		if n.spec.port == port {
			out = append(out, n)
		}
	}
	return out
}

func (f *fakeNet) live(port uint16) *fakeNode {
	var last *fakeNode
	for _, n := range f.byPort(port) {
		if !n.isClosed() {
			last = n
		}
	}
	return last
}

type saved struct {
	mu     sync.Mutex
	states []State
	fail   bool
}

func (s *saved) save(st State) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fail {
		return errors.New("disk full")
	}
	s.states = append(s.states, st)
	return nil
}

func (s *saved) last() State {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.states) == 0 {
		return State{}
	}
	return s.states[len(s.states)-1]
}

func newTestHost(t *testing.T, cfg Config) (*Host, *fakeNet, *saved) {
	t.Helper()
	f, s := &fakeNet{}, &saved{}
	if cfg.Save == nil {
		cfg.Save = s.save
	}
	if cfg.Name == "" {
		cfg.Name = "Studio Mac"
	}
	h, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	h.start = f.start
	if err := h.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = h.Close() })
	return h, f, s
}

func phoneKey(t *testing.T) (key.NodePrivate, string) {
	t.Helper()
	k := key.NewNode()
	return k, k.Public().String()
}

// client speaks HTTP to a fake node by handing it one side of a pipe per request.
func client(n *fakeNode, port uint16) *http.Client {
	return &http.Client{Timeout: 5 * time.Second, Transport: &http.Transport{DisableKeepAlives: true,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			handler := n.spec.onTCP(port)
			if handler == nil {
				return nil, errors.New("port not served")
			}
			c, s := net.Pipe()
			go handler(s)
			return c, nil
		}}}
}

func postPair(t *testing.T, n *fakeNode, inv connectinvite.Invite, proof, phone, device string) (int, pairReply) {
	t.Helper()
	body := fmt.Sprintf(`{"phoneKey":%q,"device":%q}`, phone, device)
	req, _ := http.NewRequest(http.MethodPost, "http://pairing.invalid/pair", strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+proof)
	resp, err := client(n, uint16(inv.Port)).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var r pairReply
	_ = json.NewDecoder(resp.Body).Decode(&r)
	return resp.StatusCode, r
}

func TestNewValidates(t *testing.T) {
	ok := func(State) error { return nil }
	_, phone := phoneKey(t)
	for name, cfg := range map[string]Config{
		"no save":       {Name: "x"},
		"bad identity":  {Name: "x", Save: ok, Identity: "nope"},
		"bad phone key": {Name: "x", Save: ok, Allowed: []string{"nodekey:zz"}},
		"duplicate":     {Name: "x", Save: ok, Allowed: []string{phone, phone}},
		"same ports":    {Name: "x", Save: ok, Port: 5000, PairingPort: 5000},
	} {
		if _, err := New(cfg); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestNoPhonesMeansNoMainNode(t *testing.T) {
	h, f, _ := newTestHost(t, Config{})
	if h.Address() != "" || len(f.byPort(DefaultPort)) != 0 {
		t.Fatal("main node started without an allowed phone (Tailcat would admit everyone)")
	}
}

func TestPairOverQRPath(t *testing.T) {
	h, f, s := newTestHost(t, Config{})
	p, err := h.Pair(context.Background(), PairOptions{TTL: time.Minute, Payload: func(ph Phone) (string, error) {
		return `{"token":"t-` + ph.Device + `"}`, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	inv := p.Invite()
	if err := inv.Validate(); err != nil {
		t.Fatal(err)
	}
	if inv.Port != DefaultPairingPort || inv.Name != "Studio Mac" || time.Until(time.Unix(inv.ExpiresAt, 0)) > time.Minute {
		t.Fatalf("invite %+v", inv)
	}
	qr, err := p.QR(connectinvite.DefaultPrefix)
	if err != nil || !strings.HasPrefix(qr, connectinvite.DefaultPrefix) {
		t.Fatalf("qr %q %v", qr, err)
	}
	pn := f.live(DefaultPairingPort)
	var inviteKey key.NodePrivate
	_ = inviteKey.UnmarshalText([]byte(inv.Key))
	if pn == nil || len(pn.spec.allowed) != 1 || pn.spec.allowed[0] != inviteKey.Public() {
		t.Fatal("pairing node must admit only the invite's one-time key")
	}

	_, phone := phoneKey(t)
	if code, _ := postPair(t, pn, inv, "wrong-proof", phone, "Pixel"); code != http.StatusUnauthorized {
		t.Fatalf("wrong proof: %d", code)
	}
	code, reply := postPair(t, pn, inv, inv.Proof, phone, "Pixel")
	if code != http.StatusOK {
		t.Fatalf("pair: %d", code)
	}
	if reply.V != 1 || reply.Address == "" || reply.Address != h.Address() || reply.Port != DefaultPort || reply.Name != "Studio Mac" || reply.Payload != `{"token":"t-Pixel"}` {
		t.Fatalf("reply %+v", reply)
	}
	got, err := p.Wait(context.Background())
	if err != nil || got.Key != phone || got.Device != "Pixel" {
		t.Fatalf("wait %+v %v", got, err)
	}
	if st := s.last(); len(st.Allowed) != 1 || st.Allowed[0] != phone || st.Identity == "" {
		t.Fatalf("saved %+v", st)
	}
	if main := f.live(DefaultPort); main == nil || !main.allows(phone) {
		t.Fatal("main node does not admit the phone")
	}
	// A retry with the same key is answered again; a second phone is refused.
	if code, again := postPair(t, pn, inv, inv.Proof, phone, "Pixel"); code != http.StatusOK || again.Address != reply.Address {
		t.Fatalf("retry: %d", code)
	}
	_, other := phoneKey(t)
	if code, _ := postPair(t, pn, inv, inv.Proof, other, "Other"); code != http.StatusConflict {
		t.Fatalf("second phone: %d", code)
	}
}

func TestPairRequestShapeErrors(t *testing.T) {
	h, f, _ := newTestHost(t, Config{})
	p, _ := h.Pair(context.Background(), PairOptions{})
	inv, pn := p.Invite(), f.live(DefaultPairingPort)
	c := client(pn, uint16(inv.Port))
	do := func(method, path, auth, body string) int {
		req, _ := http.NewRequest(method, "http://pairing.invalid"+path, strings.NewReader(body))
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		resp, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		return resp.StatusCode
	}
	bearer := "Bearer " + inv.Proof
	if got := do("GET", "/pair", bearer, ""); got != http.StatusMethodNotAllowed {
		t.Errorf("GET: %d", got)
	}
	if got := do("POST", "/other", bearer, "{}"); got != http.StatusNotFound {
		t.Errorf("path: %d", got)
	}
	if got := do("POST", "/pair", bearer, `{"phoneKey":"nodekey:00"}`); got != http.StatusBadRequest {
		t.Errorf("bad key: %d", got)
	}
	if got := do("POST", "/pair", bearer, strings.Repeat("x", 5000)); got != http.StatusRequestEntityTooLarge {
		t.Errorf("large: %d", got)
	}
}

func TestExpiredPairingWindow(t *testing.T) {
	h, f, _ := newTestHost(t, Config{})
	p, _ := h.Pair(context.Background(), PairOptions{TTL: 50 * time.Millisecond})
	pn := f.live(DefaultPairingPort)
	time.Sleep(120 * time.Millisecond)
	if _, err := p.Wait(context.Background()); !errors.Is(err, ErrExpired) {
		t.Fatalf("wait: %v", err)
	}
	if !pn.isClosed() {
		t.Fatal("pairing node still open after expiry")
	}
}

func TestSaveFailureRefusesThePhone(t *testing.T) {
	s := &saved{fail: true}
	h, f, _ := newTestHost(t, Config{Save: s.save})
	p, _ := h.Pair(context.Background(), PairOptions{})
	_, phone := phoneKey(t)
	if code, _ := postPair(t, f.live(DefaultPairingPort), p.Invite(), p.Invite().Proof, phone, "x"); code != http.StatusServiceUnavailable {
		t.Fatalf("code %d", code)
	}
	if len(h.Allowed()) != 0 || f.live(DefaultPort) != nil {
		t.Fatal("allowlist changed although saving failed")
	}
}

func TestSecondPhoneJoinsAndRevokeWithoutRestart(t *testing.T) {
	_, a := phoneKey(t)
	h, f, s := newTestHost(t, Config{Allowed: []string{a}})
	first := f.live(DefaultPort)
	if first == nil {
		t.Fatal("main node not started for a known phone")
	}
	addr := h.Address()
	p, _ := h.Pair(context.Background(), PairOptions{})
	_, b := phoneKey(t)
	if code, _ := postPair(t, f.live(DefaultPairingPort), p.Invite(), p.Invite().Proof, b, "b"); code != 200 {
		t.Fatalf("pair b: %d", code)
	}
	if f.live(DefaultPort) != first || !first.allows(b) {
		t.Fatal("adding a phone restarted the main node (drops other phones' connections)")
	}
	if err := h.Revoke(context.Background(), a); err != nil {
		t.Fatal(err)
	}
	if f.live(DefaultPort) != first || first.isClosed() {
		t.Fatal("revoking one of two phones restarted the main node")
	}
	if h.Address() != addr {
		t.Fatal("address changed")
	}
	if st := s.last(); len(st.Allowed) != 1 || st.Allowed[0] != b {
		t.Fatalf("saved %+v", st)
	}
	if err := h.Revoke(context.Background(), b); err != nil {
		t.Fatal(err)
	}
	if f.live(DefaultPort) != nil || h.Address() != "" {
		t.Fatal("main node still up with no phones")
	}
}

func TestIdentitySurvivesRestart(t *testing.T) {
	_, a := phoneKey(t)
	h1, _, s := newTestHost(t, Config{Allowed: []string{a}})
	addr := h1.Address()
	st := s.last()
	h1.Close()
	h2, _, _ := newTestHost(t, Config{Identity: st.Identity, Allowed: st.Allowed})
	if h2.Address() != addr {
		t.Fatalf("address changed:\n%s\n%s", addr, h2.Address())
	}
}

func TestConnectionsReachTheListenerWithThePhoneKey(t *testing.T) {
	_, a := phoneKey(t)
	h, f, _ := newTestHost(t, Config{Allowed: []string{a}})
	main := f.live(DefaultPort)
	if main.spec.onTCP(DefaultPairingPort) != nil || main.spec.onTCP(22) != nil {
		t.Fatal("main node serves ports other than its own")
	}
	c, s := net.Pipe()
	var pub key.NodePublic
	_ = pub.UnmarshalText([]byte(a))
	go main.spec.onTCP(DefaultPort)(&addrConn{Conn: s, remote: &net.TCPAddr{IP: clientIP(pub).AsSlice(), Port: 40000}})
	conn, err := h.Listener().Accept()
	if err != nil {
		t.Fatal(err)
	}
	if PhoneOf(conn) != a {
		t.Fatalf("PhoneOf = %q", PhoneOf(conn))
	}
	go c.Write([]byte("ping"))
	buf := make([]byte, 4)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("%q %v", buf, err)
	}
	conn.Close()
	h.Close()
	if _, err := h.Listener().Accept(); err == nil {
		t.Fatal("Accept after Close")
	}
}

type addrConn struct {
	net.Conn
	remote net.Addr
}

func (a *addrConn) RemoteAddr() net.Addr { return a.remote }

func TestLocalPairingDeliversTheInvite(t *testing.T) {
	h, _, _ := newTestHost(t, Config{})
	p, _ := h.Pair(context.Background(), PairOptions{})
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	advertised := make(chan connectmdns.Service, 1)
	stopped := make(chan struct{})
	var hostCode string
	errc := make(chan error, 1)
	go func() {
		errc <- p.ServeLocal(ctx, LocalOptions{Service: "_tailpocket-demo._tcp",
			Bindings: []connectlocal.Binding{{IP: net.IPv4(127, 0, 0, 1).To4(), Mask: net.CIDRMask(8, 32)}},
			Advertise: func(s connectmdns.Service) (func(), error) {
				advertised <- s
				return func() { close(stopped) }, nil
			},
			Confirm: func(_ context.Context, code, device string) bool { hostCode = code; return device == "Pixel" }})
	}()
	var svc connectmdns.Service
	select {
	case svc = <-advertised:
	case err := <-errc:
		t.Fatal(err)
	}
	if svc.Type != "_tailpocket-demo._tcp" || svc.Instance != "Studio Mac" || svc.TXT[0] != "v=1" {
		t.Fatalf("service %+v", svc)
	}
	var phoneCode string
	plain, err := connectlocal.Pair(ctx, fmt.Sprintf("127.0.0.1:%d", svc.Port), connectlocal.PhoneOptions{Label: connectlocal.DefaultLabel, Device: "Pixel",
		OnCode: func(c string) { phoneCode = c }})
	if err != nil {
		t.Fatal(err)
	}
	got, err := connectinvite.ParseJSON(plain)
	if err != nil || got != p.Invite() {
		t.Fatalf("delivered %+v %v", got, err)
	}
	if phoneCode != hostCode {
		t.Fatal("codes differ")
	}
	if err := <-errc; err != nil {
		t.Fatal(err)
	}
	<-stopped
}

// accept takes the next connection from the host's listener, failing after a few seconds.
func accept(t *testing.T, h *Host) net.Conn {
	t.Helper()
	type res struct {
		c   net.Conn
		err error
	}
	ch := make(chan res, 1)
	go func() { c, err := h.Listener().Accept(); ch <- res{c, err} }()
	select {
	case r := <-ch:
		if r.err != nil {
			t.Fatal(r.err)
		}
		return r.c
	case <-time.After(3 * time.Second):
		t.Fatal("no connection reached the listener")
		return nil
	}
}

// open reports whether bytes still flow from the phone's side to the app's side.
func open(phoneSide, appSide net.Conn) bool {
	go func() {
		_ = phoneSide.SetWriteDeadline(time.Now().Add(time.Second))
		_, _ = phoneSide.Write([]byte("x"))
	}()
	_ = appSide.SetReadDeadline(time.Now().Add(time.Second))
	defer appSide.SetReadDeadline(time.Time{})
	_, err := io.ReadFull(appSide, make([]byte, 1))
	return err == nil
}

// closedByHost reports whether the host closed the phone's side: a read ends at once instead of waiting.
func closedByHost(phoneSide net.Conn) bool {
	_ = phoneSide.SetReadDeadline(time.Now().Add(time.Second))
	_, err := phoneSide.Read(make([]byte, 1))
	return err != nil && !errors.Is(err, os.ErrDeadlineExceeded)
}

func TestRevokeClosesOnlyThatPhone(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, s := newTestHost(t, Config{Allowed: []string{a, b}})
	main := f.live(DefaultPort)
	phoneA := main.dial(t, a)
	appA := accept(t, h)
	phoneB := main.dial(t, b)
	appB := accept(t, h)
	if PhoneOf(appA) != a || PhoneOf(appB) != b {
		t.Fatalf("PhoneOf = %q, %q", PhoneOf(appA), PhoneOf(appB))
	}

	if err := h.Revoke(context.Background(), a); err != nil {
		t.Fatal(err)
	}
	if !open(phoneB, appB) {
		t.Fatal("revoking a dropped the other phone's connection")
	}
	if f.live(DefaultPort) != main {
		t.Fatal("revoke restarted the main node")
	}
	if !closedByHost(phoneA) {
		t.Fatal("the revoked phone's connection is still open")
	}
	if st := s.last(); len(st.Allowed) != 1 || st.Allowed[0] != b {
		t.Fatalf("saved %+v", st)
	}
	if got := h.Allowed(); len(got) != 1 || got[0] != b {
		t.Fatalf("Allowed = %v", got)
	}

	// Stays refused: Tailcat still knows the key, so the host must close the new connection itself.
	if again := main.dial(t, a); again == nil || !closedByHost(again) {
		t.Fatal("the revoked phone opened a new connection")
	}
	// The listener hands the app b's next connection, never a's.
	phoneB2 := main.dial(t, b)
	if appB2 := accept(t, h); PhoneOf(appB2) != b || !open(phoneB2, appB2) {
		t.Fatalf("next connection from %q", PhoneOf(appB2))
	}
}

func TestRevokeRefusesAConnectionAlreadyBeingDialed(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, _ := newTestHost(t, Config{Allowed: []string{a, b}})
	main := f.live(DefaultPort)
	// a's connection has reached the host but the app has not accepted it yet.
	pending := main.dial(t, a)
	time.Sleep(50 * time.Millisecond)
	if err := h.Revoke(context.Background(), a); err != nil {
		t.Fatal(err)
	}
	if !closedByHost(pending) {
		t.Fatal("a connection dialed before the revoke stayed open")
	}
	phoneB := main.dial(t, b)
	if app := accept(t, h); PhoneOf(app) != b || !open(phoneB, app) {
		t.Fatalf("the listener handed over a connection from %q", PhoneOf(app))
	}
}

func TestRevokeRacingDials(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, _ := newTestHost(t, Config{Allowed: []string{a, b}})
	main := f.live(DefaultPort)
	var accepted sync.Map // app-side conns from a
	go func() {
		for {
			c, err := h.Listener().Accept()
			if err != nil {
				return
			}
			if PhoneOf(c) == a {
				accepted.Store(c, true)
			}
		}
	}()
	stop := make(chan struct{})
	var wg sync.WaitGroup
	var mu sync.Mutex
	var phoneSides []net.Conn
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				if c := main.dial(t, a); c != nil {
					mu.Lock()
					phoneSides = append(phoneSides, c)
					mu.Unlock()
				}
				time.Sleep(time.Millisecond)
			}
		}()
	}
	time.Sleep(20 * time.Millisecond)
	if err := h.Revoke(context.Background(), a); err != nil {
		t.Fatal(err)
	}
	close(stop)
	wg.Wait()
	time.Sleep(100 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if len(phoneSides) == 0 {
		t.Fatal("no dials ran")
	}
	for _, c := range phoneSides {
		if !closedByHost(c) {
			t.Fatal("a connection from the revoked phone is still open")
		}
	}
}

func TestRevokeSaveFailureChangesNothing(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, s := newTestHost(t, Config{Allowed: []string{a, b}})
	main := f.live(DefaultPort)
	phoneA := main.dial(t, a)
	appA := accept(t, h)
	s.mu.Lock()
	s.fail = true
	s.mu.Unlock()
	if err := h.Revoke(context.Background(), a); err == nil {
		t.Fatal("revoke succeeded although saving failed")
	}
	if len(h.Allowed()) != 2 || !open(phoneA, appA) {
		t.Fatal("a failed revoke still removed the phone")
	}
}

func TestRevokedPhoneCanPairAgain(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, _ := newTestHost(t, Config{Allowed: []string{a, b}})
	if err := h.Revoke(context.Background(), a); err != nil {
		t.Fatal(err)
	}
	p, _ := h.Pair(context.Background(), PairOptions{})
	if code, _ := postPair(t, f.live(DefaultPairingPort), p.Invite(), p.Invite().Proof, a, "a"); code != 200 {
		t.Fatalf("pair a again: %d", code)
	}
	phoneA := f.live(DefaultPort).dial(t, a)
	if app := accept(t, h); PhoneOf(app) != a || !open(phoneA, app) {
		t.Fatal("the re-paired phone cannot connect")
	}
}

func TestSetAllowedClosesRemovedPhones(t *testing.T) {
	_, a := phoneKey(t)
	_, b := phoneKey(t)
	h, f, _ := newTestHost(t, Config{Allowed: []string{a, b}})
	phoneA := f.live(DefaultPort).dial(t, a)
	accept(t, h)
	if err := h.SetAllowed(context.Background(), []string{b}); err != nil {
		t.Fatal(err)
	}
	if !closedByHost(phoneA) || f.live(DefaultPort).dial(t, a) != nil {
		t.Fatal("SetAllowed left the removed phone connected")
	}
	phoneB := f.live(DefaultPort).dial(t, b)
	if app := accept(t, h); PhoneOf(app) != b || !open(phoneB, app) {
		t.Fatal("the kept phone cannot connect after SetAllowed")
	}
}

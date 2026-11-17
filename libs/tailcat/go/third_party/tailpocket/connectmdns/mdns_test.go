package connectmdns

import (
	"bufio"
	"context"
	"errors"
	"net"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func testService() Service {
	return Service{Instance: "Studio Mac", Type: "_tailpocket-demo._tcp", Port: 40123, TXT: []string{"v=1"}}
}

func question(t *testing.T, name string, typ dnsmessage.Type) dnsmessage.Question {
	t.Helper()
	return dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: typ, Class: dnsmessage.ClassINET}
}

func TestServiceValidation(t *testing.T) {
	good := testService()
	if err := good.validate(); err != nil {
		t.Fatal(err)
	}
	for _, typ := range []string{"", "tailpocket-demo._tcp", "_tailpocket-demo", "_tailpocket-demo._udp", "_has space._tcp", "_waytoolongservicename._tcp"} {
		s := good
		s.Type = typ
		if s.validate() == nil {
			t.Errorf("type %q accepted", typ)
		}
	}
	for _, inst := range []string{"", "a.b", "line\nbreak", strings.Repeat("x", 64)} {
		s := good
		s.Instance = inst
		if s.validate() == nil {
			t.Errorf("instance %q accepted", inst)
		}
	}
	s := good
	s.Port = 0
	if s.validate() == nil {
		t.Error("port 0 accepted")
	}
}

func TestAnswersForBrowseQuery(t *testing.T) {
	r := newRecords(testService(), "studio-mac-1a2b.local.")
	ips := []net.IP{net.IPv4(192, 168, 1, 20)}
	answers, extra := r.answer(question(t, "_tailpocket-demo._tcp.local.", dnsmessage.TypePTR), ips)
	if len(answers) != 1 {
		t.Fatalf("answers %v", answers)
	}
	ptr, ok := answers[0].Body.(*dnsmessage.PTRResource)
	if !ok || ptr.PTR.String() != "Studio Mac._tailpocket-demo._tcp.local." {
		t.Fatalf("ptr %+v", answers[0].Body)
	}
	var srv, txt, a bool
	for _, rr := range extra {
		switch b := rr.Body.(type) {
		case *dnsmessage.SRVResource:
			srv = b.Port == 40123 && b.Target.String() == "studio-mac-1a2b.local."
		case *dnsmessage.TXTResource:
			txt = len(b.TXT) == 1 && b.TXT[0] == "v=1"
		case *dnsmessage.AResource:
			a = net.IP(b.A[:]).Equal(ips[0])
		}
	}
	if !srv || !txt || !a {
		t.Fatalf("additional srv=%v txt=%v a=%v", srv, txt, a)
	}
}

func TestAnswersForOtherQuestions(t *testing.T) {
	r := newRecords(testService(), "studio-mac-1a2b.local.")
	ips := []net.IP{net.IPv4(10, 0, 0, 2)}
	if a, _ := r.answer(question(t, "_services._dns-sd._udp.local.", dnsmessage.TypePTR), ips); len(a) != 1 {
		t.Fatalf("service enumeration: %v", a)
	}
	if a, _ := r.answer(question(t, "Studio Mac._tailpocket-demo._tcp.local.", dnsmessage.TypeSRV), ips); len(a) != 1 {
		t.Fatalf("srv: %v", a)
	}
	if a, _ := r.answer(question(t, "studio-mac-1a2b.local.", dnsmessage.TypeA), ips); len(a) != 1 {
		t.Fatalf("a: %v", a)
	}
	if a, _ := r.answer(question(t, "STUDIO MAC._TAILPOCKET-DEMO._TCP.LOCAL.", dnsmessage.TypeTXT), ips); len(a) != 1 {
		t.Fatalf("names compare case-insensitively: %v", a)
	}
	if a, _ := r.answer(question(t, "_other._tcp.local.", dnsmessage.TypePTR), ips); len(a) != 0 {
		t.Fatalf("answered a foreign type: %v", a)
	}
}

func TestLegacyUnicastReplyEchoesIDAndQuestion(t *testing.T) {
	r := newRecords(testService(), "studio-mac-1a2b.local.")
	q := question(t, "_tailpocket-demo._tcp.local.", dnsmessage.TypePTR)
	query := buildQuery(t, 0x4242, q)
	reply, unicast, ok := r.respond(query, 49152, []net.IP{net.IPv4(192, 168, 1, 20)})
	if !ok || !unicast {
		t.Fatalf("ok=%v unicast=%v", ok, unicast)
	}
	var p dnsmessage.Parser
	h, err := p.Start(reply)
	if err != nil {
		t.Fatal(err)
	}
	if h.ID != 0x4242 || !h.Response {
		t.Fatalf("header %+v", h)
	}
	qs, err := p.AllQuestions()
	if err != nil || len(qs) != 1 || qs[0].Name.String() != q.Name.String() {
		t.Fatalf("questions %v %v", qs, err)
	}
	// From port 5353 it is a normal multicast query: no ID echo, multicast reply.
	if _, unicast, ok := r.respond(query, 5353, nil); !ok || unicast {
		t.Fatalf("mdns query: ok=%v unicast=%v", ok, unicast)
	}
}

func buildQuery(t *testing.T, id uint16, q dnsmessage.Question) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id})
	if err := b.StartQuestions(); err != nil {
		t.Fatal(err)
	}
	if err := b.Question(q); err != nil {
		t.Fatal(err)
	}
	out, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func needLAN(t *testing.T) {
	t.Helper()
	if os.Getenv("TAILPOCKET_NO_LAN") == "1" || len(lanInterfaces()) == 0 {
		t.Skip("no Wi-Fi/LAN interface")
	}
}

func uniqueService(t *testing.T) Service {
	s := testService()
	s.Instance = "tailpocket test " + strings.ReplaceAll(t.Name(), "/", "-")[4:] + " " + time.Now().Format("150405.000")
	s.Instance = strings.ReplaceAll(s.Instance, ".", "")
	if len(s.Instance) > 63 {
		s.Instance = s.Instance[:63]
	}
	return s
}

func findIn(t *testing.T, ctx context.Context, s Service) Entry {
	t.Helper()
	entries, err := Browse(ctx, s.Type)
	if err != nil {
		t.Fatal(err)
	}
	for e := range entries {
		if e.Instance == s.Instance {
			return e
		}
	}
	t.Fatalf("%q not found", s.Instance)
	return Entry{}
}

func advertiseBuiltin(t *testing.T, s Service) func() {
	t.Helper()
	stop, err := AdvertiseBuiltin(s)
	if errors.Is(err, ErrMulticastBlocked) {
		t.Skip("NOT TESTED HERE: this process may not send multicast (macOS Local Network privacy)")
	}
	if err != nil {
		t.Fatal(err)
	}
	return stop
}

func TestParseTXT(t *testing.T) {
	got := parseTXT(` v=1 k=a\ b n=\195\169`)
	if got["v"] != "1" || got["k"] != "a b" || got["n"] != "é" {
		t.Fatalf("%q", got)
	}
}

func TestBuiltinAdvertiseIsFoundByBrowse(t *testing.T) {
	needLAN(t)
	s := uniqueService(t)
	stop := advertiseBuiltin(t, s)
	defer stop()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	e := findIn(t, ctx, s)
	if e.Port != s.Port || len(e.IPv4) == 0 || e.TXT["v"] != "1" {
		t.Fatalf("entry %+v", e)
	}
}

// The system browser (mDNSResponder on macOS) must see the builtin responder: that is what
// iOS's NWBrowser and Android's NsdManager talk to on other devices.
func TestBuiltinAdvertiseIsSeenBySystemBrowser(t *testing.T) {
	needLAN(t)
	if runtime.GOOS != "darwin" {
		t.Skip("dns-sd browser check runs on macOS")
	}
	s := uniqueService(t)
	stop := advertiseBuiltin(t, s)
	defer stop()
	if !systemBrowserSees(t, s) {
		t.Fatalf("dns-sd -B did not list %q", s.Instance)
	}
}

func TestSystemAdvertiseIsFoundByBrowse(t *testing.T) {
	needLAN(t)
	if !SystemAvailable() {
		t.Skip("no system Bonjour tool")
	}
	s := uniqueService(t)
	stop, err := AdvertiseSystem(s)
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	e := findIn(t, ctx, s)
	if e.Port != s.Port || len(e.IPv4) == 0 {
		t.Fatalf("entry %+v", e)
	}
	stop()
	time.Sleep(time.Second)
	if systemBrowserSees(t, s) {
		t.Fatal("still listed after stop")
	}
}

func TestBuiltinBrowseFindsSystemAdvertise(t *testing.T) {
	needLAN(t)
	if !SystemAvailable() {
		t.Skip("no system Bonjour tool")
	}
	s := uniqueService(t)
	stop, err := AdvertiseSystem(s)
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	entries, err := BrowseBuiltin(ctx, s.Type)
	if errors.Is(err, ErrMulticastBlocked) {
		t.Skip("NOT TESTED HERE: this process may not send multicast (macOS Local Network privacy)")
	}
	if err != nil {
		t.Fatal(err)
	}
	for e := range entries {
		if e.Instance == s.Instance && e.Port == s.Port {
			return
		}
	}
	t.Fatal("not found")
}

// systemBrowserSees reports whether dns-sd -B lists s at the end of a short browse (an Add not
// followed by a Rmv).
func systemBrowserSees(t *testing.T, s Service) bool {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "/usr/bin/dns-sd", "-B", s.Type, "local")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { cancel(); _ = cmd.Wait() }()
	sc := bufio.NewScanner(out)
	listed := false
	for sc.Scan() {
		line := sc.Text()
		if strings.HasSuffix(line, s.Instance) {
			listed = strings.Contains(line, " Add ")
		}
	}
	return listed
}

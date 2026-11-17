package connectphone

import (
	"context"
	"errors"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jt-wang/tailpocket/connectinvite"
)

func TestKeys(t *testing.T) {
	k := NewKey()
	if !strings.HasPrefix(k, "privkey:") {
		t.Fatalf("key %q", k)
	}
	pub, err := PublicKey(k)
	if err != nil || !strings.HasPrefix(pub, "nodekey:") {
		t.Fatalf("public %q %v", pub, err)
	}
	if again, _ := PublicKey(k); again != pub {
		t.Fatal("public key not stable")
	}
	for _, bad := range []string{"", "privkey:00", pub} {
		if _, err := PublicKey(bad); err == nil {
			t.Errorf("accepted %q", bad)
		}
	}
}

func TestPairRefusesExpiredOrInvalidInviteWithoutNetwork(t *testing.T) {
	inv := connectinvite.Invite{Version: 1, Address: "tcAAAA", Port: 48932, Key: NewKey(), Proof: connectinvite.NewProof(), ExpiresAt: time.Now().Add(time.Minute).Unix()}
	if _, err := Pair(context.Background(), inv, NewKey(), Options{}); !errors.Is(err, connectinvite.ErrInvalid) {
		t.Fatalf("invalid invite: %v", err)
	}
	if _, err := Pair(context.Background(), inv, "privkey:00", Options{}); err == nil {
		t.Fatal("bad phone key accepted")
	}
}

func TestReplyValidation(t *testing.T) {
	good := reply{V: 1, Address: testAddress(t), Port: 48931, Name: "Mac"}
	if _, err := good.link("privkey:x"); err != nil {
		t.Fatal(err)
	}
	for name, r := range map[string]reply{
		"version": {V: 2, Address: good.Address, Port: 1},
		"address": {V: 1, Address: "nope", Port: 1},
		"port":    {V: 1, Address: good.Address, Port: 0},
		"name":    {V: 1, Address: good.Address, Port: 1, Name: "a\nb"},
	} {
		if _, err := r.link("privkey:x"); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
}

func TestLinkJSONRoundTrip(t *testing.T) {
	l := Link{Address: testAddress(t), Port: 48931, Name: "Mac", Payload: "p", Key: NewKey()}
	text, err := l.Encode()
	if err != nil {
		t.Fatal(err)
	}
	back, err := ParseLink(text)
	if err != nil || back != l {
		t.Fatalf("%+v %v", back, err)
	}
	if _, err := ParseLink(`{"address":"x"}`); err == nil {
		t.Fatal("invalid link parsed")
	}
}

// echoDialer stands in for a Tailcat session.
func echoDialer() (func(context.Context) (net.Conn, error), *atomic.Int32) {
	n := &atomic.Int32{}
	return func(context.Context) (net.Conn, error) {
		n.Add(1)
		a, b := net.Pipe()
		go func() { _, _ = io.Copy(b, b) }()
		return a, nil
	}, n
}

func TestForwardBridgesEachConnectionLazily(t *testing.T) {
	dial, dials := echoDialer()
	f, err := startForward(dial)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if dials.Load() != 0 {
		t.Fatal("dialed before any local connection")
	}
	if !strings.HasPrefix(f.Addr(), "127.0.0.1:") {
		t.Fatalf("addr %s", f.Addr())
	}
	for i := 0; i < 2; i++ {
		c, err := net.Dial("tcp", f.Addr())
		if err != nil {
			t.Fatal(err)
		}
		_, _ = c.Write([]byte("ping"))
		buf := make([]byte, 4)
		_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
		if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "ping" {
			t.Fatalf("%q %v", buf, err)
		}
		c.Close()
	}
	if dials.Load() != 2 {
		t.Fatalf("dials %d", dials.Load())
	}
}

func TestForwardCloseCutsOpenConnections(t *testing.T) {
	dial, _ := echoDialer()
	f, _ := startForward(dial)
	c, err := net.Dial("tcp", f.Addr())
	if err != nil {
		t.Fatal(err)
	}
	_, _ = c.Write([]byte("x"))
	time.Sleep(50 * time.Millisecond)
	f.Close()
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 8)
	for {
		if _, err := c.Read(buf); err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				t.Fatal("connection still open after Close")
			}
			break
		}
	}
	if _, err := net.DialTimeout("tcp", f.Addr(), time.Second); err == nil {
		t.Fatal("still listening")
	}
}

func TestForwardDialFailureClosesLocalConnection(t *testing.T) {
	f, _ := startForward(func(context.Context) (net.Conn, error) { return nil, errors.New("unreachable") })
	defer f.Close()
	c, _ := net.Dial("tcp", f.Addr())
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read succeeded")
	}
}

// nilConn stands in for gonet.TCPConn: its methods dereference the receiver, so calling Close on a
// nil one panics, like (*gonet.TCPConn)(nil).Close() does.
type nilConn struct {
	net.Conn
	ep io.Closer
}

func (c *nilConn) Close() error { return c.ep.Close() }

// Tailcat's dialer returns a failed netstack dial as a nil *gonet.TCPConn inside a non-nil net.Conn
// (tailcat v0.6.0, tailcat.go:1895 returns ns.DialContextTCP as is). A revoked phone or an
// offline computer must not crash the app.
func TestForwardDialFailureWithTypedNilConnDoesNotPanic(t *testing.T) {
	f, _ := startForward(func(context.Context) (net.Conn, error) {
		var c *nilConn
		return c, errors.New("connection refused")
	})
	defer f.Close()
	c, _ := net.Dial("tcp", f.Addr())
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read succeeded")
	}
}

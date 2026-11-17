package connectmobile

import (
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/jt-wang/tailpocket/connectinvite"
	"github.com/jt-wang/tailpocket/connectlocal"
)

func errorOf(t *testing.T, s string) string {
	t.Helper()
	var v struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal([]byte(s), &v)
	return v.Error
}

func TestKeysNeverThrow(t *testing.T) {
	k := NewKey()
	if PublicKey(k) == "" || PublicKey("junk") != "" {
		t.Fatal("PublicKey")
	}
}

func TestDecodeInvite(t *testing.T) {
	if errorOf(t, DecodeInvite("tailpocket1:junk", "tailpocket1:")) != "invalid-invite" {
		t.Fatal("junk decoded")
	}
}

func TestPairErrorsAreCodes(t *testing.T) {
	if got := errorOf(t, Pair(1, "{}", NewKey(), "x", "", 1000)); got != "invalid-invite" {
		t.Fatalf("got %q", got)
	}
	expired := connectinvite.Invite{Version: 1, Address: testAddress(t), Port: 48932, Key: NewKey(), Proof: connectinvite.NewProof(), ExpiresAt: time.Now().Add(-time.Minute).Unix()}
	body, _ := json.Marshal(expired)
	if got := errorOf(t, Pair(2, string(body), NewKey(), "x", "", 1000)); got != "expired" {
		t.Fatalf("got %q", got)
	}
}

func TestConnectRejectsBadLinkAndStopIsIdempotent(t *testing.T) {
	if errorOf(t, Connect(`{"address":"x"}`, "")) != "invalid-link" {
		t.Fatal("bad link")
	}
	link := `{"address":"` + testAddress(t) + `","port":48931,"name":"Mac","key":"` + NewKey() + `"}`
	out := Connect(link, "")
	var v struct {
		Handle int64 `json:"handle"`
		Port   int   `json:"port"`
	}
	if json.Unmarshal([]byte(out), &v) != nil || v.Handle == 0 || v.Port == 0 {
		t.Fatalf("connect %s", out)
	}
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", itoa(v.Port)))
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	Stop(v.Handle)
	Stop(v.Handle)
	if _, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", itoa(v.Port)), time.Second); err == nil {
		t.Fatal("forward still listening after Stop")
	}
}

// Two-step local pairing: start returns the code, finish returns the invite.
func TestLocalPairTwoSteps(t *testing.T) {
	inv := connectinvite.Invite{Version: 1, Address: testAddress(t), Port: 48932, Key: NewKey(), Proof: connectinvite.NewProof(), ExpiresAt: time.Now().Add(time.Minute).Unix(), Name: "Mac"}
	plain, _ := json.Marshal(inv)
	port := make(chan int, 1)
	hostCode := make(chan string, 1)
	go func() {
		_ = connectlocal.Serve(t.Context(), connectlocal.HostOptions{Label: connectlocal.DefaultLabel, Plaintext: plain, Expires: time.Now().Add(10 * time.Second),
			Bindings:    []connectlocal.Binding{{IP: net.IPv4(127, 0, 0, 1).To4(), Mask: net.CIDRMask(8, 32)}},
			OnListening: func(p int) { port <- p },
			Confirm:     func(_ ctxT, code, _ string) bool { hostCode <- code; return true }})
	}()
	addr := "127.0.0.1:" + itoa(<-port)
	started := LocalPairStart(7, addr, "", "Pixel")
	var s struct {
		Code string `json:"code"`
	}
	if json.Unmarshal([]byte(started), &s) != nil || len(s.Code) != 6 {
		t.Fatalf("start %s", started)
	}
	if code := <-hostCode; code != s.Code {
		t.Fatal("codes differ")
	}
	got := LocalPairFinish(7, 5000)
	back, err := connectinvite.ParseJSON([]byte(got))
	if err != nil || back != inv {
		t.Fatalf("finish %s", got)
	}
	if errorOf(t, LocalPairFinish(7, 100)) != "unknown-operation" {
		t.Fatal("finished twice")
	}
}

func TestCancelUnknownIsNoop(t *testing.T) {
	Cancel(12345)
	if !strings.Contains(LocalPairFinish(999, 10), "unknown-operation") {
		t.Fatal("unknown op")
	}
}

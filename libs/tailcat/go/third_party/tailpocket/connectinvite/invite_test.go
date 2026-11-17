package connectinvite

import (
	"bytes"
	"compress/zlib"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// testAddress builds a real, parseable tailcat address offline.
func testAddress(t *testing.T) string {
	t.Helper()
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: key.NewNode().Public()},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: key.NewDisco().Public()},
		PresharedKey:      tailcat.NewPresharedKey(),
		Region: []*tailcfg.DERPRegion{{RegionID: 7, Nodes: []*tailcfg.DERPNode{
			{Name: "7a", RegionID: 7, HostName: "derp7.example.com"},
		}}},
	}
	return string(ci.Addr())
}

func testKey(t *testing.T) string {
	t.Helper()
	text, err := key.NewNode().MarshalText()
	if err != nil {
		t.Fatal(err)
	}
	return string(text)
}

func valid(t *testing.T) Invite {
	t.Helper()
	return Invite{
		Version:   Version,
		Address:   testAddress(t),
		Port:      48932,
		Key:       testKey(t),
		Proof:     NewProof(),
		ExpiresAt: time.Now().Add(2 * time.Minute).Unix(),
		Name:      "Studio Mac",
	}
}

func TestEncodeDecodeRoundTrip(t *testing.T) {
	in := valid(t)
	text, err := Encode(in, DefaultPrefix)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(text, DefaultPrefix) {
		t.Fatalf("missing prefix: %q", text[:10])
	}
	out, err := Decode(text, DefaultPrefix)
	if err != nil {
		t.Fatal(err)
	}
	if out != in {
		t.Fatalf("round trip changed the invite:\n got %+v\nwant %+v", out, in)
	}
	t.Logf("QR text length: %d", len(text))
	// QR version 40 holds 2953 bytes in byte mode; stay far below it so phones scan it quickly.
	if len(text) > 1200 {
		t.Fatalf("QR text is %d bytes", len(text))
	}
}

func TestCustomPrefixAndSurroundingSpace(t *testing.T) {
	in := valid(t)
	text, err := Encode(in, "myapp-pair:")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Decode("  "+text+"\n", "myapp-pair:"); err != nil {
		t.Fatalf("trimmed text should decode: %v", err)
	}
	if _, err := Decode(text, DefaultPrefix); err == nil {
		t.Fatal("a different prefix must not decode")
	}
}

func TestInvalidPrefixRejected(t *testing.T) {
	for _, prefix := range []string{"", "no colon", "has space:", "x:y:"} {
		if _, err := Encode(valid(t), prefix); err == nil {
			t.Errorf("Encode accepted prefix %q", prefix)
		}
	}
}

func TestParseJSONIsTheSealedForm(t *testing.T) {
	in := valid(t)
	body, err := json.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := ParseJSON(body)
	if err != nil {
		t.Fatal(err)
	}
	if out != in {
		t.Fatalf("got %+v", out)
	}
}

func TestDecodeRejectsMalformedInput(t *testing.T) {
	pack := func(v any) string {
		raw, _ := json.Marshal(v)
		var buf bytes.Buffer
		w := zlib.NewWriter(&buf)
		_, _ = w.Write(raw)
		_ = w.Close()
		return DefaultPrefix + base64.RawURLEncoding.EncodeToString(buf.Bytes())
	}
	mutate := func(f func(*Invite)) string {
		in := valid(t)
		f(&in)
		return pack(in)
	}
	cases := map[string]string{
		"empty":          "",
		"prefix only":    DefaultPrefix,
		"not base64":     DefaultPrefix + "!!!",
		"not zlib":       DefaultPrefix + base64.RawURLEncoding.EncodeToString([]byte("plain")),
		"not json":       pack("just a string"),
		"version":        mutate(func(i *Invite) { i.Version = 2 }),
		"address":        mutate(func(i *Invite) { i.Address = "tcAAAA" }),
		"not tc address": mutate(func(i *Invite) { i.Address = "https://example.com" }),
		"key":            mutate(func(i *Invite) { i.Key = "privkey:00" }),
		"public as key":  mutate(func(i *Invite) { i.Key = strings.Replace(testKey(t), "privkey:", "nodekey:", 1) }),
		"proof short":    mutate(func(i *Invite) { i.Proof = "abc" }),
		"proof alphabet": mutate(func(i *Invite) { i.Proof = strings.Repeat("+", 43) }),
		"port zero":      mutate(func(i *Invite) { i.Port = 0 }),
		"port high":      mutate(func(i *Invite) { i.Port = 70000 }),
		"no expiry":      mutate(func(i *Invite) { i.ExpiresAt = 0 }),
		"name too long":  mutate(func(i *Invite) { i.Name = strings.Repeat("n", 65) }),
		"name control":   mutate(func(i *Invite) { i.Name = "a\nb" }),
	}
	for name, text := range cases {
		if _, err := Decode(text, DefaultPrefix); err == nil {
			t.Errorf("%s: decoded", name)
		}
	}
	huge := DefaultPrefix + strings.Repeat("A", MaxTextLength)
	if _, err := Decode(huge, DefaultPrefix); err == nil {
		t.Error("oversized text decoded")
	}
}

func TestZlibBombRejected(t *testing.T) {
	var buf bytes.Buffer
	w := zlib.NewWriter(&buf)
	_, _ = w.Write(bytes.Repeat([]byte(" "), 1<<20))
	_ = w.Close()
	text := DefaultPrefix + base64.RawURLEncoding.EncodeToString(buf.Bytes())
	if len(text) > MaxTextLength {
		t.Skip("compressed form is already over the text limit")
	}
	if _, err := Decode(text, DefaultPrefix); err == nil {
		t.Fatal("decompressed beyond the limit")
	}
}

func TestExpired(t *testing.T) {
	in := valid(t)
	now := time.Unix(in.ExpiresAt, 0)
	if in.Expired(now.Add(-time.Second)) {
		t.Fatal("expired too early")
	}
	if !in.Expired(now) {
		t.Fatal("not expired at expiresAt")
	}
}

func TestNewProofShape(t *testing.T) {
	a, b := NewProof(), NewProof()
	if len(a) != 43 || a == b {
		t.Fatalf("proofs %q %q", a, b)
	}
	if _, err := base64.RawURLEncoding.DecodeString(a); err != nil {
		t.Fatal(err)
	}
}

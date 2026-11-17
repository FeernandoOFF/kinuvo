package connectlocal

import (
	"bytes"
	"encoding/base64"
	"testing"
)

func b64(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// The frozen vector from DSH Mobile's docs/TAILCAT.md: with the same label, this package must
// reproduce it byte for byte, so existing apps can move onto it without breaking paired phones.
func TestFrozenVectorWithDSHLabel(t *testing.T) {
	const label = "dsh-mobile-local-pairing-v1"
	phone, err := KeyPairFromPrivate(b64(t, "ERERERERERERERERERERERERERERERERERERERERERE="))
	if err != nil {
		t.Fatal(err)
	}
	host, err := KeyPairFromPrivate(b64(t, "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI="))
	if err != nil {
		t.Fatal(err)
	}
	if got := base64.StdEncoding.EncodeToString(phone.Public[:]); got != "e06Qm75//kTEZaIgA31gjuNYl9Me+XLwf3SJLLD3PxM=" {
		t.Fatalf("phone public %s", got)
	}
	if got := base64.StdEncoding.EncodeToString(host.Public[:]); got != "D6poTtKIZ7l/Smot7l34zpdOdrcBjj8iocTPJnhXDyA=" {
		t.Fatalf("host public %s", got)
	}
	ps, err := NewSession(label, phone, host.Public[:], true)
	if err != nil {
		t.Fatal(err)
	}
	hs, err := NewSession(label, host, phone.Public[:], false)
	if err != nil {
		t.Fatal(err)
	}
	if ps.Code != "295936" || hs.Code != "295936" {
		t.Fatalf("codes %s %s", ps.Code, hs.Code)
	}
	if got := base64.StdEncoding.EncodeToString(ps.key); got != "380q6EYc859pXQkUCN6O6yEjVt0LPwnps+5K71Biy/c=" {
		t.Fatalf("key %s", got)
	}
	sealed, err := hs.sealWithNonce([]byte(`{"version":3}`), b64(t, "MzMzMzMzMzMzMzMz"))
	if err != nil {
		t.Fatal(err)
	}
	if got := base64.StdEncoding.EncodeToString(sealed); got != "MzMzMzMzMzMzMzMzCy/nmRJXOOnj64hGBAw4VoRK2dUw3zgf3IEP2kU=" {
		t.Fatalf("sealed %s", got)
	}
	plain, err := ps.Open(sealed)
	if err != nil || string(plain) != `{"version":3}` {
		t.Fatalf("open %q %v", plain, err)
	}
}

func TestSessionsAgreeAndRejectTampering(t *testing.T) {
	phone, _ := NewKeyPair()
	host, _ := NewKeyPair()
	ps, _ := NewSession(DefaultLabel, phone, host.Public[:], true)
	hs, _ := NewSession(DefaultLabel, host, phone.Public[:], false)
	if ps.Code != hs.Code || len(ps.Code) != 6 {
		t.Fatalf("codes %q %q", ps.Code, hs.Code)
	}
	sealed, err := hs.Seal([]byte("hello"))
	if err != nil {
		t.Fatal(err)
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := ps.Open(sealed); err == nil {
		t.Fatal("tampered box opened")
	}
	other, _ := NewSession("another-label", phone, host.Public[:], true)
	if other.Code == ps.Code && bytes.Equal(other.key, ps.key) {
		t.Fatal("label does not separate sessions")
	}
}

func TestLowOrderPeerRejected(t *testing.T) {
	own, _ := NewKeyPair()
	if _, err := NewSession(DefaultLabel, own, make([]byte, 32), true); err == nil {
		t.Fatal("all-zero peer key accepted")
	}
	if _, err := NewSession(DefaultLabel, own, make([]byte, 31), true); err == nil {
		t.Fatal("short peer key accepted")
	}
	if _, err := NewSession("", own, own.Public[:], true); err == nil {
		t.Fatal("empty label accepted")
	}
}

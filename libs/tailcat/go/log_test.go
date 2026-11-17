package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"
)

func TestRedaction(t *testing.T) {
	for _, value := range []string{
		"dial tc0123456789abc failed", "dial 192.168.1.2:443 failed", "dial [2001:db8::1]:443 failed",
		"secret AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "node key: abc123", "pairing code: 123456", "token: short", "peer(abcde…fghi)", "DERP connection for [Abc12]", "node [abcde] d:123456789abcdef0",
	} {
		result := redact(value)
		if result == value || !strings.Contains(result, "[redacted") {
			t.Errorf("not redacted: %q", result)
		}
	}
	message := "dial failed: netmon.New: route ip+net: netlinkrib: permission denied"
	if redact(message) != message {
		t.Fatal("lost network diagnostic")
	}
}

func TestStartupErrors(t *testing.T) {
	for _, test := range []struct {
		err  error
		want string
	}{
		{context.DeadlineExceeded, "Tailcat startup timed out"},
		{&net.DNSError{Err: "failed", Name: "secret"}, "Tailcat could not resolve a relay hostname"},
		{errors.New("netmon.New: permission denied"), "Tailcat could not read network interfaces"},
		{errors.New("x509: secret"), "Tailcat could not verify the relay certificate"},
		{errors.New("secret"), "Tailcat could not start the tunnel; check debug logs"},
	} {
		if got := startupError(test.err).Error(); got != test.want {
			t.Fatalf("got %q", got)
		}
	}
}

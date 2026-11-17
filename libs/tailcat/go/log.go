package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"regexp"
	"strings"
)

var sensitive = regexp.MustCompile(`(?i)\btc[a-z0-9_-]{8,}|\[[a-z0-9+/…_-]{5,}\]|[a-z0-9+/=_-]{32,}|(?:\d{1,3}\.){3}\d{1,3}|\[?[0-9a-f]*:[0-9a-f:]+\]?`)

func redact(value string) string {
	lower := strings.ToLower(value)
	for _, word := range []string{"networkmap", "key", "token", "pair", "code", "authorization", "peer", "node", "disco", "endpoints"} {
		if strings.Contains(lower, word) {
			return "[redacted sensitive diagnostic]"
		}
	}
	return sensitive.ReplaceAllString(value, "[redacted]")
}

func debugLog(format string, args ...any) {
	platformLog(redact(fmt.Sprintf(format, args...)))
}

func startupError(err error) error {
	var dns *net.DNSError
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return errors.New("Tailcat startup timed out")
	case errors.As(err, &dns):
		return errors.New("Tailcat could not resolve a relay hostname")
	case strings.Contains(err.Error(), "netmon.New"):
		return errors.New("Tailcat could not read network interfaces")
	case strings.Contains(err.Error(), "x509:"):
		return errors.New("Tailcat could not verify the relay certificate")
	default:
		return errors.New("Tailcat could not start the tunnel; check debug logs")
	}
}

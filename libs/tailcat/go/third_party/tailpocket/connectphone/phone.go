// Package connectphone is the phone side: pair with a host from an invite (QR code, link, or same-Wi-Fi
// discovery), keep the resulting Link, and open connections to the host through Tailcat.
// The gomobile bindings in ../mobile wrap this package for iOS and Android.
package connectphone

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"

	"github.com/jt-wang/tailpocket"
	"github.com/jt-wang/tailpocket/connectinvite"
	"github.com/jt-wang/tailpocket/connectlocal"
	"github.com/jt-wang/tailpocket/connectmdns"
)

var (
	ErrExpired      = errors.New("connectphone: the invite has expired")
	ErrUnauthorized = errors.New("connectphone: the host rejected the invite")
	ErrTaken        = errors.New("connectphone: another phone already used this invite")
	ErrUnreachable  = errors.New("connectphone: could not reach the host")
)

// Link is everything a paired phone needs to reach its host. Key is the phone's private key and
// Address embeds the host's pre-shared key: store the whole Link in the Keychain/Keystore.
type Link struct {
	Address string `json:"address"`
	Port    int    `json:"port"`
	Name    string `json:"name"`
	Payload string `json:"payload,omitempty"`
	Key     string `json:"key"`
}

func (l Link) validate() error {
	if !connectinvite.ValidAddress(l.Address) || l.Port < 1 || l.Port > 65535 || !connectinvite.ValidName(l.Name) {
		return errors.New("connectphone: invalid link")
	}
	if _, err := parsePrivate(l.Key); err != nil {
		return err
	}
	return nil
}

// Encode returns the Link as JSON text for storage.
func (l Link) Encode() (string, error) {
	if err := l.validate(); err != nil {
		return "", err
	}
	body, err := json.Marshal(l)
	return string(body), err
}

// ParseLink reads what Encode wrote.
func ParseLink(text string) (Link, error) {
	var l Link
	if json.Unmarshal([]byte(text), &l) != nil {
		return Link{}, errors.New("connectphone: invalid link")
	}
	return l, l.validate()
}

// NewKey returns a new phone identity ("privkey:…"). Create it once and keep it: it is how hosts
// recognize this phone.
func NewKey() string {
	text, _ := key.NewNode().MarshalText()
	return string(text)
}

// PublicKey returns the "nodekey:…" a host lists for this phone.
func PublicKey(private string) (string, error) {
	k, err := parsePrivate(private)
	if err != nil {
		return "", err
	}
	return k.Public().String(), nil
}

func parsePrivate(text string) (key.NodePrivate, error) {
	var k key.NodePrivate
	if k.UnmarshalText([]byte(text)) != nil || k.IsZero() {
		return key.NodePrivate{}, errors.New("connectphone: invalid phone key")
	}
	return k, nil
}

type Options struct {
	// Device is the name the host shows for this phone.
	Device string
	// DERPMapURL must match the host's when it uses its own relays.
	DERPMapURL string
}

type reply struct {
	V       int    `json:"v"`
	Address string `json:"address"`
	Port    int    `json:"port"`
	Name    string `json:"name"`
	Payload string `json:"payload"`
}

func (r reply) link(phoneKey string) (Link, error) {
	if r.V != 1 {
		return Link{}, errors.New("connectphone: unsupported host reply")
	}
	l := Link{Address: r.Address, Port: r.Port, Name: r.Name, Payload: r.Payload, Key: phoneKey}
	if !connectinvite.ValidAddress(l.Address) || l.Port < 1 || l.Port > 65535 || !connectinvite.ValidName(l.Name) {
		return Link{}, errors.New("connectphone: invalid host reply")
	}
	return l, nil
}

// Pair joins the host's pairing window as phoneKey and returns the Link. It retries while the
// pairing node is not reachable yet, until ctx ends (give it 30–60 s on mobile networks).
func Pair(ctx context.Context, inv connectinvite.Invite, phoneKey string, opts Options) (Link, error) {
	if err := inv.Validate(); err != nil {
		return Link{}, err
	}
	private, err := parsePrivate(phoneKey)
	if err != nil {
		return Link{}, err
	}
	if inv.Expired(time.Now()) {
		return Link{}, ErrExpired
	}
	var oneTime key.NodePrivate
	_ = oneTime.UnmarshalText([]byte(inv.Key))
	session := &Session{link: Link{Address: inv.Address, Port: inv.Port}, key: oneTime, derpMapURL: opts.DERPMapURL}
	defer session.Close()
	httpClient := &http.Client{Transport: &http.Transport{DisableKeepAlives: true,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) { return session.Dial(ctx) }}}
	body, _ := json.Marshal(map[string]string{"phoneKey": private.Public().String(), "device": connectinvite.CleanName(opts.Device)})
	deadline := time.Unix(inv.ExpiresAt, 0)
	ctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	var last error
	for attempt := 0; ; attempt++ {
		req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "http://pairing.invalid/pair", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+inv.Proof)
		req.Header.Set("Content-Type", "application/json")
		resp, err := httpClient.Do(req)
		if err == nil {
			data, rerr := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
			resp.Body.Close()
			switch {
			case rerr != nil:
				last = rerr
			case resp.StatusCode == http.StatusOK:
				var r reply
				if json.Unmarshal(data, &r) != nil {
					return Link{}, errors.New("connectphone: invalid host reply")
				}
				return r.link(phoneKey)
			case resp.StatusCode == http.StatusUnauthorized:
				return Link{}, ErrUnauthorized
			case resp.StatusCode == http.StatusConflict:
				return Link{}, ErrTaken
			case resp.StatusCode == http.StatusGone:
				return Link{}, ErrExpired
			case resp.StatusCode == http.StatusServiceUnavailable:
				last = errors.New("connectphone: host could not add this phone")
			default:
				return Link{}, fmt.Errorf("connectphone: host answered %d", resp.StatusCode)
			}
		} else {
			last = err
		}
		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) && !time.Now().Before(deadline) {
				return Link{}, ErrExpired
			}
			if last != nil {
				return Link{}, fmt.Errorf("%w: %v", ErrUnreachable, last)
			}
			return Link{}, ctx.Err()
		case <-time.After(time.Duration(min(attempt+1, 3)) * time.Second):
		}
	}
}

// Session reaches the host of one Link. It is safe for concurrent use.
type Session struct {
	link       Link
	key        key.NodePrivate
	derpMapURL string

	mu     sync.Mutex
	client *tailcat.Client
	closed bool
}

// Connect prepares a session; nothing goes over the network until the first Dial.
func Connect(link Link, opts Options) (*Session, error) {
	if err := link.validate(); err != nil {
		return nil, err
	}
	private, _ := parsePrivate(link.Key)
	return &Session{link: link, key: private, derpMapURL: opts.DERPMapURL}, nil
}

func relayClient(ctx context.Context, address string, private key.NodePrivate, derpMapURL string) (*tailcat.Client, error) {
	addr := tailcat.Addr(address)
	if tailpocket.IPv4Only {
		info, err := tailcat.ParseAddr(addr)
		if err != nil {
			return nil, err
		}
		var opts []any
		if derpMapURL != "" {
			opts = append(opts, tailcat.DERPMapURL(derpMapURL))
		}
		if err := info.Expand(ctx, opts...); err != nil {
			return nil, err
		}
		for i, region := range info.Region {
			info.Region[i] = tailpocket.RelayRegion(region)
		}
		addr = info.Addr()
	}
	return &tailcat.Client{Server: addr, Key: private, Logf: func(string, ...any) {}, DERPMapURL: derpMapURL}, nil
}

func (s *Session) relayClient(ctx context.Context) (*tailcat.Client, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, net.ErrClosed
	}
	if s.client == nil {
		client, err := relayClient(ctx, s.link.Address, s.key, s.derpMapURL)
		if err != nil {
			return nil, err
		}
		s.client = client
	}
	return s.client, nil
}

// Dial opens one connection to the host's app.
// On failure it returns a nil net.Conn: Tailcat returns a failed dial as a nil *gonet.TCPConn
// inside a non-nil net.Conn (tailcat v0.6.0, tailcat.go:1895), whose Close panics.
func (s *Session) Dial(ctx context.Context) (net.Conn, error) {
	client, err := s.relayClient(ctx)
	if err != nil {
		return nil, err
	}
	c, err := client.DialTCPPort(ctx, uint16(s.link.Port))
	if err != nil {
		return nil, err
	}
	return c, nil
}

// Forward listens on 127.0.0.1 and carries every accepted connection to the host, for HTTP or
// WebSocket stacks that need a plain address ("http://127.0.0.1:<port>").
func (s *Session) Forward() (*Forward, error) {
	return startForward(func(ctx context.Context) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
		defer cancel()
		return s.Dial(ctx)
	})
}

func (s *Session) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.client == nil {
		return nil
	}
	err := s.client.Close()
	s.client = nil
	return err
}

// Discover lists hosts advertising service on this Wi-Fi.
func Discover(ctx context.Context, service string) (<-chan connectmdns.Entry, error) {
	return connectmdns.Browse(ctx, service)
}

type LocalOptions struct {
	Label  string // default connectlocal.DefaultLabel; must match the host
	Device string
	// OnCode receives the 6-digit code to show while the host's user compares it.
	OnCode func(code string)
}

// PairLocal fetches the invite from a host found by Discover (address "ip:port").
func PairLocal(ctx context.Context, address string, opts LocalOptions) (connectinvite.Invite, error) {
	label := opts.Label
	if label == "" {
		label = connectlocal.DefaultLabel
	}
	plain, err := connectlocal.Pair(ctx, address, connectlocal.PhoneOptions{Label: label, Device: opts.Device, OnCode: opts.OnCode})
	if err != nil {
		return connectinvite.Invite{}, err
	}
	return connectinvite.ParseJSON(plain)
}

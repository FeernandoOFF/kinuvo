// Package connecthost is the computer side: it keeps a Tailcat node that only paired phones can reach,
// hands accepted connections to the app as a net.Listener, and opens pairing windows that a phone
// joins by QR code, link, or same-Wi-Fi Bonjour discovery.
package connecthost

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"slices"
	"sync"
	"sync/atomic"

	"tailscale.com/types/key"

	"github.com/jt-wang/tailpocket/connectinvite"
)

const (
	DefaultPort        = 48931
	DefaultPairingPort = 48932
	MaxPhones          = 256
)

var (
	ErrExpired         = errors.New("connecthost: pairing window expired")
	ErrClosed          = errors.New("connecthost: closed")
	ErrInvalidIdentity = errors.New("connecthost: invalid identity")
	ErrInvalidPhone    = errors.New("connecthost: invalid phone key list")
)

// State is what the app must store to keep its address and paired phones across restarts.
// Identity contains the node's private keys: store it like a password.
type State struct {
	Identity string   `json:"identity"`
	Allowed  []string `json:"allowed"`
}

type Config struct {
	// Name is shown on the phone while pairing. Default: the computer's hostname.
	Name string
	// Port is the virtual port phones connect to (default 48931); PairingPort serves pairing
	// windows (default 48932). Phones learn both from the host, so they rarely need changing.
	Port, PairingPort uint16
	// Identity and Allowed restore a previous State. Empty Identity creates a new one.
	Identity string
	Allowed  []string
	// Save stores State. It runs before a change takes effect; if it fails, the change is refused.
	Save func(State) error
	// DERPMapURL points Tailcat at your own relay map instead of Tailcat's free, rate-limited default
	// (https://tailcat.dev/derpmap.json).
	DERPMapURL string
}

// Host is safe for concurrent use.
type Host struct {
	name              string
	port, pairingPort uint16
	save              func(State) error
	derpMapURL        string
	start             starter
	listener          *chanListener

	op sync.Mutex // serializes changes to the allowlist and main node

	mu       sync.Mutex
	id       identity
	allowed  []key.NodePublic
	byIP     map[netip.Addr]string // phones whose connections serveMain admits
	conns    map[*Conn]struct{}    // admitted connections not yet closed
	main     node
	pairings map[*Pairing]struct{}
	closed   bool
}

func New(cfg Config) (*Host, error) {
	if cfg.Save == nil {
		return nil, errors.New("connecthost: Config.Save is required")
	}
	h := &Host{name: connectinvite.CleanName(cfg.Name), port: cfg.Port, pairingPort: cfg.PairingPort, save: cfg.Save,
		derpMapURL: cfg.DERPMapURL, start: realStart, listener: newChanListener(), pairings: map[*Pairing]struct{}{},
		conns: map[*Conn]struct{}{}}
	if h.name == "" {
		hostname, _ := os.Hostname()
		h.name = connectinvite.CleanName(hostname)
	}
	if h.port == 0 {
		h.port = DefaultPort
	}
	if h.pairingPort == 0 {
		h.pairingPort = DefaultPairingPort
	}
	if h.port == h.pairingPort {
		return nil, errors.New("connecthost: Port and PairingPort must differ")
	}
	var err error
	if cfg.Identity == "" {
		h.id = newIdentity()
	} else if h.id, err = parseIdentity(cfg.Identity); err != nil {
		return nil, err
	}
	if h.allowed, err = parseKeys(cfg.Allowed); err != nil {
		return nil, err
	}
	h.reindex()
	return h, nil
}

func parseKeys(texts []string) ([]key.NodePublic, error) {
	if len(texts) > MaxPhones {
		return nil, fmt.Errorf("%w: more than %d phones", ErrInvalidPhone, MaxPhones)
	}
	out := make([]key.NodePublic, 0, len(texts))
	for _, t := range texts {
		k, err := parsePhone(t)
		if err != nil {
			return nil, err
		}
		if slices.Contains(out, k) {
			return nil, fmt.Errorf("%w: duplicate key", ErrInvalidPhone)
		}
		out = append(out, k)
	}
	return out, nil
}

func parsePhone(text string) (key.NodePublic, error) {
	var k key.NodePublic
	if k.UnmarshalText([]byte(text)) != nil || k.IsZero() {
		return key.NodePublic{}, ErrInvalidPhone
	}
	return k, nil
}

// Start brings up the main node if any phone is paired. A host with no phones runs no main node:
// Tailcat admits every client when its allowlist is empty.
func (h *Host) Start(ctx context.Context) error {
	h.op.Lock()
	defer h.op.Unlock()
	h.mu.Lock()
	allowed, running, closed := slices.Clone(h.allowed), h.main != nil, h.closed
	h.mu.Unlock()
	if closed {
		return ErrClosed
	}
	if running || len(allowed) == 0 {
		return nil
	}
	return h.restartMain(ctx, allowed)
}

// restartMain replaces the main node with one admitting keys, saving the state it implies.
// On failure the previous node keeps running. h.op must be held.
func (h *Host) restartMain(ctx context.Context, keys []key.NodePublic) error {
	h.mu.Lock()
	id, old, prev := h.id, h.main, slices.Clone(h.allowed)
	h.mu.Unlock()
	var next node
	if len(keys) > 0 {
		var err error
		if old != nil {
			_ = old.Close() // the address is the node key's; two live nodes with it would fight
		}
		h.mu.Lock()
		h.byIP = indexKeys(keys) // the new node may hand over connections before start returns
		h.mu.Unlock()
		next, err = h.start(ctx, nodeSpec{key: id.key, psk: id.psk, region: id.region, allowed: keys, port: h.port,
			onTCP: h.serveMain, derpMapURL: h.derpMapURL})
		if err != nil {
			h.mu.Lock()
			h.byIP = indexKeys(prev)
			h.mu.Unlock()
			if old != nil {
				h.mu.Lock()
				h.main = nil
				h.mu.Unlock()
				if len(prev) > 0 {
					if back, berr := h.start(context.Background(), nodeSpec{key: id.key, psk: id.psk, region: id.region, allowed: prev,
						port: h.port, onTCP: h.serveMain, derpMapURL: h.derpMapURL}); berr == nil {
						h.mu.Lock()
						h.main = back
						h.mu.Unlock()
					}
				}
			}
			return err
		}
		if id.region == nil {
			id.region = next.Region()
		}
	}
	if err := h.save(State{Identity: id.String(), Allowed: keyStrings(keys)}); err != nil {
		if next != nil {
			_ = next.Close()
		}
		h.mu.Lock()
		h.byIP = indexKeys(prev)
		h.mu.Unlock()
		if old != nil && len(keys) > 0 {
			// Bring back what was running before.
			h.mu.Lock()
			h.main = nil
			h.mu.Unlock()
			if back, berr := h.start(context.Background(), nodeSpec{key: id.key, psk: id.psk, region: id.region, allowed: prev,
				port: h.port, onTCP: h.serveMain, derpMapURL: h.derpMapURL}); berr == nil {
				h.mu.Lock()
				h.main = back
				h.mu.Unlock()
			}
		}
		return fmt.Errorf("connecthost: saving state: %w", err)
	}
	if old != nil && len(keys) == 0 {
		_ = old.Close()
	}
	h.mu.Lock()
	h.id, h.main, h.allowed = id, next, slices.Clone(keys)
	h.reindexLocked()
	dropped := h.takeConnsLocked(removedKeys(prev, keys))
	closed := h.closed
	h.mu.Unlock()
	closeConns(dropped)
	if closed && next != nil {
		_ = next.Close()
		return ErrClosed
	}
	return nil
}

// addPhone admits k on the main node, starting it if needed, and returns the main address.
func (h *Host) addPhone(ctx context.Context, k key.NodePublic) (string, error) {
	h.op.Lock()
	defer h.op.Unlock()
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return "", ErrClosed
	}
	main, allowed, id := h.main, slices.Clone(h.allowed), h.id
	h.mu.Unlock()
	if slices.Contains(allowed, k) && main != nil {
		return main.Address(), nil
	}
	if !slices.Contains(allowed, k) {
		if len(allowed) >= MaxPhones {
			return "", fmt.Errorf("connecthost: more than %d phones", MaxPhones)
		}
		allowed = append(allowed, k)
	}
	if main == nil {
		if err := h.restartMain(ctx, allowed); err != nil {
			return "", err
		}
		return h.Address(), nil
	}
	// Running: save first, then admit at runtime so other phones keep their connections.
	if err := h.save(State{Identity: id.String(), Allowed: keyStrings(allowed)}); err != nil {
		return "", fmt.Errorf("connecthost: saving state: %w", err)
	}
	h.mu.Lock()
	h.allowed = allowed
	h.reindexLocked()
	h.mu.Unlock()
	main.Allow(k)
	return main.Address(), nil
}

// SetAllowed replaces the paired phones. Removing a phone this way restarts the main node, which
// drops open connections from every phone; they reconnect on their next dial. To remove one phone
// and keep the others connected, use Revoke.
func (h *Host) SetAllowed(ctx context.Context, phones []string) error {
	keys, err := parseKeys(phones)
	if err != nil {
		return err
	}
	h.op.Lock()
	defer h.op.Unlock()
	h.mu.Lock()
	closed := h.closed
	h.mu.Unlock()
	if closed {
		return ErrClosed
	}
	return h.restartMain(ctx, keys)
}

// Revoke removes one phone. Unknown keys are ignored. It saves the new state first; then it closes
// that phone's connections, including any the app has not accepted yet, and the host refuses its new
// ones. Other phones keep their connections. Removing the last phone stops the main node.
//
// Tailcat cannot forget a client, so the removed phone can still reach the main node until it
// next restarts; the host closes each connection it opens before the app sees it.
func (h *Host) Revoke(ctx context.Context, phone string) error {
	k, err := parsePhone(phone)
	if err != nil {
		return err
	}
	h.op.Lock()
	defer h.op.Unlock()
	h.mu.Lock()
	keep := slices.DeleteFunc(slices.Clone(h.allowed), func(x key.NodePublic) bool { return x == k })
	unchanged, closed, id := len(keep) == len(h.allowed), h.closed, h.id
	h.mu.Unlock()
	if unchanged {
		return nil
	}
	if closed {
		return ErrClosed
	}
	if len(keep) == 0 {
		return h.restartMain(ctx, nil)
	}
	if err := h.save(State{Identity: id.String(), Allowed: keyStrings(keep)}); err != nil {
		return fmt.Errorf("connecthost: saving state: %w", err)
	}
	h.mu.Lock()
	h.allowed = keep
	h.reindexLocked()
	dropped := h.takeConnsLocked(map[string]bool{k.String(): true})
	h.mu.Unlock()
	closeConns(dropped)
	return nil
}

// Address is the main node's Tailcat address, or "" while no phone is paired.
func (h *Host) Address() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.main == nil {
		return ""
	}
	return h.main.Address()
}

// Allowed lists the paired phones' keys.
func (h *Host) Allowed() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return keyStrings(h.allowed)
}

// Name is the name phones see.
func (h *Host) Name() string { return h.name }

// Listener yields connections from paired phones. Use PhoneOf to tell which phone opened one.
func (h *Host) Listener() net.Listener { return h.listener }

// Close stops every node and pairing window and closes the listener.
func (h *Host) Close() error {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil
	}
	h.closed = true
	main := h.main
	h.main = nil
	pairings := make([]*Pairing, 0, len(h.pairings))
	for p := range h.pairings {
		pairings = append(pairings, p)
	}
	h.mu.Unlock()
	for _, p := range pairings {
		p.Close()
	}
	if main != nil {
		_ = main.Close()
	}
	return h.listener.Close()
}

func (h *Host) serveMain(port uint16) func(net.Conn) {
	if port != h.port {
		return nil
	}
	return func(c net.Conn) {
		var ip netip.Addr
		if a, ok := c.RemoteAddr().(*net.TCPAddr); ok {
			ip, _ = netip.AddrFromSlice(a.IP)
		}
		// Look up and register under one lock, so a Revoke either sees this connection or runs first.
		h.mu.Lock()
		phone, ok := h.byIP[ip.Unmap()]
		if !ok {
			h.mu.Unlock()
			_ = c.Close() // not (or no longer) paired: Tailcat may still know the key, connecthost does not
			return
		}
		pc := &Conn{Conn: c, Phone: phone, host: h}
		h.conns[pc] = struct{}{}
		h.mu.Unlock()
		h.listener.offer(pc)
	}
}

func (h *Host) reindex() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.reindexLocked()
}

func (h *Host) reindexLocked() { h.byIP = indexKeys(h.allowed) }

func indexKeys(keys []key.NodePublic) map[netip.Addr]string {
	out := make(map[netip.Addr]string, len(keys))
	for _, k := range keys {
		out[clientIP(k)] = k.String()
	}
	return out
}

// removedKeys lists the keys in prev but not in next.
func removedKeys(prev, next []key.NodePublic) map[string]bool {
	out := map[string]bool{}
	for _, k := range prev {
		if !slices.Contains(next, k) {
			out[k.String()] = true
		}
	}
	return out
}

// takeConnsLocked unregisters and marks the open connections of phones, for closeConns. h.mu must be held.
func (h *Host) takeConnsLocked(phones map[string]bool) []*Conn {
	var out []*Conn
	for c := range h.conns {
		if phones[c.Phone] {
			c.revoked.Store(true)
			delete(h.conns, c)
			out = append(out, c)
		}
	}
	return out
}

func closeConns(cs []*Conn) {
	for _, c := range cs {
		_ = c.Conn.Close()
	}
}

// Conn is a connection from a paired phone.
type Conn struct {
	net.Conn
	Phone string // the phone's key ("nodekey:…")

	host    *Host
	revoked atomic.Bool // the phone was removed; the listener skips it
}

// Close closes the connection.
func (c *Conn) Close() error {
	if c.host != nil {
		c.host.mu.Lock()
		delete(c.host.conns, c)
		c.host.mu.Unlock()
	}
	return c.Conn.Close()
}

// PhoneOf returns the key of the phone that opened c, or "".
func PhoneOf(c net.Conn) string {
	if pc, ok := c.(*Conn); ok {
		return pc.Phone
	}
	return ""
}

// clientIP is the address Tailcat gives a client inside the tunnel: fd7a:115c:a1e0::/48 followed
// by the first 80 bits of its node key (tailcat v0.6.0 tcAddrForKey). Checked against real Tailcat
// by e2e.TestLiveQRPairConnectRestartRevoke (passed 2026-10-29, tailcat v0.6.0): rerun after upgrades.
func clientIP(k key.NodePublic) netip.Addr {
	var a [16]byte
	raw := k.Raw32()
	copy(a[:6], []byte{0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0})
	copy(a[6:], raw[:10])
	return netip.AddrFrom16(a)
}

func keyStrings(keys []key.NodePublic) []string {
	out := make([]string, len(keys))
	for i, k := range keys {
		out[i] = k.String()
	}
	return out
}

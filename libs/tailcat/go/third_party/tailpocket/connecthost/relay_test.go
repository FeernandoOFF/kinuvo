package connecthost

import (
	"testing"

	"github.com/jt-wang/tailpocket"
	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestIPv4OnlyHostAndPairingPreserveIdentity(t *testing.T) {
	previous := tailpocket.IPv4Only
	tailpocket.IPv4Only = true
	t.Cleanup(func() { tailpocket.IPv4Only = previous })

	id := newIdentity()
	id.region = &tailcfg.DERPRegion{RegionID: 1, Nodes: []*tailcfg.DERPNode{{
		Name: "1a", RegionID: 1, HostName: "localhost", IPv4: "127.0.0.1", IPv6: "::1", DERPPort: 1, STUNPort: -1,
	}}}
	var saved State
	host, err := New(Config{
		Identity: id.String(), Allowed: []string{key.NewNode().Public().String()},
		Save: func(s State) error { saved = s; return nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = host.Close() })
	if err := host.Start(t.Context()); err != nil {
		t.Fatal(err)
	}
	pairing, err := host.Pair(t.Context(), PairOptions{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pairing.Close)

	main := host.main.(*tailNode).server.Region.Nodes[0]
	if main.IPv4 != "127.0.0.1" || main.IPv6 != "none" {
		t.Fatalf("main relay has IPv4 %q and IPv6 %q", main.IPv4, main.IPv6)
	}
	info, err := tailcat.ParseAddr(tailcat.Addr(pairing.Invite().Address))
	if err != nil {
		t.Fatal(err)
	}
	invite := info.Region[0].Nodes[0]
	if invite.IPv4 != "127.0.0.1" || invite.IPv6 != "none" {
		t.Fatalf("pairing relay has IPv4 %q and IPv6 %q", invite.IPv4, invite.IPv6)
	}
	persisted, err := parseIdentity(saved.Identity)
	if err != nil {
		t.Fatal(err)
	}
	if got := persisted.region.Nodes[0].IPv6; got != "::1" {
		t.Fatalf("persisted IPv6 = %q, want ::1", got)
	}
}

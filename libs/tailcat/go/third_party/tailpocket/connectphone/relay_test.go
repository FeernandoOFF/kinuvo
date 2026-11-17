package connectphone

import (
	"testing"

	"github.com/jt-wang/tailpocket"
	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestIPv4OnlyClientUsesClonedRegions(t *testing.T) {
	previous := tailpocket.IPv4Only
	tailpocket.IPv4Only = true
	t.Cleanup(func() { tailpocket.IPv4Only = previous })

	info := tailcat.ConnInfo{
		ServerPublic: tailcat.NodePublic{NodePublic: key.NewNode().Public()},
		Region: []*tailcfg.DERPRegion{{RegionID: 1, Nodes: []*tailcfg.DERPNode{
			{Name: "1a", HostName: "one.example.com", IPv4: "192.0.2.10", IPv6: "2001:db8::10"},
			{Name: "1b", HostName: "two.example.com"},
		}}},
	}
	address := info.Addr()
	client, err := relayClient(t.Context(), string(address), key.NewNode(), "")
	if err != nil {
		t.Fatal(err)
	}
	got, err := tailcat.ParseAddr(client.Server)
	if err != nil {
		t.Fatal(err)
	}
	for i, node := range got.Region[0].Nodes {
		want := info.Region[0].Nodes[i]
		if node.IPv4 != want.IPv4 || node.IPv6 != "none" {
			t.Errorf("%s: got IPv4 %q and IPv6 %q, want %q and none", node.HostName, node.IPv4, node.IPv6, want.IPv4)
		}
	}
}

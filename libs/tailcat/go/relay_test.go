package main

import (
	"encoding/json"
	"testing"

	"github.com/jt-wang/tailpocket/connecthost"
	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func dualStack() *tailcfg.DERPRegion {
	return &tailcfg.DERPRegion{RegionID: 1, Nodes: []*tailcfg.DERPNode{{
		Name: "1a", RegionID: 1, HostName: "localhost", IPv4: "127.0.0.1", IPv6: "::1", DERPPort: 1, STUNPort: -1,
	}}}
}

func TestHostDialsIPv4AndSavesItsRegionUnchanged(t *testing.T) {
	k, _ := key.NewNode().MarshalText()
	p, _ := tailcat.NewPresharedKey().MarshalText()
	saved, _ := json.Marshal(identityDoc{V: 1, Key: string(k), PSK: string(p), Region: dualStack()})
	identity, region := ipv4Identity(t.Context(), string(saved))
	var persisted connecthost.State
	host, err := connecthost.New(connecthost.Config{
		Identity: identity, Allowed: []string{key.NewNode().Public().String()},
		Save: func(s connecthost.State) error { s.Identity = withRegion(s.Identity, region); persisted = s; return nil },
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { host.Close() })
	if err := host.Start(t.Context()); err != nil {
		t.Fatal(err)
	}
	pairing, err := host.Pair(t.Context(), connecthost.PairOptions{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pairing.Close)
	for name, address := range map[string]string{"main": host.Address(), "pairing": pairing.Invite().Address} {
		info, err := tailcat.ParseAddr(tailcat.Addr(address))
		if err != nil {
			t.Fatal(err)
		}
		if n := info.Region[0].Nodes[0]; n.IPv4 != "127.0.0.1" || n.IPv6 != "none" {
			t.Fatalf("%s relay has IPv4 %q and IPv6 %q", name, n.IPv4, n.IPv6)
		}
	}
	var doc identityDoc
	if json.Unmarshal([]byte(persisted.Identity), &doc) != nil || doc.Key != string(k) || doc.Region.Nodes[0].IPv6 != "::1" {
		t.Fatalf("persisted %s", persisted.Identity)
	}
}

func TestPhoneDialsIPv4AndKeepsOtherFields(t *testing.T) {
	info := tailcat.ConnInfo{ServerPublic: tailcat.NodePublic{NodePublic: key.NewNode().Public()}, Region: []*tailcfg.DERPRegion{dualStack()}}
	link := `{"address":"` + string(info.Addr()) + `","port":7,"name":"Mac"}`
	var got struct {
		Address, Name string
		Port          int
	}
	if json.Unmarshal([]byte(ipv4Addressed(link)), &got) != nil || got.Port != 7 || got.Name != "Mac" {
		t.Fatalf("fields lost: %+v", got)
	}
	parsed, err := tailcat.ParseAddr(tailcat.Addr(got.Address))
	if err != nil {
		t.Fatal(err)
	}
	if n := parsed.Region[0].Nodes[0]; n.IPv4 != "127.0.0.1" || n.IPv6 != "none" || parsed.ServerPublic != info.ServerPublic {
		t.Fatalf("got %+v", n)
	}
	if ipv4Addressed("nope") != "nope" {
		t.Fatal("malformed link changed")
	}
}

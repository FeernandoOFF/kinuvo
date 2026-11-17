package connectmobile

import (
	"context"
	"testing"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

type ctxT = context.Context

func testAddress(t *testing.T) string {
	t.Helper()
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: key.NewNode().Public()},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: key.NewDisco().Public()},
		PresharedKey:      tailcat.NewPresharedKey(),
		Region:            []*tailcfg.DERPRegion{{RegionID: 3, Nodes: []*tailcfg.DERPNode{{Name: "3a", RegionID: 3, HostName: "derp3.example.com"}}}},
	}
	return string(ci.Addr())
}

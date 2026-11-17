// Package tailpocket configures TailPocket's shared relay behavior.
package tailpocket

import "tailscale.com/tailcfg"

// IPv4Only restricts DERP connections to IPv4. Set it before starting any host or phone.
var IPv4Only bool

// RelayRegion returns the region to use for relay connections.
func RelayRegion(region *tailcfg.DERPRegion) *tailcfg.DERPRegion {
	if !IPv4Only || region == nil {
		return region
	}
	region = region.Clone()
	for _, node := range region.Nodes {
		if node != nil {
			node.IPv6 = "none"
		}
	}
	return region
}

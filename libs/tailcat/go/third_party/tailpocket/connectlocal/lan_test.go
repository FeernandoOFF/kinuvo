package connectlocal

import (
	"net"
	"testing"
)

func TestLANAddressesKeepsOnlyPrivateIPv4OfPhysicalInterfaces(t *testing.T) {
	mask := net.CIDRMask(24, 32)
	in := []iface{
		{"en0", []net.Addr{&net.IPNet{IP: net.ParseIP("192.168.1.20"), Mask: mask}, &net.IPNet{IP: net.ParseIP("fe80::1"), Mask: net.CIDRMask(64, 128)}}},
		{"en1", []net.Addr{&net.IPNet{IP: net.ParseIP("10.0.0.5"), Mask: net.CIDRMask(8, 32)}}},
		{"en2", []net.Addr{&net.IPNet{IP: net.ParseIP("8.8.8.8"), Mask: mask}}},
		{"lo0", []net.Addr{&net.IPNet{IP: net.ParseIP("127.0.0.1"), Mask: net.CIDRMask(8, 32)}}},
		{"utun3", []net.Addr{&net.IPNet{IP: net.ParseIP("10.1.0.2"), Mask: mask}}},
		{"tailscale0", []net.Addr{&net.IPNet{IP: net.ParseIP("100.64.0.1"), Mask: mask}}},
		{"bridge100", []net.Addr{&net.IPNet{IP: net.ParseIP("192.168.64.1"), Mask: mask}}},
		{"awdl0", []net.Addr{&net.IPNet{IP: net.ParseIP("169.254.1.1"), Mask: net.CIDRMask(16, 32)}}},
		{"en5", []net.Addr{&net.IPNet{IP: net.ParseIP("169.254.3.3"), Mask: net.CIDRMask(16, 32)}}},
	}
	got := lanAddresses(in)
	want := []string{"192.168.1.20", "10.0.0.5", "169.254.3.3"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i, b := range got {
		if b.IP.String() != want[i] {
			t.Fatalf("got %v", got)
		}
	}
}

func TestSameSubnet(t *testing.T) {
	b := Binding{IP: net.ParseIP("192.168.1.20").To4(), Mask: net.CIDRMask(24, 32)}
	cases := map[string]bool{"192.168.1.99": true, "192.168.2.1": false, "10.0.0.1": false, "::ffff:192.168.1.7": true, "fe80::1": false, "": false}
	for ip, want := range cases {
		if got := b.Contains(net.ParseIP(ip)); got != want {
			t.Errorf("%s: %v", ip, got)
		}
	}
}

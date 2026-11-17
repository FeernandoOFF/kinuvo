package connectmobile

import (
	"net"
	"testing"
)

func TestInterfacesFromRowsGroupsByNameAndMapsFlags(t *testing.T) {
	rows := []ifaddrRow{
		{name: "wlan0", index: 3, flags: 0x1 | 0x2 | 0x40 | 0x1000},
		{name: "wlan0", index: 3, flags: 0x1, ip: net.IPv4(192, 168, 1, 5), mask: net.CIDRMask(24, 32)},
		{name: "lo", index: 1, flags: 0x1 | 0x8, ip: net.IPv4(127, 0, 0, 1), mask: net.CIDRMask(8, 32)},
		{name: "rmnet0", index: 4, flags: 0x1 | 0x10},
	}
	got := interfacesFromRows(rows)
	if len(got) != 3 || got[0].Name != "wlan0" || len(got[0].AltAddrs) != 1 || got[2].AltAddrs == nil {
		t.Fatalf("%+v", got)
	}
	if got[0].Flags&net.FlagMulticast == 0 || got[1].Flags&net.FlagLoopback == 0 || got[2].Flags&net.FlagPointToPoint == 0 {
		t.Fatal("flags")
	}
}

package main

/*
#include <stdlib.h>
char *kinuvo_tailcat_interfaces(void);
*/
import "C"

import (
	"encoding/json"
	"errors"
	"net"
	"unsafe"

	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		value := C.kinuvo_tailcat_interfaces()
		if value == nil {
			return nil, errors.New("Android network interfaces unavailable")
		}
		defer C.free(unsafe.Pointer(value))
		var interfaces []struct {
			Name      string
			Index     int
			MTU       int
			Up        bool
			Loopback  bool
			Multicast bool
			Addresses []string
		}
		if err := json.Unmarshal([]byte(C.GoString(value)), &interfaces); err != nil {
			return nil, errors.New("invalid Android network interfaces")
		}
		result := make([]netmon.Interface, 0, len(interfaces))
		for _, item := range interfaces {
			flags := net.Flags(0)
			if item.Up {
				flags |= net.FlagUp | net.FlagRunning
			}
			if item.Loopback {
				flags |= net.FlagLoopback
			}
			if item.Multicast {
				flags |= net.FlagMulticast
			}
			addresses := make([]net.Addr, 0, len(item.Addresses))
			for _, address := range item.Addresses {
				ip, network, err := net.ParseCIDR(address)
				if err != nil {
					return nil, errors.New("invalid Android interface address")
				}
				network.IP = ip
				addresses = append(addresses, network)
			}
			result = append(result, netmon.Interface{Interface: &net.Interface{Name: item.Name, Index: item.Index, MTU: item.MTU, Flags: flags}, AltAddrs: addresses})
		}
		return result, nil
	})
}

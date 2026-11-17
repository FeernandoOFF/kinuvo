package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"sync"
	"unsafe"

	"github.com/jt-wang/tailpocket/connectmobile"
)

const abiVersion = 2

type request struct {
	State     string `json:"state"`
	Name      string `json:"name"`
	Port      int    `json:"port"`
	Handle    uint64 `json:"handle"`
	Pairing   uint64 `json:"pairing"`
	Code      string `json:"code"`
	Payload   string `json:"payload"`
	TTL       int    `json:"ttl"`
	Phone     string `json:"phone"`
	Invite    string `json:"invite"`
	Device    string `json:"device"`
	Op        int64  `json:"op"`
	TimeoutMs int    `json:"timeoutMs"`
	Link      string `json:"link"`
}

type response struct {
	Handle    uint64 `json:"handle,omitempty"`
	Port      int    `json:"port,omitempty"`
	Pairing   uint64 `json:"pairing,omitempty"`
	Invite    string `json:"invite,omitempty"`
	ExpiresAt int64  `json:"expiresAt,omitempty"`
	Phone     string `json:"phone,omitempty"`
	Open      bool   `json:"open,omitempty"`
	Link      string `json:"link,omitempty"`
	Payload   string `json:"payload,omitempty"`
	Name      string `json:"name,omitempty"`
	Error     string `json:"error,omitempty"`
}

var registry = struct {
	sync.Mutex
	next  uint64
	items map[uint64]stopper
}{items: make(map[uint64]stopper)}

func register(item stopper) uint64 {
	registry.Lock()
	defer registry.Unlock()
	registry.next++
	registry.items[registry.next] = item
	return registry.next
}

func lookupHost(handle uint64) *hostTunnel {
	registry.Lock()
	defer registry.Unlock()
	h, _ := registry.items[handle].(*hostTunnel)
	return h
}

func dispatch(method string, r request) response {
	switch method {
	case "reset":
		if err := resetState(r.State); err != nil {
			return response{Error: err.Error()}
		}
		return response{}
	case "revoke":
		if err := revokeOffline(r.State, r.Phone); err != nil {
			return response{Error: err.Error()}
		}
		return response{}
	case "stop":
		registry.Lock()
		item := registry.items[r.Handle]
		delete(registry.items, r.Handle)
		registry.Unlock()
		if item != nil {
			if err := item.stop(); err != nil {
				return response{Error: err.Error()}
			}
		}
		return response{}
	case "host_start":
		h, err := startHost(r)
		if err != nil {
			return response{Error: err.Error()}
		}
		return response{Handle: register(h)}
	case "host_pair", "host_pair_open", "host_close_pair", "host_phone", "host_revoke":
		h := lookupHost(r.Handle)
		if h == nil {
			return response{Error: "Tailcat is not running"}
		}
		switch method {
		case "host_pair":
			return h.pair(r)
		case "host_pair_open":
			return response{Open: h.pairingOpen(r.Pairing)}
		case "host_close_pair":
			h.closePairing(r.Pairing)
		case "host_phone":
			return response{Phone: h.phoneOf(r.Code)}
		case "host_revoke":
			if err := h.revoke(r.Phone); err != nil {
				return response{Error: err.Error()}
			}
		}
		return response{}
	case "pair":
		return pairPhone(r)
	case "cancel":
		connectmobile.Cancel(r.Op)
		return response{}
	case "forward":
		f, port, err := forward(r)
		if err != nil {
			return response{Error: err.Error()}
		}
		return response{Handle: register(f), Port: port}
	default:
		return response{Error: "unknown method"}
	}
}

//export kinuvo_tailcat_abi_version
func kinuvo_tailcat_abi_version() C.int { return abiVersion }

//export kinuvo_tailcat_call
func kinuvo_tailcat_call(method, params *C.char) (ret *C.char) {
	defer func() {
		if recover() != nil {
			ret = C.CString(`{"error":"Tailcat operation failed"}`)
		}
	}()
	var r request
	if method == nil || params == nil || json.Unmarshal([]byte(C.GoString(params)), &r) != nil {
		return C.CString(`{"error":"invalid request"}`)
	}
	data, _ := json.Marshal(dispatch(C.GoString(method), r))
	return C.CString(string(data))
}

//export kinuvo_tailcat_free
func kinuvo_tailcat_free(value *C.char) { C.free(unsafe.Pointer(value)) }

func main() {}

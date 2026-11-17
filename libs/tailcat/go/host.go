package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"

	"github.com/jt-wang/tailpocket/connecthost"
)

const (
	invitePrefix = "kinuvo1:"
	stateFile    = "state.json"
	maxBound     = 64
)

type hostTunnel struct {
	*tunnel
	host  *connecthost.Host
	pmu   sync.Mutex
	next  uint64
	pairs map[uint64]*connecthost.Pairing
	bound map[string]string
}

func loadState(dir string) (connecthost.State, error) {
	var st connecthost.State
	path := filepath.Join(dir, stateFile)
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return st, nil
	}
	if err != nil {
		return st, errors.New("cannot read saved state")
	}
	if json.Unmarshal(data, &st) != nil || st.Identity == "" {
		return st, errors.New("invalid saved state")
	}
	if os.Chmod(path, 0600) != nil {
		return st, errors.New("cannot secure saved state")
	}
	return st, nil
}

func saveState(dir string, st connecthost.State) error {
	data, err := json.Marshal(st)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, "state-")
	if err != nil {
		return errors.New("cannot create state file")
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	if e := f.Close(); err == nil {
		err = e
	}
	if err == nil {
		err = os.Rename(f.Name(), filepath.Join(dir, stateFile))
	}
	if err != nil {
		return errors.New("cannot save state")
	}
	return nil
}

func startHost(r request) (_ *hostTunnel, err error) {
	if r.Port < 1 || r.Port > 65535 {
		return nil, errors.New("invalid loopback port")
	}
	lock, err := hold(r.State)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	t := newTunnel(cancel)
	t.lock = lock
	ready := false
	defer func() {
		if !ready {
			t.stop()
		}
	}()
	st, err := loadState(r.State)
	if err != nil {
		return nil, err
	}
	h, err := connecthost.New(connecthost.Config{
		Name: r.Name, Identity: st.Identity, Allowed: st.Allowed,
		Save: func(s connecthost.State) error { return saveState(r.State, s) },
	})
	if err != nil {
		return nil, errors.New("invalid saved state")
	}
	t.listener = h.Listener()
	t.closeNative = h.Close
	start, done := context.WithTimeout(ctx, 30*time.Second)
	defer done()
	if err = h.Start(start); err != nil {
		debugLog("host start failed: %v", err)
		return nil, startupError(err)
	}
	go t.accept(func() (net.Conn, error) {
		return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", fmt.Sprintf("127.0.0.1:%d", r.Port))
	})
	ready = true
	return &hostTunnel{tunnel: t, host: h, pairs: map[uint64]*connecthost.Pairing{}, bound: map[string]string{}}, nil
}

func (h *hostTunnel) bind(code, phone string) {
	h.pmu.Lock()
	defer h.pmu.Unlock()
	if len(h.bound) >= maxBound {
		clear(h.bound)
	}
	h.bound[code] = phone
}

func (h *hostTunnel) phoneOf(code string) string {
	h.pmu.Lock()
	defer h.pmu.Unlock()
	phone := h.bound[code]
	delete(h.bound, code)
	return phone
}

func (h *hostTunnel) pair(r request) response {
	if r.Code == "" {
		return response{Error: "pairing code required"}
	}
	ctx, done := context.WithTimeout(context.Background(), 30*time.Second)
	defer done()
	p, err := h.host.Pair(ctx, connecthost.PairOptions{
		TTL: time.Duration(r.TTL) * time.Second,
		Payload: func(phone connecthost.Phone) (string, error) {
			h.bind(r.Code, phone.Key)
			return r.Payload, nil
		},
	})
	if err != nil {
		debugLog("pair failed: %v", err)
		return response{Error: startupError(err).Error()}
	}
	invite, err := p.QR(invitePrefix)
	if err != nil {
		p.Close()
		return response{Error: "cannot encode the pairing invite"}
	}
	h.pmu.Lock()
	h.next++
	id := h.next
	h.pairs[id] = p
	h.pmu.Unlock()
	go func() {
		<-p.Done()
		h.pmu.Lock()
		delete(h.pairs, id)
		h.pmu.Unlock()
	}()
	return response{Pairing: id, Invite: invite, ExpiresAt: p.Expires().Unix()}
}

func (h *hostTunnel) closePairing(id uint64) {
	h.pmu.Lock()
	p := h.pairs[id]
	h.pmu.Unlock()
	if p != nil {
		p.Close()
	}
}

func (h *hostTunnel) revoke(phone string) error {
	ctx, done := context.WithTimeout(context.Background(), 30*time.Second)
	defer done()
	if err := h.host.Revoke(ctx, phone); err != nil {
		return errors.New("cannot revoke the paired phone")
	}
	return nil
}

func revokeOffline(dir, phone string) error {
	lock, err := hold(dir)
	if err != nil {
		return err
	}
	defer release(lock)
	st, err := loadState(dir)
	if err != nil || st.Identity == "" {
		return err
	}
	kept := slices.DeleteFunc(slices.Clone(st.Allowed), func(p string) bool { return p == phone })
	if len(kept) == len(st.Allowed) {
		return nil
	}
	st.Allowed = kept
	return saveState(dir, st)
}

func resetState(dir string) error {
	lock, err := hold(dir)
	if err != nil {
		return err
	}
	defer release(lock)
	for _, name := range []string{stateFile, "key.json"} {
		if err = os.Remove(filepath.Join(dir, name)); err != nil && !os.IsNotExist(err) {
			return errors.New("cannot remove saved state")
		}
	}
	return nil
}

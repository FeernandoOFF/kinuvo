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
	revokedFile  = "revoked.json"
	maxBound     = 64
)

type hostTunnel struct {
	*tunnel
	host  *connecthost.Host
	pmu   sync.Mutex
	next  uint64
	pairs map[uint64]*connecthost.Pairing
	bound map[string]string

	state   string
	rmu     sync.Mutex
	revoked []string
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
	return writeAtomic(dir, stateFile, data)
}

func loadRevoked(dir string) ([]string, error) {
	data, err := os.ReadFile(filepath.Join(dir, revokedFile))
	if os.IsNotExist(err) {
		return nil, nil
	}
	var phones []string
	if err != nil || json.Unmarshal(data, &phones) != nil {
		return nil, errors.New("invalid saved revocations")
	}
	return phones, nil
}

func saveRevoked(dir string, phones []string) error {
	data, err := json.Marshal(phones)
	if err != nil {
		return err
	}
	return writeAtomic(dir, revokedFile, data)
}

func writeAtomic(dir, name string, data []byte) error {
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
		err = os.Rename(f.Name(), filepath.Join(dir, name))
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
	revoked, err := loadRevoked(r.State)
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
	ht := &hostTunnel{tunnel: t, host: h, pairs: map[uint64]*connecthost.Pairing{}, bound: map[string]string{}, state: r.State, revoked: revoked}
	t.refuse = ht.refuseRevoked
	go t.accept(func() (net.Conn, error) {
		return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", fmt.Sprintf("127.0.0.1:%d", r.Port))
	})
	ready = true
	return ht, nil
}

func (h *hostTunnel) isRevoked(phone string) bool {
	h.rmu.Lock()
	defer h.rmu.Unlock()
	return phone != "" && slices.Contains(h.revoked, phone)
}

// refuseRevoked answers a revoked phone like a revoked Device and reports whether c was one.
func (h *hostTunnel) refuseRevoked(c net.Conn) bool {
	if !h.isRevoked(connecthost.PhoneOf(c)) {
		return false
	}
	answerRevoked(c)
	return true
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
	h.prune(ctx, r.BoundPhones)
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

func (h *hostTunnel) pairingOpen(id uint64) bool {
	h.pmu.Lock()
	defer h.pmu.Unlock()
	_, ok := h.pairs[id]
	return ok
}

func (h *hostTunnel) closePairing(id uint64) {
	h.pmu.Lock()
	p := h.pairs[id]
	h.pmu.Unlock()
	if p != nil {
		p.Close()
	}
}

// revoke keeps the phone allowed at the Tailcat level, so its connections still reach this process and get
// refused with an answer; connecthost.Revoke would close them silently, which looks like a Server that is down.
func (h *hostTunnel) revoke(phone string) error {
	if !slices.Contains(h.host.Allowed(), phone) {
		return nil
	}
	h.rmu.Lock()
	if !slices.Contains(h.revoked, phone) {
		h.revoked = append(h.revoked, phone)
	}
	saved := slices.Clone(h.revoked)
	h.rmu.Unlock()
	// Let the existing bridge deliver the revoke RPC response after this call returns.
	defer time.AfterFunc(time.Second, func() {
		defer func() {
			if v := recover(); v != nil {
				debugLog("native panic: %v", v)
			}
		}()
		h.closePhone(phone)
	})
	if err := saveRevoked(h.state, saved); err != nil {
		return errors.New("cannot save the revoked phone")
	}
	return nil
}

// prune frees a slot when full, preferring revoked phones and then the oldest orphan.
func (h *hostTunnel) prune(ctx context.Context, bound []string) {
	allowed := h.host.Allowed()
	if len(allowed) < connecthost.MaxPhones {
		return
	}
	h.rmu.Lock()
	index := slices.IndexFunc(h.revoked, func(phone string) bool {
		return slices.Contains(allowed, phone) && !slices.Contains(bound, phone)
	})
	var oldest string
	if index >= 0 {
		oldest = h.revoked[index]
	}
	h.rmu.Unlock()
	if oldest == "" {
		index = slices.IndexFunc(allowed, func(phone string) bool { return !slices.Contains(bound, phone) })
		if index < 0 {
			return
		}
		oldest = allowed[index]
	}

	if err := h.host.Revoke(ctx, oldest); err != nil {
		debugLog("prune failed: %v", err)
		return
	}
	h.rmu.Lock()
	defer h.rmu.Unlock()
	if index := slices.Index(h.revoked, oldest); index >= 0 {
		h.revoked = slices.Delete(h.revoked, index, index+1)
		saveRevoked(h.state, h.revoked)
	}
}

func revokeOffline(dir, phone string) error {
	lock, err := hold(dir)
	if err != nil {
		return err
	}
	defer release(lock)
	st, err := loadState(dir)
	if err != nil || st.Identity == "" || !slices.Contains(st.Allowed, phone) {
		return err
	}
	revoked, err := loadRevoked(dir)
	if err != nil || slices.Contains(revoked, phone) {
		return err
	}
	return saveRevoked(dir, append(revoked, phone))
}

func resetState(dir string) error {
	lock, err := hold(dir)
	if err != nil {
		return err
	}
	defer release(lock)
	for _, name := range []string{stateFile, revokedFile, "key.json"} {
		if err = os.Remove(filepath.Join(dir, name)); err != nil && !os.IsNotExist(err) {
			return errors.New("cannot remove saved state")
		}
	}
	return nil
}

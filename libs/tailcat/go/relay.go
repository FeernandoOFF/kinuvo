package main

import (
	"context"
	"encoding/json"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// Relays are dialed over IPv4 only: phone hosts on dual-stack networks dropped off their IPv6 relay connection.

type identityDoc struct {
	V      int                 `json:"v"`
	Key    string              `json:"key"`
	PSK    string              `json:"psk"`
	Region *tailcfg.DERPRegion `json:"region,omitempty"`
}

func ipv4Region(r *tailcfg.DERPRegion) *tailcfg.DERPRegion {
	r = r.Clone()
	for _, n := range r.Nodes {
		if n != nil {
			n.IPv6 = "none"
		}
	}
	return r
}

// ipv4Identity returns the host identity to start with, and the region to save in its place.
// A new identity gets its region picked here, since TailPocket would pick one with IPv6; offline, TailPocket picks later.
func ipv4Identity(ctx context.Context, text string) (string, *tailcfg.DERPRegion) {
	var doc identityDoc
	if text == "" {
		k, _ := key.NewNode().MarshalText()
		p, _ := tailcat.NewPresharedKey().MarshalText()
		doc = identityDoc{V: 1, Key: string(k), PSK: string(p)}
	} else if json.Unmarshal([]byte(text), &doc) != nil || doc.V != 1 {
		return text, nil
	}
	if doc.Region == nil {
		info := tailcat.ConnInfo{RegionID: -1}
		if err := info.Expand(ctx, tailcat.ExpandForServer); err != nil {
			debugLog("relay region not picked: %v", err)
			return text, nil
		}
		doc.Region = info.Region[0]
	}
	saved := doc.Region
	doc.Region = ipv4Region(saved)
	body, _ := json.Marshal(doc)
	return string(body), saved
}

// withRegion puts region back into an identity TailPocket saves.
func withRegion(text string, region *tailcfg.DERPRegion) string {
	var doc identityDoc
	if region == nil || json.Unmarshal([]byte(text), &doc) != nil || doc.V != 1 {
		return text
	}
	doc.Region = region
	body, _ := json.Marshal(doc)
	return string(body)
}

func ipv4Address(ctx context.Context, address string) (string, error) {
	info, err := tailcat.ParseAddr(tailcat.Addr(address))
	if err != nil {
		return "", err
	}
	if err := info.Expand(ctx); err != nil {
		return "", err
	}
	for i, r := range info.Region {
		info.Region[i] = ipv4Region(r)
	}
	info.RegionID = 0
	return string(info.Addr()), nil
}

// ipv4Addressed rewrites the "address" field of an invite or link; TailPocket reports any malformed input itself.
func ipv4Addressed(text string) string {
	var doc map[string]json.RawMessage
	var address string
	if json.Unmarshal([]byte(text), &doc) != nil || json.Unmarshal(doc["address"], &address) != nil {
		return text
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	address, err := ipv4Address(ctx, address)
	if err != nil {
		debugLog("relay address kept: %v", err)
		return text
	}
	doc["address"], _ = json.Marshal(address)
	body, _ := json.Marshal(doc)
	return string(body)
}

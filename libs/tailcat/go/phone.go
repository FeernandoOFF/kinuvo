package main

import (
	"encoding/json"
	"errors"
	"strings"

	"github.com/jt-wang/tailpocket/connectmobile"
)

type forwardHandle struct{ handle int64 }

func (f forwardHandle) stop() error {
	connectmobile.Stop(f.handle)
	return nil
}

var pairErrors = map[string]string{
	"invalid-invite": "That pairing link is not valid",
	"invalid-key":    "Cannot pair with this private address",
	"invalid-link":   "The saved private address is not valid; pair again",
	"expired":        "That pairing link has expired; show a new one",
	"unauthorized":   "The Server rejected this pairing link",
	"taken":          "Another device already used this pairing link",
	"unreachable":    "Could not reach the Server",
	"timeout":        "Pairing timed out",
	"cancelled":      "Pairing was cancelled",
}

func nativeError(text string) string {
	var failure struct {
		Error string `json:"error"`
	}
	if json.Unmarshal([]byte(text), &failure) != nil || failure.Error == "" {
		return ""
	}
	if message, ok := pairErrors[failure.Error]; ok {
		return message
	}
	return "Tailcat could not complete the request"
}

func pairPhone(r request) response {
	invite := connectmobile.DecodeInvite(strings.TrimSpace(r.Invite), invitePrefix)
	if message := nativeError(invite); message != "" {
		return response{Error: message}
	}
	timeout := r.TimeoutMs
	if timeout <= 0 {
		timeout = 60_000
	}
	link := connectmobile.Pair(r.Op, invite, connectmobile.NewKey(), r.Device, "", timeout)
	if message := nativeError(link); message != "" {
		return response{Error: message}
	}
	var parsed struct{ Name, Payload string }
	if json.Unmarshal([]byte(link), &parsed) != nil {
		return response{Error: "Tailcat could not complete the request"}
	}
	return response{Link: link, Payload: parsed.Payload, Name: parsed.Name}
}

func forward(r request) (forwardHandle, int, error) {
	text := connectmobile.Connect(r.Link, "")
	if message := nativeError(text); message != "" {
		return forwardHandle{}, 0, errors.New(message)
	}
	var opened struct {
		Handle int64
		Port   int
	}
	if json.Unmarshal([]byte(text), &opened) != nil || opened.Port == 0 {
		return forwardHandle{}, 0, errors.New("Tailcat could not complete the request")
	}
	return forwardHandle{opened.Handle}, opened.Port, nil
}

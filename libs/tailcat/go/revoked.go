package main

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	revokedMessage = "This device was unpaired"
	wsGUID         = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
	maxRequest     = 16 << 10
	maxFrame       = 4096
)

// answerRevoked replies to a revoked phone the way a Server replies to a revoked Device credential: the /ws upgrade
// succeeds and the first request frame fails with a forbidden error. Any other request gets a plain 401.
// It never touches the Server.
func answerRevoked(c net.Conn) {
	c.SetDeadline(time.Now().Add(5 * time.Second))
	br := bufio.NewReader(io.LimitReader(c, maxRequest))
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	key := req.Header.Get("Sec-WebSocket-Key")
	if key == "" || !strings.EqualFold(req.Header.Get("Upgrade"), "websocket") {
		io.WriteString(c, "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain\r\nContent-Length: "+strconv.Itoa(len(revokedMessage))+"\r\nConnection: close\r\n\r\n"+revokedMessage)
		return
	}
	sum := sha1.Sum([]byte(key + wsGUID))
	io.WriteString(c, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+base64.StdEncoding.EncodeToString(sum[:])+"\r\n\r\n")
	id := firstRequestID(br)
	if id != "" {
		body, _ := json.Marshal(map[string]any{"type": "res", "id": id, "ok": false, "error": map[string]string{"code": "forbidden", "message": revokedMessage}})
		writeFrame(c, 1, body)
	}
	writeFrame(c, 8, []byte{0x03, 0xe8})
}

// firstRequestID reads client frames until a text request, and returns its id.
func firstRequestID(r io.Reader) string {
	for range 4 {
		opcode, payload, ok := readFrame(r)
		if !ok || opcode == 8 {
			return ""
		}
		if opcode != 1 {
			continue
		}
		var frame struct {
			Type string `json:"type"`
			ID   string `json:"id"`
		}
		if json.Unmarshal(payload, &frame) == nil && frame.Type == "req" {
			return frame.ID
		}
		return ""
	}
	return ""
}

func readFrame(r io.Reader) (opcode byte, payload []byte, ok bool) {
	var head [2]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return 0, nil, false
	}
	size := uint64(head[1] & 0x7f)
	switch size {
	case 126:
		var ext [2]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return 0, nil, false
		}
		size = uint64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err := io.ReadFull(r, ext[:]); err != nil {
			return 0, nil, false
		}
		size = binary.BigEndian.Uint64(ext[:])
	}
	if size > maxFrame || head[1]&0x80 == 0 {
		return 0, nil, false
	}
	var mask [4]byte
	if _, err := io.ReadFull(r, mask[:]); err != nil {
		return 0, nil, false
	}
	payload = make([]byte, size)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, false
	}
	for i := range payload {
		payload[i] ^= mask[i%4]
	}
	return head[0] & 0x0f, payload, true
}

func writeFrame(w io.Writer, opcode byte, payload []byte) {
	head := []byte{0x80 | opcode}
	switch n := len(payload); {
	case n < 126:
		head = append(head, byte(n))
	default:
		head = append(head, 126, byte(n>>8), byte(n))
	}
	w.Write(append(head, payload...))
}

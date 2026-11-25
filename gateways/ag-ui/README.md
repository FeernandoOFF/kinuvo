# gateways/ag-ui

Draft HTTP+SSE gateway that serves one Kinuvo Agent to AG-UI clients (ADR 0042). It is a client of a running Server: `POST /` takes a `RunAgentInput` and streams that run's AG-UI events as `text/event-stream`.

## Try it

```
./kotlin run -m fixture-server -- agui --port=18085 --agui=18086
```

Or against a real Server: `kinuvo agui --agent <id> [--host 127.0.0.1] [--port 8790] [--allow-origin <origin>]...`. The fixture takes `--agui-allow-origin=<origin>` the same way.

Point `@ag-ui/client` (`new HttpAgent({ url: "http://127.0.0.1:18086/" })`) or CopilotKit's `HttpAgent` runtime at it. Fixture phrases: "Deploy" parks on a `tool_call` interrupt, "Ask me" on `input_required`, anything else replies.

Resuming sends `resume: [{interruptId, status, payload}]`. Approvals take `payload.decision` (`allow_once`, `allow_session`, `always_allow`, `deny`, optional `note`); a cancelled interrupt is denied or skipped. A missing or unknown `decision` is an error, never an approval. `resume` must answer every open interrupt of the thread, otherwise the run fails at once.

A send streams the run the Server started for it. A message queued behind an open interrupt or another run waits for its own run and reports an error if it has not started in time.

## Limitations

- Draft: the thread-to-Session map is in memory, so a restart forgets threads.
- Ignores `tools`, `context`, `state` and `forwardedProps`; only the last user message is sent.
- One Agent per gateway and no authentication; protect it with the rules below.

## Security

`kinuvo agui` acts through a management-authorized Server session, so any caller can drive the Agent. The gateway therefore:

- binds loopback addresses only and refuses any other `--host`;
- rejects (403) requests whose `Host` is not the loopback host and port it serves, which blocks DNS rebinding;
- rejects (403) requests that carry an `Origin` header unless it was passed with `--allow-origin`, and sends CORS headers only to those origins; server-side clients such as the CopilotKit runtime send no `Origin` and need no flag;
- rejects (415) bodies that are not `Content-Type: application/json`, so a cross-site form POST cannot reach it.

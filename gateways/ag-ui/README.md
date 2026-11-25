# gateways/ag-ui

Draft HTTP+SSE gateway that serves one Kinuvo Agent to AG-UI clients (ADR 0042). It is a client of a running Server: `POST /` takes a `RunAgentInput` and streams that run's AG-UI events as `text/event-stream`.

## Try it

```
./kotlin run -m fixture-server -- agui --port=18085 --agui=18086
```

Or against a real Server: `kinuvo agui --agent <id> [--host 127.0.0.1] [--port 8790]`.

Point `@ag-ui/client` (`new HttpAgent({ url: "http://127.0.0.1:18086/" })`) or CopilotKit's `HttpAgent` runtime at it. Fixture phrases: "Deploy" parks on a `tool_call` interrupt, "Ask me" on `input_required`, anything else replies.

Resuming sends `resume: [{interruptId, status, payload}]`. Approvals take `payload.decision` (`allow_once`, `allow_session`, `always_allow`, `deny`, optional `note`); a cancelled interrupt is denied or skipped.

## Limitations

- Draft: the thread-to-Session map is in memory, so a restart forgets threads.
- Ignores `tools`, `context`, `state` and `forwardedProps`; only the last user message is sent.
- One Agent per gateway, no authentication, CORS open for local development.

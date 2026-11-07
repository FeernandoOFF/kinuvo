---
title: "Local Agent traces"
description: "Configure and use local agent traces."
---

Desktop, CLI and Android Servers save diagnostic traces for Agent turns and Reflection in `<Server home>/traces/spans-0.jsonl` through `spans-3.jsonl`. Files rotate at about 4 MiB each, retaining about 16 MiB. Traces are flushed when a run ends.

Traces include Agent, Session and run IDs, request kind, graph steps, model and tool names, timings, token counts when available, and success/error status. Reflection uses its source Session's ID and a separate run ID. Koog 1.3 needs lifecycle forwarding for streaming inference and Kinuvo's controlled tool calls; Koog creates all spans.

Verbose capture is off. The export filter keeps only metadata attributes and removes events, links and status descriptions. Prompts, replies, reasoning, tool arguments/results/descriptions, attachments, response metadata and exception messages are excluded. There is no remote exporter or collector endpoint. iOS currently uses a no-op adapter.

Tracing is on by default. To disable it, set this in the Server's `config.yaml` and restart the Server:

```yaml
tracing: false
```

The logging exporter dependency is pinned because its configurable output builder is an internal OTel API. Recheck file output and privacy tests when upgrading Koog or OpenTelemetry.

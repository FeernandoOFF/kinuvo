# GraalVM CLI spike (macOS arm64)

Build with a local GraalVM JDK 25, without changing mise configuration:

```sh
mise install java@graalvm-community-25.0.2
export GRAALVM_HOME="$(mise where java@graalvm-community-25.0.2)"
/usr/bin/time -l scripts/build-cli-native.sh /absolute/path/to/kinuvo.jar
```

The script writes `build/native-image/kinuvo`, caps the builder at 8 GB heap and
four workers, and embeds the CLI EventKit usage keys. It uses an existing CLI
executable jar; it never builds Kotlin or changes the regular packaging path.
This is a macOS experiment, not a portable distribution build.

The input jar must already contain all runtime dependencies. The regular
`scripts/package.sh` restores nested dependency filename collisions; the native
script does not reconstruct missing dependencies. Root resources (`web`,
`server-voice`, `kinuvo-version`) and BOOT-INF classes are both on its classpath.
Being on the classpath does not guarantee their inclusion in the image.

To refresh metadata, unpack the jar and run GraalVM Java with
`--enable-native-access=ALL-UNNAMED`,
`-agentlib:native-image-agent=config-output-dir=<new-directory>` and
`-cp 'build/native-image/flat/BOOT-INF/classes:build/native-image/flat/BOOT-INF/lib/*:build/native-image/flat'`.
Use `kinuvo.cli.MainKt --help` and `serve --port 8799`; set `KINUVO_HOME` to a
fresh disposable directory with `config.yaml` containing:

```yaml
tasks:
  provider: local
```

Exercise `agents list` and `tasks list` in separate processes with the same home.
Stop the Server before merging each trace with `native-image-configure generate
--input-dir=... --input-dir=... --output-dir=...`. Do not concurrently write to
the same metadata directory. Do not request Reminders or Calendar access.

The committed metadata records only the exercised paths described below. Keep
tracing homes, tokens and binary artifacts out of Git.

## Current-source verification (November 26, 2026)

Input: fresh portable jar built from main 9e0965e8 plus the on-demand LiteRT
packaging fix in PR #256. JBR SDK 25.0.5.1, GraalVM CE 25.0.2; macOS arm64.
The jar is 166,674,334 bytes, down from 300,460,384 before stripping LiteRT
natives. LiteRT API remains (436,627 bytes), with no bundled engine libraries.
No engine or model was downloaded.

The expanded image is 196,575,288 bytes. Build wall time: 206.35 seconds;
maximum RSS: 3,898,851,328 bytes. Native-image reports 64.84 MB code,
129.83 MB heap (627,063 objects, 369 resources), and 1.91 MB other data.
It does not separately report resource bytes. The packaged web tree is
47,599,847 uncompressed bytes; serving all its files from the image confirms
inclusion. The earlier 143 MB image did not establish web-resource coverage.
Analysis registered 8,223 reflection types, 144 JNI types, and three FFM downcalls.

One build at a time, with 8 GB builder heap and four workers. A first merge
refused an interrupted trace's lock file. Its accidentally started builder was
stopped; the successful merge excluded that locked directory and combined 132
completed trace directories with existing metadata using native-image-configure.
No initialization overrides or additional native-image flags were required.

The flat classpath tracing run served the web assets but management connections
timed out under the tracing overhead. The CLI prints that error with exit 0, so
exit status alone is insufficient. Tracing the packaged jar uses Apache rather
than the flat classpath's Java HTTP client engine and succeeded. Agent metadata
still emitted the previously observed Kotlin resource-processing warning.
The corrected ACP v2 initialize envelope uses `info` and `capabilities`.

Full verification ran sequentially after compilation, once on JBR and then on
the native image, each using a new temporary home, port 8799 and local Tasks.
The personal home was not modified; no service registration, device pairing,
Calendar or Reminders permission request was involved. Status probes can still
see OS-level service and secret-provider metadata outside the isolated home. Every started Server and local stub stopped.

| Observation | JBR jar | Native |
| --- | ---: | ---: |
| Root and every packaged web file | 181 HTTP 200 responses | 181 HTTP 200 responses |
| /agents, /settings, /sessions | 404 each | 404 each |
| Initial Server launch to HTTP root | 7.722 s | 5.583 s |
| Initial Server RSS | 279,808 KiB | 110,128 KiB |
| Tailcat startup to active log | 8.746 s | 6.600 s |
| Tailcat Server RSS | 395,440 KiB | 244,144 KiB |
| Computer startup | 7.660 s | 5.556 s |
| Computer Server RSS | 396,176 KiB | 227,600 KiB |

These are single runs, including polling granularity and cache effects, not
controlled speedup benchmarks. RSS was sampled immediately after startup.
No JVM measurements overlapped native compilation.

Real scenarios passed on both runtimes: Agent create/change/show/delete;
Routine create/list/show/pause/resume/run/runs/remove; Skill create/list/show/remove
in both scopes; local stdio MCP add/list/test/remove, discovering the echo tool;
Knowledge search and changes; Task lists/add/list/assign/status; secret-provider
status and browse; notification error; pair and pair --manage; Device list;
service status; tailcat-reset with the Server stopped; ACP initialize, session/new
and session/prompt. A local OpenAI-compatible stub received streaming requests,
and the ACP client received SPIKE_REPLY on both runtimes. The CLI cannot directly
invoke an arbitrary MCP tool; MCP tool invocation was not tested. Routine run
was accepted; completion of its scheduled work was not separately asserted.

Notifications reported that no paired Device receives pushes. Secret status
and browse read existing provider metadata and vault names through the OS
credential store; a temporary home does not isolate that store. No sign-in,
secret-value retrieval or manager mutation was performed. Login and service mutation commands were help-only.
The direct SPA routes return 404 equally on both runtimes; this is existing
Server behavior, not a native resource regression. Successful web responses
matched byte lengths. Browser rendering was not tested.

Tailcat logged active on both runtimes; no phone was paired or tunnel traffic
tested. Computer registered through installed cua-driver; no screen capture or
control was attempted. No voice initialization appeared in Server logs; voice
inference, engine/model loading, native dialogs, real providers, Linux and Windows
remain unvalidated. The portable jar still bundles the voice worker, but that
does not establish native-image voice-resource coverage.

Reproduce with the fresh jar at dist/kinuvo.jar and the image from
scripts/build-cli-native.sh. Set JBR_HOME to a JBR 25 SDK and GRAALVM_HOME to
GraalVM 25, then run from the repository root:

```sh
python3 apps/cli/native-image/verify.py trace
python3 apps/cli/native-image/verify.py jvm
python3 apps/cli/native-image/verify.py native build/native-image/kinuvo
```

Tracing writes one configuration directory per process under a temporary home.
Merge completed directories with native-image-configure before building.
Each run prints its evidence directory containing outputs, timings and logs;
pairing material and model prompts remain local and must not be committed.
Help coverage includes every command and subcommand, including mutation commands
that are never executed. Per-command times below exclude compilation; ACP reply
timing was not measured. Output differences are expected generated IDs, timestamps,
ephemeral ports, local paths, engine selection and timing; semantic results match.

| Command | JBR | Native | JBR seconds | Native seconds |
| --- | --- | --- | ---: | ---: |
| `--help` | pass | pass | 0.672 | 1.306 |
| `agents --help` | pass | pass | 0.501 | 0.018 |
| `agents list --help` | pass | pass | 0.436 | 0.025 |
| `agents show --help` | pass | pass | 0.447 | 0.015 |
| `agents create --help` | pass | pass | 0.462 | 0.015 |
| `agents set --help` | pass | pass | 0.436 | 0.02 |
| `agents delete --help` | pass | pass | 0.491 | 0.013 |
| `routines --help` | pass | pass | 0.532 | 0.012 |
| `routines list --help` | pass | pass | 0.497 | 0.017 |
| `routines show <id>` | pass | pass | 0.432 | 0.016 |
| `routines create --help` | pass | pass | 0.491 | 0.022 |
| `routines edit --help` | pass | pass | 0.522 | 0.018 |
| `routines pause <id>` | pass | pass | 0.566 | 0.024 |
| `routines resume <id>` | pass | pass | 0.532 | 0.021 |
| `routines run <id>` | pass | pass | 0.452 | 0.019 |
| `routines remove <id>` | pass | pass | 0.46 | 0.022 |
| `routines runs <id>` | pass | pass | 0.489 | 0.015 |
| `skills --help` | pass | pass | 0.549 | 0.013 |
| `skills list --help` | pass | pass | 0.501 | 0.028 |
| `skills show --help` | pass | pass | 0.452 | 0.022 |
| `skills create --help` | pass | pass | 0.444 | 0.014 |
| `skills install --help` | pass | pass | 0.455 | 0.016 |
| `skills remove --help` | pass | pass | 0.441 | 0.018 |
| `mcp --help` | pass | pass | 0.536 | 0.016 |
| `mcp list --help` | pass | pass | 0.498 | 0.018 |
| `mcp add --help` | pass | pass | 0.515 | 0.02 |
| `mcp remove --help` | pass | pass | 0.469 | 0.018 |
| `mcp test --help` | pass | pass | 0.468 | 0.019 |
| `mcp login --help` | pass | pass | 0.441 | 0.015 |
| `mcp logout --help` | pass | pass | 0.471 | 0.017 |
| `knowledge --help` | pass | pass | 0.445 | 0.018 |
| `knowledge search --help` | pass | pass | 0.433 | 0.022 |
| `knowledge show --help` | pass | pass | 0.408 | 0.02 |
| `knowledge changes --help` | pass | pass | 0.532 | 0.021 |
| `knowledge undo --help` | pass | pass | 0.556 | 0.021 |
| `tasks --help` | pass | pass | 0.426 | 0.016 |
| `tasks list --help` | pass | pass | 0.436 | 0.023 |
| `tasks lists --help` | pass | pass | 0.42 | 0.021 |
| `tasks add --help` | pass | pass | 0.505 | 0.017 |
| `tasks assign <id>` | pass | pass | 0.425 | 0.017 |
| `tasks status <id>` | pass | pass | 0.492 | 0.025 |
| `secrets --help` | pass | pass | 0.452 | 0.019 |
| `secrets status --help` | pass | pass | 0.466 | 0.019 |
| `secrets connect --help` | pass | pass | 0.476 | 0.017 |
| `secrets disconnect --help` | pass | pass | 0.481 | 0.019 |
| `secrets browse --help` | pass | pass | 0.523 | 0.023 |
| `secrets refresh --help` | pass | pass | 0.435 | 0.019 |
| `devices --help` | pass | pass | 0.441 | 0.025 |
| `devices list --help` | pass | pass | 0.379 | 0.019 |
| `devices revoke --help` | pass | pass | 0.442 | 0.022 |
| `service --help` | pass | pass | 0.421 | 0.018 |
| `service install --help` | pass | pass | 0.541 | 0.024 |
| `service uninstall --help` | pass | pass | 0.521 | 0.021 |
| `service restart --help` | pass | pass | 0.392 | 0.026 |
| `service status --help` | pass | pass | 0.384 | 0.017 |
| `service logs --help` | pass | pass | 0.413 | 0.023 |
| `serve --help` | pass | pass | 0.494 | 0.02 |
| `login --help` | pass | pass | 0.506 | 0.022 |
| `tailcat-reset --help` | pass | pass | 0.415 | 0.017 |
| `acp --help` | pass | pass | 0.407 | 0.02 |
| `notify --help` | pass | pass | 0.394 | 0.024 |
| `pair --help` | pass | pass | 0.451 | 0.02 |
| `serve` | pass | pass | 7.722 | 5.583 |
| `agents list` | pass | pass | 1.521 | 0.043 |
| `agents create spike --clone default` | pass | pass | 1.611 | 0.041 |
| `agents set spike name Trace Agent` | pass | pass | 1.85 | 0.044 |
| `agents show spike` | pass | pass | 1.435 | 0.042 |
| `-a spike skills create spike-skill --description trace --body Reply briefly` | pass | pass | 1.771 | 0.045 |
| `-a spike skills list` | pass | pass | 1.425 | 0.043 |
| `-a spike skills show spike-skill` | pass | pass | 1.776 | 0.046 |
| `-a spike skills remove spike-skill` | pass | pass | 1.767 | 0.042 |
| `-a spike skills create spike-skill --description trace --body Reply briefly --shared` | pass | pass | 1.903 | 0.042 |
| `-a spike skills list` | pass | pass | 1.414 | 0.044 |
| `-a spike skills show spike-skill --shared` | pass | pass | 1.824 | 0.054 |
| `-a spike skills remove spike-skill --shared` | pass | pass | 1.469 | 0.039 |
| `-a spike mcp add spike-mcp --command python3 -- <local-stub.py>` | pass | pass | 1.627 | 0.042 |
| `-a spike mcp list` | pass | pass | 1.443 | 0.042 |
| `-a spike mcp test spike-mcp` | pass | pass | 1.549 | 0.079 |
| `-a spike mcp remove spike-mcp` | pass | pass | 1.52 | 0.045 |
| `-a spike routines create spike Say hello --every 1d --paused` | pass | pass | 1.328 | 0.042 |
| `-a spike routines list` | pass | pass | 1.562 | 0.04 |
| `-a spike routines show <id>` | pass | pass | 1.551 | 0.042 |
| `-a spike routines pause <id>` | pass | pass | 1.766 | 0.046 |
| `-a spike routines resume <id>` | pass | pass | 1.706 | 0.043 |
| `-a spike routines run <id>` | pass | pass | 1.365 | 0.045 |
| `-a spike routines runs <id>` | pass | pass | 1.812 | 0.048 |
| `-a spike routines remove <id>` | pass | pass | 2.042 | 0.044 |
| `knowledge search spike` | pass | pass | 1.408 | 0.045 |
| `knowledge changes` | pass | pass | 1.496 | 0.04 |
| `tasks lists` | pass | pass | 1.5 | 0.051 |
| `tasks add spike task` | pass | pass | 1.749 | 0.045 |
| `tasks list` | pass | pass | 1.348 | 0.044 |
| `tasks assign <id> spike` | pass | pass | 1.606 | 0.046 |
| `tasks status <id> done` | pass | pass | 1.784 | 0.053 |
| `secrets status` | pass | pass | 3.528 | 2.752 |
| `secrets browse` | pass | pass | 2.475 | 1.016 |
| `notify spike` | pass | pass | 1.556 | 0.05 |
| `pair` | pass | pass | 1.583 | 0.035 |
| `pair --manage` | pass | pass | 1.624 | 0.043 |
| `devices list` | pass | pass | 1.607 | 0.049 |
| `service status` | pass | pass | 0.522 | 0.032 |
| `agents set spike model stub` | pass | pass | 1.8 | 0.047 |
| `acp initialize/session-new/model-prompt` | pass | pass | — | — |
| `agents delete spike --yes` | pass | pass | 1.765 | 0.06 |
| `tailcat-reset` | pass | pass | 0.517 | 0.025 |
| `serve --tailcat` | pass | pass | 8.746 | 6.6 |
| `serve --computer` | pass | pass | 7.66 | 5.556 |

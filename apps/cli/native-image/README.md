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

The committed metadata records only the exercised paths. Provider calls, voice,
Tailcat, web assets, Computer/cua, ACP/MCP sessions and scheduled work require
separate coverage. Keep tracing homes, tokens and binary artifacts out of Git.

## Observations

Measured on macOS arm64 with GraalVM CE 25.0.2, using the existing November 20
CLI distribution jar (SHA-256
`dc0386f512fae4b28bf37a56e61ccf996cdc854c92d703072aec918a838b3f27`).
No fresh Kotlin build was run, so this does not validate the current branch's
sources against GraalVM.

The direct CLI image built on the first attempt in 214.04 seconds wall time
(native-image reported 3m27s), at 3,787,751,424 bytes peak RSS, producing a
143.22 MB executable. No explicit class-initialization overrides were needed.
The analysis registered 7,679 reflection types, 144 JNI types and two FFM
downcalls. Those counts include GraalVM's own support; the agent JSON is not
exhaustive coverage of those types.

Single-run observations, not controlled benchmarks:

| Operation | JVM executable jar | Native CLI |
| --- | ---: | ---: |
| warm `--help` | 0.59 s | 0.01 s |
| first observed `--help` | 1.04 s | 1.09 s |
| `agents list` against running Server | 3.03 s | 0.041 s |
| `tasks list` against running Server | 2.60 s | 0.042 s |
| Server RSS after start | 166.5 MiB | 108.7 MiB |
| launch to TCP listen | 14.57 s | 5.46 s |

The JVM startup measurement ran during native compilation; the native startup
measurement ran after compilation. Cache warmth, tracing warmup, local machine
load and different Ktor engines prevent treating these as reliable speedup
ratios. Both client commands returned expected empty/default data with exit 0.

The Swift Apple library built and a JVM status call returned `unavailable` for
both services, as expected without usage keys. The image embeds `__info_plist`
(verified with `otool`). `NativeCli.java` forwards to the regular CLI and adds
only `--apple-status` for a permission-free FFM status probe:

```sh
build/native-image/kinuvo -Dkinuvo.apple.library="$PWD/build/libKinuvoApple.dylib" --apple-status
```

Failures and limitations:

- The sandbox initially blocked network/DNS, socket binds and process metrics;
  these operations were rerun with the authorized host access.
- The GraalVM tracing agent emitted `List must have exactly 2 element(s)` while
  processing Kotlin built-in resource access. The merged metadata includes
  the observed built-in resource globs, but other paths can still be missing.
- The traced `tasks list` could not reconnect; the later untraced JVM and native
  commands both succeeded. The failed trace is not proof of tasks coverage.
- Flat classpath runs choose the Java Ktor client engine; the nested jar chooses
  Apache. Both engines are present, so discovery order changes behavior.
- The FFM flag is experimental (enabled by default in this GraalVM release).
- Tailcat tunnel establishment, voice, web frontend loading, Computer/cua,
  live model providers, ACP/MCP sessions, scheduling and all OS permissions are
  unvalidated. Their failures are unknown, rather than confirmed compatible.
- This reuses a packaged jar with collision restoration. A raw Amper jar can
  have missing dependencies. The spike does not replace release packaging.

Maintenance estimate: dependency bumps need trace refreshes plus representative
runtime scenarios for serializers, reflection, ServiceLoader, native signatures
and resources. Basic `--help` is insufficient. Budget at least roughly 4 minutes
per macOS native compilation on this machine plus packaging and scenarios;
Linux and Windows require their own native builders, metadata validation and
native-library packaging. Cross-platform timings and CI costs have not been
measured. This is feasible for the exercised CLI paths, but is not evidence of a
complete native Server distribution.

The final image with the status entrypoint built in 187.20 seconds wall time
(native-image: 3m1s), with 4,120,363,008 bytes peak RSS and a 143.22 MB file.
The native status probe returned Calendar `notDetermined` and Reminders
`fullAccess`, confirming FFM calls and visible usage keys. It made no permission
request. Final `--help`, `serve`, `agents list` and `tasks list` all passed;
the table uses the final runtime measurements. All test Servers were stopped.

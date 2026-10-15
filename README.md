# Kinuvo

A self-hosted personal AI assistant. You talk to one or more named Agents that remember you across conversations, work with your Knowledge (Obsidian, Anytype), run Routines and Tasks, use MCP servers and Skills, and can operate a computer. One Server runs the Agents; the desktop, Android and web apps and the `kinuvo` CLI connect to it.

## Install

The Server and CLI, with a bundled Java runtime:

```sh
# macOS and Linux
curl -fsSL https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.sh | sh
```

```powershell
# Windows
irm https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.ps1 | iex
```

Then:

```sh
kinuvo login                 # sign in to a model provider
kinuvo                       # run the Server on 127.0.0.1:8787
kinuvo service install       # or run it in the background, started at login
kinuvo pair --manage         # pair the desktop or Android app
```

Run the installer again to upgrade. `KINUVO_VERSION=v0.1.0` pins a release.

### Docker

```sh
docker run -d --name kinuvo -p 127.0.0.1:8787:8787 -v kinuvo:/data ghcr.io/feernandooff/kinuvo
docker exec -it kinuvo kinuvo login
docker exec -it kinuvo kinuvo pair --manage
```

### Apps

Download the desktop app (`.dmg`, `.deb`, Windows `.zip`) and the Android `.apk` from [Releases](https://github.com/FeernandoOFF/kinuvo/releases/latest). The desktop apps are not code-signed yet; see [docs/releasing.md](docs/releasing.md#unsigned-macos-and-windows-apps) to open them.

To reach the Server from other networks, put it behind HTTPS: [docs/public-hosting.md](docs/public-hosting.md).

## Build from source

Uses the [Kotlin Toolchain](https://kotlin-toolchain.org) (`./kotlin`, no install needed):

```sh
./kotlin run -m cli          # the Server
./kotlin run -m desktop      # the desktop app
./kotlin test -p jvm
```

See [AGENTS.md](AGENTS.md) for the layout and [CONTEXT.md](CONTEXT.md) for terms.

## License

[MIT](LICENSE)

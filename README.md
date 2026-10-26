# Kinuvo

A self-hosted personal AI assistant. Talk to named Agents that remember you across conversations, work with your Knowledge (Obsidian, Anytype), run Routines and Tasks, and use MCP servers, Skills and Computers. One Server owns your Agents; your Devices connect to it.

![Kinuvo: pair your Devices, choose where the model, harness and tools run, and approve what the Agent does](docs/assets/demo.gif)

## Install

macOS and Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.ps1 | iex
```

[Documentation and getting started](https://feernandooff.github.io/kinuvo/) · [Download apps](https://github.com/FeernandoOFF/kinuvo/releases/latest)

## Build from source

Uses the [Kotlin Toolchain](https://kotlin-toolchain.org) (`./kotlin`, no install needed):

```sh
./kotlin run -m cli          # the Server
./kotlin run -m desktop      # the desktop app
./kotlin test -p jvm
```

In a pinned development checkout, use the mise commands in [local testing](docs/local-testing.md). See [AGENTS.md](AGENTS.md) for layout, [CONTEXT.md](CONTEXT.md) for terms and [releasing](docs/releasing.md) for packaging.

## License

[MIT](LICENSE)

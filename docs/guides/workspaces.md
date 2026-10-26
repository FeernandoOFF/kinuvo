---
title: "Workspaces and Exec backends"
description: "Choose where shell and file tools run: host, container or SSH."
---

A Workspace is the folders an Agent's file tools may touch and where its commands start. Choose an **Exec backend** in **Settings → Workspace**: host, container or SSH. `exec: none` disables shell and file tools.

Commands start in the first Workspace folder. File tools check paths against the Workspace; Approvals assess command Risk separately. These checks are not an OS sandbox for arbitrary shell commands.

## Host

Commands run on the Server machine as the Server's OS user. Workspace folders must already exist there.

```yaml
exec: host
workspace:
  - /home/me/work
```

## Container

Kinuvo uses Podman when available, otherwise Docker. Keep the chosen runtime running. It creates one long-lived container per Agent, named `kinuvo-<agent-id>`, and recreates it when image, network or mounts change.

```yaml
exec: container
workspace:
  - /home/me/work
container:
  image: docker.io/library/debian:stable-slim
  network: false
```

Folders on the Server are mounted under `/workspace/<folder-name>` (duplicate names get suffixes). File and shell paths inside the container use those mount paths. Networking is off by default. Choose an image containing the commands you need. Mounted folders remain writable; container isolation does not protect those folders from changes.

Running the whole Server in Docker is separate from choosing a container Exec backend. The latter needs its runtime available to the Server.

## SSH

The Server uses its system SSH client in batch mode. Configure non-interactive access and verify the host through SSH before using it with an Agent. Workspace folders are absolute paths on the remote machine; the identity file is on the Server.

```yaml
exec: ssh
workspace:
  - /srv/work
ssh:
  host: buildbox.example
  user: me
  port: 22
  identityFile: ~/.ssh/id_ed25519
```

The remote machine needs Bash. Extra `env` values are supported for container and SSH modes and can hold Secret references. See [Agent configuration](../reference/agent.md).

Use the Settings connection test before asking the Agent to work. If it fails, check folder existence, runtime availability, SSH authentication and paths on the correct machine.

Source: [Exec backends](https://github.com/FeernandoOFF/kinuvo/tree/main/harness/plugins/exec/src).

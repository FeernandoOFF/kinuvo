---
title: "Upgrade"
description: "Replace an installed release and refresh its service safely."
---

Back up the Server home before changing versions. Keep only one Server running on that home.

## Installer

Run the same [install command](../start/install.md#server-and-cli) again. To select a release, set `KINUVO_VERSION` to its tag before running the installer:

```sh
export KINUVO_VERSION=v0.1.0
curl -fsSL https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.sh | sh
```

On Windows, set `$env:KINUVO_VERSION` before the PowerShell installer. The tag above illustrates the syntax; choose an existing release.

If a user service is installed, run `kinuvo service install` again with its existing port, Computer and HTTPS flags. The service runs a copied jar in the Server home, so replacing the CLI installation alone does not update that copy. Check `kinuvo service status` and its logs, then open a Device and verify a Session.

For Docker, pull the desired image, stop the old container and recreate it with the same persistent data volume and deployment settings. Update Device apps from Releases as needed.

Older versions are not guaranteed to understand data written by newer versions. Preserve the stopped-home backup and previous release together; see [backup](backup.md) and [service management](service.md).

Sources: [installer](https://github.com/FeernandoOFF/kinuvo/blob/main/install.sh), [service installer](https://github.com/FeernandoOFF/kinuvo/blob/main/apps/cli/src/ServiceCommands.kt).

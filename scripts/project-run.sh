#!/bin/sh
set -eu
kinuvo_project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
. "$kinuvo_project_root/scripts/project-env.sh"
cd "$kinuvo_project_root"
[ -f .kinuvo-home ] && [ -f .kinuvo-port ] || { echo "Run mise run init -- /absolute/data/home <port> first" >&2; exit 1; }
if [ "${1:-}" = --check-server ]; then
    shift
    python3 - "$KINUVO_HOME" <<'CHECK'
import json, os, sys
from pathlib import Path
record = Path(sys.argv[1]) / "server.json"
if record.exists():
    try:
        data = json.loads(record.read_text())
        pid = int(data["pid"])
        if pid <= 0:
            raise ValueError("invalid Server pid")
        os.kill(pid, 0)
    except ProcessLookupError:
        pass
    except PermissionError:
        sys.exit("Cannot verify the existing Server; refusing to start another against this home")
    except (ValueError, KeyError, OSError) as error:
        sys.exit(f"Cannot validate {record}: {error}")
    else:
        sys.exit(f"A Server (pid {pid}) already owns {sys.argv[1]}; use mise run cli or stop it first")
CHECK
fi
exec "$@"

import datetime as dt
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import urllib.request


def next_reset(now):
    reset = now.replace(hour=3, minute=0, second=0, microsecond=0)
    return reset if now < reset else reset + dt.timedelta(days=1)


def reset_state(home):
    if home.is_symlink() or (home.exists() and not (home / ".review-server").is_file()):
        raise RuntimeError("Refusing to reset an unmarked review home")
    identity = (home / "server-id").read_bytes() if (home / "server-id").is_file() else None
    if home.exists():
        shutil.rmtree(home)
    home.mkdir(parents=True)
    (home / ".review-server").write_text("Kinuvo review Server\n")
    if identity is not None:
        (home / "server-id").write_bytes(identity)


def model_environment():
    env = os.environ.copy()
    env.pop("KINUVO_REVIEW_API_KEY", None)
    key_file = Path("/data/openrouter-key")
    if not key_file.exists():
        print("Model disabled: review-only API key has not been installed", flush=True)
        return env
    key = key_file.read_text().strip()
    request = urllib.request.Request("https://openrouter.ai/api/v1/key", headers={"Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.load(response)["data"]
        if not isinstance(data.get("limit"), (int, float)) or not 0 < data["limit"] <= 1 or data.get("limit_reset") != "daily":
            raise ValueError("Invalid daily cap")
    except Exception:
        raise RuntimeError("Review key must have a verified daily spend limit of at most USD 1") from None
    env["KINUVO_REVIEW_API_KEY"] = key
    return env


def main():
    home = Path(os.environ["KINUVO_HOME"])
    if home != Path("/data/state"):
        raise RuntimeError("Review container requires /data/state")
    stopping = False
    child = None

    def stop(signum, frame):
        nonlocal stopping
        stopping = True
        if child is not None and child.poll() is None:
            child.terminate()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    now = dt.datetime.now(dt.timezone.utc)
    latest_reset = next_reset(now) - dt.timedelta(days=1)
    reset_record = home.parent / "review-reset-at"
    missed_reset = not reset_record.exists() or reset_record.read_text() != latest_reset.isoformat()
    if home.exists() and (missed_reset or not (home / ".review-seeded").exists()):
        reset_state(home)
    reset_record.write_text(latest_reset.isoformat())
    while not stopping:
        reset_at = next_reset(dt.datetime.now(dt.timezone.utc))
        child = subprocess.Popen(["java", "-Xmx1024m", "-jar", "/opt/kinuvo/kinuvo.jar", "serve", "--host", "0.0.0.0"], env=model_environment())
        print("Next review reset: " + reset_at.isoformat(), flush=True)
        while child.poll() is None and not stopping and dt.datetime.now(dt.timezone.utc) < reset_at:
            time.sleep(1)
        if child.poll() is None:
            child.terminate()
        try:
            child.wait(timeout=45)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
        if stopping:
            break
        if dt.datetime.now(dt.timezone.utc) < reset_at:
            raise SystemExit(child.returncode or 1)
        reset_state(home)
        reset_record.write_text(reset_at.isoformat())
        print("Review state reset; paired Devices and Server identity retained", flush=True)


if __name__ == "__main__":
    main()

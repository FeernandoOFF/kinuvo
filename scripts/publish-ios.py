#!/usr/bin/env python3
"""Archive, sign and publish an iPhone build privately through Tailscale Serve."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import html
import json
from pathlib import Path
import os
import plistlib
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.parse import quote


ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "build/ios-ota"
SITE = ROOT / "dist/ios-install"
MOUNT = "/install/ios/"
PORT = 8795
PROXY = f"http://127.0.0.1:{PORT}"


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def xcode(log_name, *args):
    log = WORK / log_name
    print(f"Running Xcode; log: {log}", flush=True)
    environment = os.environ.copy()
    if log_name == "archive.log":
        # Toolchain 0.13 hardcodes a 3 GB native compiler heap; Release linking needs more.
        heap = environment.get("KINUVO_IOS_BUILD_HEAP", "8g")
        environment["_JAVA_OPTIONS"] = environment.get("_JAVA_OPTIONS", "") + f" -Xmx{heap}"
    with log.open("w") as stream:
        result = subprocess.run(["xcodebuild", *map(str, args)], cwd=ROOT,
                                stdout=stream, stderr=subprocess.STDOUT, env=environment)
    if result.returncode:
        raise RuntimeError(f"Xcode failed. See {log}\n" + "\n".join(log.read_text().splitlines()[-35:]))


def atomic_write(path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def route_check(config, host):
    if config.get("AllowFunnel", {}).get(host + ":443"):
        raise RuntimeError("Port 443 has public Funnel enabled; refusing to publish a private build.")
    handlers = config.get("Web", {}).get(host + ":443", {}).get("Handlers", {})
    for mount, handler in handlers.items():
        if mount.rstrip("/") == MOUNT.rstrip("/") and handler != {"Proxy": PROXY}:
            raise RuntimeError(f"An existing handler owns {MOUNT}: {handler}")


def serve():
    label = "dev.kinuvo.ios-install"
    domain = f"gui/{os.getuid()}"
    agent = Path.home() / "Library/LaunchAgents" / (label + ".plist")
    specification = {"Label": label, "ProgramArguments": ["/usr/bin/python3", "-m", "http.server",
                     str(PORT), "--bind", "127.0.0.1", "--directory", str(SITE)],
                     "RunAtLoad": True, "KeepAlive": True,
                     "StandardOutPath": str(WORK / "hosting.log"),
                     "StandardErrorPath": str(WORK / "hosting.log")}
    if agent.exists() and plistlib.loads(agent.read_bytes()) != specification:
        raise RuntimeError(f"An existing launch agent owns {agent}; refusing to replace it.")
    loaded = subprocess.run(["launchctl", "print", domain + "/" + label],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not loaded:
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", PORT)) == 0:
                raise RuntimeError(f"Port {PORT} is already in use.")
        agent.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(agent, plistlib.dumps(specification))
        subprocess.run(["launchctl", "bootstrap", domain, str(agent)], check=True)
    for attempt in range(30):
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", PORT)) == 0:
                break
        time.sleep(0.1)
    else:
        raise RuntimeError(f"Installer host did not start; see {WORK / 'hosting.log'}")
    subprocess.run(["tailscale", "serve", "--bg", "--https=443", "--set-path=" + MOUNT, PROXY], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Export an existing .xcarchive instead of rebuilding")
    args = parser.parse_args()
    os.chdir(ROOT)
    WORK.mkdir(parents=True, exist_ok=True)
    with (WORK / "publish.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another iOS publication is running in this checkout.") from None
        publish(args.archive)


def publish(existing_archive):
    status = json.loads(output("tailscale", "status", "--json"))
    if status.get("BackendState") != "Running":
        raise RuntimeError("Connect Tailscale before publishing.")
    host = status["Self"]["DNSName"].rstrip(".")
    config = json.loads(output("tailscale", "serve", "status", "--json"))
    route_check(config, host)
    base = f"https://{host}{MOUNT}"
    revision = output("git", "rev-parse", "--short", "HEAD")
    if output("git", "status", "--porcelain"):
        revision += "-dirty"
    if existing_archive:
        revision = "existing-archive"
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    release = f"{stamp}-{revision}"
    archive = existing_archive.resolve() if existing_archive else WORK / "Kinuvo.xcarchive"
    if not existing_archive:
        xcode("archive.log", "-project", "apps/ios/module.xcodeproj", "-scheme", "ios",
              "-configuration", "Release", "-destination", "generic/platform=iOS",
              "-derivedDataPath", WORK / "DerivedData", "-archivePath", archive,
              "-allowProvisioningUpdates", "archive")
    archive_info = plistlib.loads((archive / "Info.plist").read_bytes())
    team = archive_info["ApplicationProperties"]["Team"]
    options = WORK / "ExportOptions.plist"
    options.write_bytes(plistlib.dumps({"method": "release-testing", "teamID": team,
                                       "signingStyle": "automatic", "thinning": "<none>"}))
    with tempfile.TemporaryDirectory(dir=WORK) as temporary:
        temporary = Path(temporary)
        exported = temporary / "export"
        xcode("export.log", "-exportArchive", "-archivePath", archive,
              "-exportPath", exported, "-exportOptionsPlist", options,
              "-allowProvisioningUpdates")
        ipa, = exported.glob("*.ipa")
        subprocess.run(["ditto", "-x", "-k", str(ipa), str(temporary / "unpacked")], check=True)
        app, = (temporary / "unpacked/Payload").glob("*.app")
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
        info = plistlib.loads((app / "Info.plist").read_bytes())
        profile = plistlib.loads(subprocess.check_output(
            ["security", "cms", "-D", "-i", str(app / "embedded.mobileprovision")],
            stderr=subprocess.DEVNULL))
        entitlements = profile["Entitlements"]
        if (entitlements.get("get-task-allow") or not profile.get("ProvisionedDevices")
                or profile.get("ProvisionsAllDevices") or profile["ExpirationDate"] <= datetime.now(timezone.utc).replace(tzinfo=None)):
            raise RuntimeError("Export must contain an unexpired Ad Hoc profile for registered devices.")
        bundle = info["CFBundleIdentifier"]
        if entitlements["application-identifier"] != f"{team}.{bundle}":
            raise RuntimeError("Exported profile does not match the app identifier.")
        SITE.mkdir(parents=True, exist_ok=True)
        releases = SITE / "builds"
        releases.mkdir(exist_ok=True)
        destination = releases / release
        destination.mkdir()
        shutil.copyfile(ipa, destination / "Kinuvo.ipa")
        icon = ROOT / "apps/ios/src/Assets.xcassets/AppIcon.appiconset/icon.png"
        shutil.copyfile(icon, destination / "icon.png")
        release_url = base + "builds/" + release + "/"
        manifest = {"items": [{"assets": [
            {"kind": "software-package", "url": release_url + "Kinuvo.ipa"},
            {"kind": "display-image", "url": release_url + "icon.png"},
            {"kind": "full-size-image", "url": release_url + "icon.png"}],
            "metadata": {"bundle-identifier": bundle, "bundle-version": info["CFBundleVersion"],
                         "kind": "software", "title": "Kinuvo"}}]}
        manifest_bytes = plistlib.dumps(manifest)
        (destination / "manifest.plist").write_bytes(manifest_bytes)
        digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
        metadata = {"build": release, "bundleIdentifier": bundle,
                    "version": info["CFBundleShortVersionString"], "sha256": digest,
                    "minimumIOS": info["MinimumOSVersion"],
                    "profileExpires": profile["ExpirationDate"].isoformat() + "Z",
                    "registeredDeviceCount": len(profile["ProvisionedDevices"])}
        (destination / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
        link = "itms-services://?action=download-manifest&url=" + quote(release_url + "manifest.plist", safe="")
        page = f"""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-store"><title>Install Kinuvo</title>
<style>body{{margin:0;background:#101714;color:#ecf3ed;font:17px/1.6 system-ui;display:grid;min-height:100svh;place-items:center}}main{{max-width:32rem;padding:2rem}}img{{width:76px;border-radius:18px}}h1{{font-size:2.5rem;line-height:1.1}}a{{color:#b5eac8}}.install{{display:block;text-align:center;background:#b5eac8;color:#102118;padding:1rem;border-radius:12px;text-decoration:none;font-weight:650;margin:2rem 0}}small{{color:#a4b4a9;overflow-wrap:anywhere}}</style>
<main><img src="{release_url}icon.png" alt=""><h1>Kinuvo for iPhone</h1>
<p>Connect to Tailscale and open this page in Safari on your registered iPhone.</p>
<a class="install" href="{html.escape(link, quote=True)}">Install Kinuvo</a>
<p>Requires iOS {html.escape(info['MinimumOSVersion'])} or later. Confirm the installation, then open Kinuvo from your Home Screen. Updating preserves this app’s data.</p>
<p><small>Version {html.escape(info['CFBundleShortVersionString'])} · {html.escape(revision)}<br>Published {stamp}<br>Signing expires {profile['ExpirationDate']:%Y-%m-%d}</small></p>
<p><small>Refresh this page after publishing a new build. Installation is available only to devices registered in the signing profile.</small></p>
<a href="{release_url}build.json">Build details</a></main></html>"""
        atomic_write(SITE / "index.html", page.encode())
        serve()
        after = json.loads(output("tailscale", "serve", "status", "--json"))
        route_check(after, host)
        for endpoint, web in config.get("Web", {}).items():
            for mount, handler in web.get("Handlers", {}).items():
                if after.get("Web", {}).get(endpoint, {}).get("Handlers", {}).get(mount) != handler:
                    raise RuntimeError(f"Existing Tailscale route changed: {endpoint}{mount}")
        for url, expected in [(base, page.encode()), (release_url + "manifest.plist", manifest_bytes),
                              (release_url + "Kinuvo.ipa", ipa.read_bytes())]:
            actual = subprocess.check_output(["curl", "--fail", "--silent", "--show-error",
                                              "--retry", "3", "--max-time", "180", url])
            if hashlib.sha256(actual).digest() != hashlib.sha256(expected).digest():
                raise RuntimeError(f"HTTPS content verification failed: {url}")
        print(f"Verified HTTPS page, manifest and IPA; SHA-256 {digest}")
        print(f"Install URL: {base}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))

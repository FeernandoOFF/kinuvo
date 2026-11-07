#!/usr/bin/env python3
"""Sign a Play AAB with the local upload key, separate from Google's app signing key."""

import argparse
import os
from pathlib import Path
import secrets
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("bundle", type=Path)
parser.add_argument("--init-upload-key", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
bundle = args.bundle.resolve(strict=True)
directory = root / "signing"
keystore = directory / "play-upload.p12"
password = directory / "play-upload.password"
os.umask(0o077)
if args.init_upload_key:
    if keystore.exists() or password.exists():
        parser.error("Upload key files already exist; refusing to replace them")
    directory.mkdir(mode=0o700, exist_ok=True)
    password.write_text(secrets.token_urlsafe(48))
    subprocess.run([
        "keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore),
        "-storepass:file", str(password), "-alias", "play-upload", "-keyalg", "RSA",
        "-keysize", "4096", "-validity", "10000", "-dname", "CN=Kinuvo Play Upload",
    ], check=True)
if not keystore.is_file() or not password.is_file():
    parser.error("Upload key missing; use --init-upload-key once, or restore signing/ from backup")
output = root / "dist" / "Kinuvo-play.aab"
output.parent.mkdir(exist_ok=True)
subprocess.run([
    "jarsigner", "-keystore", str(keystore), "-storepass:file", str(password),
    "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", "-signedjar", str(output),
    str(bundle), "play-upload",
], check=True)
subprocess.run(["jarsigner", "-verify", str(output)], check=True)
subprocess.run([
    "keytool", "-exportcert", "-rfc", "-keystore", str(keystore),
    "-storepass:file", str(password), "-alias", "play-upload", "-file",
    str(output.parent / "Kinuvo-play-upload.pem"),
], check=True)
print(f"Signed bundle: {output}")
print(f"Back up {directory}: its keystore and password are needed for future uploads.")

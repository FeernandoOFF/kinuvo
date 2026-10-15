#!/bin/sh
# Installs or upgrades the kinuvo command (CLI and Server) on macOS or Linux:
#   curl -fsSL https://raw.githubusercontent.com/FeernandoOFF/kinuvo/main/install.sh | sh
# KINUVO_VERSION=v1.2.3 pins a release; KINUVO_INSTALL_DIR and KINUVO_BIN_DIR choose where it goes.
set -eu

repo=${KINUVO_REPO:-FeernandoOFF/kinuvo}

fail() { echo "kinuvo: $*" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) fail "unsupported OS $(uname -s); on Windows use install.ps1" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) fail "unsupported architecture $(uname -m)" ;;
esac

if [ "$(id -u)" = 0 ]; then
  install_dir=${KINUVO_INSTALL_DIR:-/opt/kinuvo}
  bin_dir=${KINUVO_BIN_DIR:-/usr/local/bin}
else
  install_dir=${KINUVO_INSTALL_DIR:-$HOME/.local/share/kinuvo}
  bin_dir=${KINUVO_BIN_DIR:-$HOME/.local/bin}
fi

if [ -n "${KINUVO_DOWNLOAD_URL:-}" ]; then
  base=$KINUVO_DOWNLOAD_URL
elif [ -n "${KINUVO_VERSION:-}" ]; then
  base=https://github.com/$repo/releases/download/$KINUVO_VERSION
else
  base=https://github.com/$repo/releases/latest/download
fi
archive=kinuvo-$os-$arch.tar.gz

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

echo "Downloading $archive..."
curl -fsSL --retry 3 -o "$tmp/$archive" "$base/$archive" || fail "download failed: $base/$archive"
curl -fsSL --retry 3 -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" || fail "download failed: $base/SHA256SUMS"

expected=$(awk -v f="$archive" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
[ -n "$expected" ] || fail "no checksum for $archive"
if command -v sha256sum >/dev/null; then
  actual=$(sha256sum "$tmp/$archive" | awk '{ print $1 }')
else
  actual=$(shasum -a 256 "$tmp/$archive" | awk '{ print $1 }')
fi
[ "$expected" = "$actual" ] || fail "checksum mismatch for $archive"

tar -xzf "$tmp/$archive" -C "$tmp"
upgrade=false
[ -d "$install_dir" ] && upgrade=true
mkdir -p "$(dirname "$install_dir")" "$bin_dir"
rm -rf "$install_dir.old"
[ -d "$install_dir" ] && mv "$install_dir" "$install_dir.old"
mv "$tmp/kinuvo" "$install_dir"
rm -rf "$install_dir.old"
ln -sf "$install_dir/bin/kinuvo" "$bin_dir/kinuvo"

echo "Installed $("$install_dir/bin/kinuvo" --version) to $install_dir"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "Add $bin_dir to your PATH: export PATH=\"$bin_dir:\$PATH\"" ;;
esac

service=
[ -f "$HOME/Library/LaunchAgents/dev.kinuvo.server.plist" ] && service=yes
[ -f "$HOME/.config/systemd/user/kinuvo.service" ] && service=yes
if [ "$upgrade" = true ] && [ -n "$service" ]; then
  echo "Run 'kinuvo service install' again to restart the service on the new version."
elif [ "$upgrade" = false ]; then
  echo "Next: 'kinuvo login' to sign in to a model provider, then 'kinuvo' to run the Server,"
  echo "or 'kinuvo service install' to run it in the background at login."
fi

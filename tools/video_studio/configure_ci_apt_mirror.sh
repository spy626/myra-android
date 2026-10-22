#!/usr/bin/env bash
# Slice 6 recovery: public, read-only speed probes for Ubuntu archives.
# The previous Azure archive took >12 min to download one 36 MB .deb.
# Probe public Ubuntu mirrors, then use the best for apt without downloading
# untrusted binaries or weakening the standard Ubuntu package signatures.
set -euo pipefail

if [[ ! -f /usr/share/keyrings/ubuntu-archive-keyring.gpg ]]; then
  echo "Ubuntu signature keyring unavailable" >&2
  exit 1
fi
if [[ ! -f /etc/apt/sources.list.d/ubuntu.sources ]]; then
  echo "Expected Ubuntu 24.04 runner deb822 sources unavailable" >&2
  exit 1
fi
source /etc/os-release
if [[ "$VERSION_CODENAME" != "noble" ]]; then
  echo "Only official Ubuntu Noble hosted runners supported" >&2
  exit 1
fi

# A real package whose download stalled in the cancelled runner.
sample="pool/universe/b/blender/blender-data_4.0.2+dfsg-1ubuntu8_all.deb"
best_url=""
best_speed=0
for url in \
  "https://archive.ubuntu.com/ubuntu" \
  "https://mirrors.edge.kernel.org/ubuntu" \
  "https://azure.archive.ubuntu.com/ubuntu"; do
  speed=""
  if speed="$(curl --silent --show-error --fail --location \
      --max-time 9 --connect-timeout 4 --range 0-524287 \
      --output /dev/null --write-out '%{speed_download}' \
      "$url/$sample" 2>/dev/null)"; then
    numeric="$(awk -v n="$speed" 'BEGIN {printf "%d", n+0}')"
    echo "Mirror response: $url speed=$numeric bytes/sec"
    if (( numeric > best_speed )); then
      best_speed=$numeric
      best_url="$url"
    fi
  else
    echo "Mirror probe unavailable: $url"
  fi
done

# An official Ubuntu archive is the only fallback. APT still validates all
# Ubuntu-signed packages with /usr/share/keyrings/ubuntu-archive-keyring.gpg.
if [[ -z "$best_url" || "$best_speed" -lt 200000 ]]; then
  echo "No sufficiently responsive archive; fail quickly instead of using 25m runner time" >&2
  exit 1
fi
echo "Selected Ubuntu mirror: $best_url ($best_speed bytes/sec)"
cat <<SOURCES | sudo tee /etc/apt/sources.list.d/ubuntu.sources >/dev/null
Types: deb
URIs: $best_url
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES
echo "Ubuntu package signatures remain required; no third-party APT sources added"

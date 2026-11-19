#!/usr/bin/env bash
set -euo pipefail

# Install the pinned Linux SDK into a caller-owned empty directory. The caller
# owns cleanup; stdout is reserved for the verified SDK's build identity.
url=https://download.jetbrains.com/idea/idea-2026.3.tar.gz
sha256=6a53ff71f4a06add4c97edc4efe86c83119ff82e5b5e9f1db62fc95995bd6b25
build=263.7307.200

if [[ $# != 1 || ! -d $1 || -L $1 || -n $(ls -A -- "$1") ]]; then
  echo 'Usage: install-idea.sh <existing empty installation directory>' >&2
  exit 1
fi

archive="$1/idea.tar.gz"
curl --proto '=https' --proto-redir '=https' --fail --silent --show-error \
  --location --retry 3 --output "$archive" "$url"
printf '%s  %s\n' "$sha256" "$archive" | sha256sum --check --status || {
  echo 'IntelliJ SDK SHA256 mismatch' >&2
  exit 1
}
tar --extract --gzip --file "$archive" --directory "$1" --strip-components=1 \
  --no-same-owner --no-same-permissions
rm -- "$archive"
jq -e --arg build "$build" '.buildNumber == $build' "$1/product-info.json" >/dev/null || {
  echo "Expected IntelliJ buildNumber $build" >&2
  exit 1
}
printf 'IntelliJ build: %s\nIntelliJ SDK SHA256: %s\n' "$build" "$sha256"

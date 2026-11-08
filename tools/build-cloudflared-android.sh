#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLOUDFLARED_COMMIT="3106f9499d6a23e2f87ec5267eb4771b507a4d1f"
EXPECTED_GO_VERSION="go1.27.3"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ "$(go env GOVERSION)" != "$EXPECTED_GO_VERSION" ]]; then
    echo "Expected $EXPECTED_GO_VERSION; found $(go env GOVERSION)" >&2
    exit 1
fi

HOST_GOROOT="$(go env GOROOT)"
CLOUDFLARED_SOURCE="$TMP_DIR/cloudflared"
PATCHED_GOROOT="$TMP_DIR/goroot"
OUTPUT="$ROOT/TMessagesProj/src/main/jniLibs/arm64-v8a/libcloudflared.so"
LICENSES="$ROOT/TMessagesProj/src/main/assets/third_party/cloudflared"
GENERATED_LICENSES="$TMP_DIR/licenses"

git clone --filter=blob:none --no-checkout https://github.com/cloudflare/cloudflared.git "$CLOUDFLARED_SOURCE"
git -C "$CLOUDFLARED_SOURCE" checkout --detach "$CLOUDFLARED_COMMIT"
cp -a "$HOST_GOROOT" "$PATCHED_GOROOT"

python3 - "$PATCHED_GOROOT/src/net/dnsconfig_unix.go" \
    "$CLOUDFLARED_SOURCE/token/launch_browser_unix.go" <<'PY'
from pathlib import Path
import sys

dns_path = Path(sys.argv[1])
dns_source = dns_path.read_text()
dns_entry = "func dnsReadConfig(filename string) *dnsConfig {\n"
dns_patch = dns_entry + '\tfilename = "/proc/self/fd/0"\n'
if dns_source.count(dns_entry) != 1:
    raise SystemExit("Unable to patch Go Android DNS resolver")
dns_path.write_text(dns_source.replace(dns_entry, dns_patch, 1))

browser_path = Path(sys.argv[2])
browser_source = browser_path.read_text()
old = 'return exec.Command("xdg-open", url)'
new = 'return exec.Command("am", "start", "-a", "android.intent.action.VIEW", "-d", url, "--activity-new-task")'
if browser_source.count(old) != 1:
    raise SystemExit("Unable to patch Cloudflared Android browser launch")
browser_path.write_text(browser_source.replace(old, new, 1))
PY

mkdir -p "$(dirname "$OUTPUT")"
(
    cd "$CLOUDFLARED_SOURCE"
    GOROOT="$PATCHED_GOROOT" GOTOOLCHAIN=local GOOS=android GOARCH=arm64 \
        CGO_ENABLED=0 "$PATCHED_GOROOT/bin/go" build \
        -trimpath -buildvcs=false -ldflags="-s -w" \
        -o "$OUTPUT" ./cmd/cloudflared
)
chmod 755 "$OUTPUT"

GOBIN="$TMP_DIR/bin" go install github.com/google/go-licenses@v1.6.0
(
    cd "$CLOUDFLARED_SOURCE"
    "$TMP_DIR/bin/go-licenses" save ./cmd/cloudflared --save_path="$GENERATED_LICENSES"
)
cp "$HOST_GOROOT/LICENSE" "$GENERATED_LICENSES/GO-LICENSE"
mkdir -p "$LICENSES"
cp -R --remove-destination "$GENERATED_LICENSES/." "$LICENSES/"

echo "Built ARM64 Android connector: $OUTPUT"

#!/usr/bin/env bash
set -euo pipefail

out="${1:-release.keystore}"
alias="${JARVIS_KEY_ALIAS:-jarvis}"
password="${JARVIS_KEYSTORE_PASSWORD:-}"
name="${JARVIS_KEY_NAME:-Jarvis Android}"

if [[ -z "$password" ]]; then
    echo "Set JARVIS_KEYSTORE_PASSWORD first." >&2
    exit 1
fi
if [[ -f "$out" ]]; then
    echo "$out already exists; refusing to overwrite." >&2
    exit 1
fi

keytool -genkeypair \
    -keystore "$out" \
    -alias "$alias" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$password" \
    -keypass "${JARVIS_KEY_PASSWORD:-$password}" \
    -dname "CN=$name, OU=Jarvis, O=Jarvis, L=Android, ST=Android, C=GB"

echo "Created $out."
echo "Base64 it for the ANDROID_KEYSTORE_BASE64 secret:"
echo "  base64 -w0 $out"

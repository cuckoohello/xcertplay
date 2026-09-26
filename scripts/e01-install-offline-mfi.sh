#!/usr/bin/env bash
#
# Deploys the offline MFi accessory identity into the E01 assets tree so the next
# :e01:assembleDebug produces an APK that carries the Apple accessory private key
# and certificate. The credential is INTERNAL USE ONLY: any APK produced by this
# script embeds an Apple accessory private key. Do NOT distribute the APK
# externally and do NOT commit the extracted files.
#
# Usage:
#   scripts/e01-install-offline-mfi.sh <path/to/DiPlay.apk>
#   scripts/e01-install-offline-mfi.sh <path/to/DiPlay.apk> --build
#
# On success the script prints the deployed file hashes. On any hash mismatch,
# extraction error, or missing dependency, it removes the staged files and exits
# non-zero. Deployed files remain outside git via .gitignore rule
# '**/assets/offline-mfi/'.

set -euo pipefail

script_name=$(basename "$0")
repo_root=$(cd "$(dirname "$0")/.." && pwd)
assets_dir="$repo_root/e01/src/main/assets/offline-mfi"

# Expected hashes come from docs/03-wired-carplay.md §Local MFi §凭据来源. If the
# upstream identity is rotated, update both this script and the doc together.
expected_apk_sha="89466e016973580ccffa0e265cc8b49800c6c92e6a0789bdf704832bfc01820e"
expected_identity_sha="bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75"
expected_certificate_sha="634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e"

usage() {
    cat <<EOF
$script_name — install the offline MFi accessory identity into e01 assets

Usage:
  $script_name <path/to/DiPlay.apk> [--build]
  $script_name --help

Options:
  --build         After a successful install, run :e01:assembleDebug and print
                  the resulting APK path and SHA-256.
  -h, --help      Print this message and exit.

Behavior:
  - Extracts assets/offline-mfi/identity.pk8 and assets/offline-mfi/certificate.p7b
    from the provided APK and writes them to
    e01/src/main/assets/offline-mfi/.
  - Verifies the source APK, extracted identity, and extracted certificate all
    match the SHA-256 hashes recorded in docs/03-wired-carplay.md. Any
    mismatch cleans the staging directory and fails.
  - Never modifies git-tracked files. The '.gitignore' rule
    '**/assets/offline-mfi/' keeps the credentials out of the repository.

Security:
  This installs an Apple MFi accessory private key into the build tree so the
  produced APK can complete iAP2 and AirPlay MFi-SAP without a hardware
  coprocessor. Any APK built after running this script embeds that private key
  and MUST NOT be shared outside the internal validation team. Delete the
  staged files with 'rm -rf $assets_dir' when you are done.
EOF
}

fail() {
    local message="$1"
    echo "$script_name: $message" >&2
    if [[ -d "$assets_dir" ]]; then
        rm -rf "$assets_dir"
    fi
    exit 1
}

require_tool() {
    local tool="$1"
    if ! command -v "$tool" >/dev/null 2>&1; then
        fail "required tool '$tool' is not on PATH"
    fi
}

sha256_of() {
    local path="$1"
    shasum -a 256 "$path" | awk '{print $1}'
}

expect_hash() {
    local label="$1" path="$2" expected="$3"
    local actual
    actual=$(sha256_of "$path")
    if [[ "$actual" != "$expected" ]]; then
        fail "$label SHA-256 mismatch: got $actual, expected $expected"
    fi
}

apk_path=""
run_build="false"

while (($# > 0)); do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --build)
            run_build="true"
            shift
            ;;
        --)
            shift
            if [[ $# -eq 1 ]]; then apk_path="$1"; shift; else fail "unexpected extra arguments after --"; fi
            ;;
        -*)
            fail "unknown option '$1'; run --help for usage"
            ;;
        *)
            if [[ -z "$apk_path" ]]; then
                apk_path="$1"
            else
                fail "unexpected extra argument '$1'; run --help for usage"
            fi
            shift
            ;;
    esac
done

if [[ -z "$apk_path" ]]; then
    usage >&2
    exit 2
fi

if [[ ! -f "$apk_path" ]]; then
    fail "APK not found: $apk_path"
fi

require_tool shasum
require_tool unzip

echo "$script_name: verifying source APK"
expect_hash "source APK" "$apk_path" "$expected_apk_sha"

echo "$script_name: staging into $assets_dir"
mkdir -p "$assets_dir"

# Guard: extraction below overwrites any pre-existing files. Truncate first so
# a mid-run failure never leaves a stale mismatched pair.
rm -f "$assets_dir/identity.pk8" "$assets_dir/certificate.p7b"

if ! unzip -p "$apk_path" assets/offline-mfi/identity.pk8 > "$assets_dir/identity.pk8"; then
    fail "failed to extract assets/offline-mfi/identity.pk8 from $apk_path"
fi
if [[ ! -s "$assets_dir/identity.pk8" ]]; then
    fail "extracted identity.pk8 is empty; check the APK"
fi

if ! unzip -p "$apk_path" assets/offline-mfi/certificate.p7b > "$assets_dir/certificate.p7b"; then
    fail "failed to extract assets/offline-mfi/certificate.p7b from $apk_path"
fi
if [[ ! -s "$assets_dir/certificate.p7b" ]]; then
    fail "extracted certificate.p7b is empty; check the APK"
fi

expect_hash "identity.pk8" "$assets_dir/identity.pk8" "$expected_identity_sha"
expect_hash "certificate.p7b" "$assets_dir/certificate.p7b" "$expected_certificate_sha"

chmod 600 "$assets_dir/identity.pk8" "$assets_dir/certificate.p7b"

echo "$script_name: installed"
echo "  identity.pk8    $(sha256_of "$assets_dir/identity.pk8")"
echo "  certificate.p7b $(sha256_of "$assets_dir/certificate.p7b")"
echo "$script_name: WARNING — the APK produced from these assets embeds an Apple accessory private key. Internal validation only."

# Belt-and-suspenders: make sure git will never pick up either file. If a user
# ever removes the .gitignore rule this line refuses to silently succeed.
for path in "$assets_dir/identity.pk8" "$assets_dir/certificate.p7b"; do
    if ! git -C "$repo_root" check-ignore -q "$path"; then
        fail ".gitignore does not exclude $path; refusing to leave credential files under a tracked path"
    fi
done

if [[ "$run_build" == "true" ]]; then
    echo "$script_name: running :e01:assembleDebug"
    ( cd "$repo_root" && ./gradlew :e01:assembleDebug -Pxcertplay.skipNative=true )
    apk_out="$repo_root/e01/build/outputs/apk/debug/e01-debug.apk"
    if [[ ! -f "$apk_out" ]]; then
        fail "expected APK not found at $apk_out"
    fi
    echo "$script_name: built $apk_out"
    echo "  SHA-256 $(sha256_of "$apk_out")"
fi

#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sandbox="$(mktemp -d "${TMPDIR:-/tmp}/jvm-mcp-install-test.XXXXXX")"
trap 'rm -rf "$sandbox"' EXIT
mkdir -p "$sandbox/mock" "$sandbox/home" "$sandbox/tmp" "$sandbox/package" "$sandbox/install" "$sandbox/bin"

printf '#!/bin/sh\nexit 0\n' > "$sandbox/package/jvm-mcp"
real_tar="$(command -v tar)"
tar -czf "$sandbox/asset.tar.gz" -C "$sandbox/package" .
if command -v sha256sum >/dev/null 2>&1; then
    hash="$(sha256sum "$sandbox/asset.tar.gz" | awk '{print $1}')"
else
    hash="$(shasum -a 256 "$sandbox/asset.tar.gz" | awk '{print $1}')"
fi

case "$(uname -s)" in
    Linux*) os=linux ;;
    Darwin*) os=macos ;;
    MINGW*|MSYS*)
        # Exercise the Linux branch when running locally from Git Bash on Windows.
        os=linux
        cat > "$sandbox/mock/uname" <<'EOF'
#!/usr/bin/env bash
case "$1" in
    -s) echo Linux ;;
    -m) echo x86_64 ;;
esac
EOF
        chmod +x "$sandbox/mock/uname"
        ;;
    *) echo "Unsupported test platform" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    arm64|aarch64) arch=aarch64 ;;
    *) echo "Unsupported test architecture" >&2; exit 1 ;;
esac
asset="jvm-mcp-${os}-${arch}.tar.gz"

cat > "$sandbox/mock/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
url=""
output=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) output="$2"; shift 2 ;;
        -*) shift ;;
        *) url="$1"; shift ;;
    esac
done
case "$url" in
    */checksums.txt) cp "$FIXTURE_CHECKSUMS" "$output" ;;
    *.tar.gz.sha256)
        [ "$CHECKSUM_MODE" != fallback ] || exit 22
        printf '%s  %s\n' "$FIXTURE_HASH" "$RELEASE_ASSET" > "$output"
        ;;
    *.tar.gz) cp "$FIXTURE_ARCHIVE" "$output" ;;
    *) exit 22 ;;
esac
EOF
cat > "$sandbox/mock/tar" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [ -n "${MOCK_TAR_ENTRY:-}" ] && [ "$1" = -tzf ]; then
    printf '%s\n' "$MOCK_TAR_ENTRY"
    exit "${MOCK_TAR_EXIT:-0}"
fi
if [ -n "${MOCK_TAR_ENTRY:-}" ] && [ "$1" = -xzf ]; then
    echo "unsafe extraction reached" >&2
    exit 1
fi
exec "$REAL_TAR" "$@"
EOF
chmod +x "$sandbox/mock/curl" "$sandbox/mock/tar"

checksum_mode=individual
fixture_hash="$hash"
mock_tar_entry=""
mock_tar_exit=0
printf '%s  %s\n' "$hash" "$asset" > "$sandbox/checksums.txt"

run_installer() {
    env HOME="$sandbox/home" TMPDIR="$sandbox/tmp" PATH="$sandbox/mock:$sandbox/bin:$PATH" \
        FIXTURE_ARCHIVE="$sandbox/asset.tar.gz" FIXTURE_CHECKSUMS="$sandbox/checksums.txt" \
        FIXTURE_HASH="$fixture_hash" CHECKSUM_MODE="$checksum_mode" RELEASE_ASSET="$asset" \
        MOCK_TAR_ENTRY="$mock_tar_entry" MOCK_TAR_EXIT="$mock_tar_exit" REAL_TAR="$real_tar" \
        bash "$repo_dir/install.sh" "$@" > "$sandbox/output" 2>&1
}

expect_success() {
    if ! run_installer "$@"; then
        cat "$sandbox/output" >&2
        echo "Expected installer success" >&2
        exit 1
    fi
}

expect_failure() {
    local message="$1"
    shift
    if run_installer "$@"; then
        echo "Expected installer failure: $message" >&2
        exit 1
    fi
    if ! grep -Fq "$message" "$sandbox/output"; then
        cat "$sandbox/output" >&2
        echo "Missing error: $message" >&2
        exit 1
    fi
}

for option in --version --install-dir --bin-dir; do
    expect_failure "Missing value for $option" "$option"
    expect_failure "Missing value for $option" "$option" --help
done

printf 'keep\n' > "$sandbox/install/existing"
upper_hash="$(printf '%s' "$hash" | tr '[:lower:]' '[:upper:]')"
fixture_hash="$upper_hash"
expect_success --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
[ -f "$sandbox/install/jvm-mcp" ]
[ -e "$sandbox/bin/jvm-mcp" ]

checksum_mode=fallback
printf '%s  %s.sha256\n%s  %s\n%s *%s\n' \
    "$hash" "$asset" "$hash" "${asset//./X}" "$upper_hash" "$asset" > "$sandbox/checksums.txt"
expect_success --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
printf '%s  %s\n' "$upper_hash" "$asset" > "$sandbox/checksums.txt"
expect_success --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"

printf 'keep\n' > "$sandbox/install/existing"
fixture_hash="$(printf '0%.0s' {1..64})"
checksum_mode=individual
expect_failure 'SHA-256 verification failed' --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
[ -f "$sandbox/install/existing" ]

checksum_mode=fallback
fixture_hash="$hash"
printf '%s  %s.sha256\n' "$hash" "$asset" > "$sandbox/checksums.txt"
expect_failure 'entry not found in checksums.txt' --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
[ -f "$sandbox/install/existing" ]

checksum_mode=individual
for entry in '..' 'subdir/..' './..' '../escape' '/tmp/escape'; do
    mock_tar_entry="$entry"
    expect_failure 'Unsafe archive path detected' --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
    [ -f "$sandbox/install/existing" ]
done
mock_tar_entry='safe-entry'
mock_tar_exit=2
expect_failure 'Could not list archive entries' --install-dir "$sandbox/install" --bin-dir "$sandbox/bin"
[ -f "$sandbox/install/existing" ]

echo 'Installer regression tests passed.'

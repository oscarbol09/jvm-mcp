#!/usr/bin/env bash
set -euo pipefail

# Configuration Defaults
REPO="oscarbol09/jvm-mcp"
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"
INSTALL_DIR="${HOME}/.local/share/jvm-mcp"
VERSION="latest"
UNINSTALL=false

# Parse CLI Options
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version|-v)
            VERSION="$2"
            shift 2
            ;;
        --uninstall|-u)
            UNINSTALL=true
            shift
            ;;
        --install-dir)
            INSTALL_DIR="$2"
            shift 2
            ;;
        --bin-dir)
            BIN_DIR="$2"
            shift 2
            ;;
        --help|-h)
            echo "JVM-MCP Universal Installer"
            echo "Usage: install.sh [options]"
            echo ""
            echo "Options:"
            echo "  --version, -v <version>  Install a specific release version (e.g. v1.1.0 or 1.1.0)"
            echo "  --uninstall, -u          Uninstall JVM-MCP from the system"
            echo "  --install-dir <dir>      Installation directory (default: ~/.local/share/jvm-mcp)"
            echo "  --bin-dir <dir>          Binary symlink directory (default: ~/.local/bin)"
            echo "  --help, -h               Show this help message"
            exit 0
            ;;
        *)
            echo "Error: Unknown argument '$1'. Use --help for usage." >&2
            exit 1
            ;;
    esac
done

# Handle Uninstallation
if [ "$UNINSTALL" = true ]; then
    echo "Uninstalling JVM-MCP..."
    rm -f "${BIN_DIR}/${EXECUTABLE_NAME}"
    rm -rf "${INSTALL_DIR}"
    echo "JVM-MCP has been completely uninstalled from ${INSTALL_DIR} and ${BIN_DIR}."
    echo "Note: If ${BIN_DIR} was added to ~/.bashrc or ~/.zshrc, you may optionally remove the '# >>> jvm-mcp >>>' block."
    exit 0
fi

# 1. Detect OS
OS="$(uname -s)"
case "${OS}" in
    Linux*)     OS_NAME="linux" ;;
    Darwin*)    OS_NAME="macos" ;;
    *)          echo "Error: Unsupported OS: ${OS}" >&2; exit 1 ;;
esac

# 2. Detect Architecture
ARCH="$(uname -m)"
case "${ARCH}" in
    x86_64|amd64)   ARCH_NAME="amd64" ;;
    arm64|aarch64)  ARCH_NAME="aarch64" ;;
    *)              echo "Error: Unsupported architecture: ${ARCH}" >&2; exit 1 ;;
esac

RELEASE_ASSET="jvm-mcp-${OS_NAME}-${ARCH_NAME}.tar.gz"

if [ "$VERSION" != "latest" ]; then
    [[ "$VERSION" != v* ]] && VERSION="v${VERSION}"
    DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${VERSION}/${RELEASE_ASSET}"
    SHA_URL="https://github.com/${REPO}/releases/download/${VERSION}/${RELEASE_ASSET}.sha256"
    CHECKSUMS_URL="https://github.com/${REPO}/releases/download/${VERSION}/checksums.txt"
else
    DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}"
    SHA_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}.sha256"
    CHECKSUMS_URL="https://github.com/${REPO}/releases/latest/download/checksums.txt"
fi

# 3. Setup temporary workspace with trap cleanup
TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t 'jvm-mcp')"
cleanup() {
    rm -rf "${TMP_DIR}"
}
trap cleanup EXIT INT TERM

# 4. Download Release Asset
echo "Downloading ${RELEASE_ASSET} from GitHub (${VERSION})..."
if ! curl -fsSL "${DOWNLOAD_URL}" -o "${TMP_DIR}/${RELEASE_ASSET}"; then
    echo "Error: Failed to download ${RELEASE_ASSET} from ${DOWNLOAD_URL}" >&2
    echo "Please verify that a release asset exists for ${OS_NAME}-${ARCH_NAME}." >&2
    echo "Check available releases at: https://github.com/${REPO}/releases" >&2
    exit 1
fi

# 5. Fetch and Verify SHA-256 Checksum
echo "Verifying SHA-256 checksum..."
SHA_FILE="${TMP_DIR}/${RELEASE_ASSET}.sha256"

if curl -fsSL "${SHA_URL}" -o "${SHA_FILE}" 2>/dev/null; then
    : # Successfully fetched asset-specific .sha256 file
elif curl -fsSL "${CHECKSUMS_URL}" -o "${TMP_DIR}/checksums.txt" 2>/dev/null; then
    grep "${RELEASE_ASSET}" "${TMP_DIR}/checksums.txt" > "${SHA_FILE}" || {
        echo "Error: ${RELEASE_ASSET} entry not found in checksums.txt" >&2
        exit 1
    }
else
    echo "Error: Could not retrieve SHA-256 checksum file (${RELEASE_ASSET}.sha256 or checksums.txt)" >&2
    exit 1
fi

EXPECTED_HASH="$(awk '{print $1}' "${SHA_FILE}" | tr -d '\r\n ')"
if [[ ! "${EXPECTED_HASH}" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "Error: Checksum file does not contain a valid 64-character SHA-256 hexadecimal hash: '${EXPECTED_HASH}'" >&2
    exit 1
fi

ACTUAL_HASH=""
if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_HASH="$(sha256sum "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_HASH="$(shasum -a 256 "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
    ACTUAL_HASH="$(openssl dgst -sha256 "${TMP_DIR}/${RELEASE_ASSET}" | awk '{print $NF}')"
else
    echo "Error: No SHA-256 verification tool available (sha256sum, shasum, or openssl required)." >&2
    exit 1
fi

ACTUAL_HASH="$(echo "${ACTUAL_HASH}" | tr -d '\r\n ')"

if [ "${EXPECTED_HASH}" != "${ACTUAL_HASH}" ]; then
    echo "Error: SHA-256 verification failed!" >&2
    echo "  Expected: ${EXPECTED_HASH}" >&2
    echo "  Actual:   ${ACTUAL_HASH}" >&2
    exit 1
fi

echo "SHA-256 checksum verified successfully."

# 6. Extract into target directory with path traversal protection
echo "Extracting JVM-MCP to ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}"
# Clean existing contents safely for idempotent upgrade
rm -rf "${INSTALL_DIR:?}"/*

while IFS= read -r entry; do
    case "$entry" in
        /*|../*|*/../*)
            echo "Error: Unsafe archive path detected: ${entry}" >&2
            exit 1
            ;;
    esac
done < <(tar -tzf "${TMP_DIR}/${RELEASE_ASSET}")

tar -xzf "${TMP_DIR}/${RELEASE_ASSET}" -C "${INSTALL_DIR}"

if [ -f "${INSTALL_DIR}/${EXECUTABLE_NAME}" ]; then
    chmod +x "${INSTALL_DIR}/${EXECUTABLE_NAME}"
fi

# 7. Create Symlink in BIN_DIR
echo "Creating symlink in ${BIN_DIR}..."
mkdir -p "${BIN_DIR}"
if [ -f "${INSTALL_DIR}/${EXECUTABLE_NAME}" ]; then
    ln -sf "${INSTALL_DIR}/${EXECUTABLE_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"
elif [ -f "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}" ]; then
    chmod +x "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}"
    ln -sf "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"
else
    echo "Error: Executable not found in unpacked files at ${INSTALL_DIR}" >&2
    exit 1
fi

# 8. Update PATH in shell configuration if necessary (idempotent with markers)
if [[ ":$PATH:" != *":${BIN_DIR}:"* ]]; then
    echo "Adding ${BIN_DIR} to PATH in shell configuration..."

    MODIFIED_RC=false
    for rc in "${HOME}/.bashrc" "${HOME}/.zshrc"; do
        if [ -f "$rc" ]; then
            if ! grep -q "jvm-mcp" "$rc"; then
                {
                    echo ""
                    echo "# >>> jvm-mcp >>>"
                    echo "export PATH=\"${BIN_DIR}:\$PATH\""
                    echo "# <<< jvm-mcp <<<"
                } >> "$rc"
            fi
            MODIFIED_RC=true
        fi
    done

    if [ "$MODIFIED_RC" = false ]; then
        TARGET_RC="${HOME}/.bashrc"
        if [ "${OS_NAME}" = "macos" ] || [[ "${SHELL:-}" == *"zsh"* ]]; then
            TARGET_RC="${HOME}/.zshrc"
        fi
        touch "$TARGET_RC"
        {
            echo ""
            echo "# >>> jvm-mcp >>>"
            echo "export PATH=\"${BIN_DIR}:\$PATH\""
            echo "# <<< jvm-mcp <<<"
        } >> "$TARGET_RC"
        echo "Configured ${TARGET_RC} with ${BIN_DIR} in PATH"
    fi

    echo "Please restart your terminal or run: source ~/.bashrc (or ~/.zshrc)"
fi

echo "Successfully installed ${EXECUTABLE_NAME} to ${BIN_DIR}"
echo ""
echo "Next step: Run '${EXECUTABLE_NAME} doctor' to verify runtime environment and JVM Attach API capabilities."

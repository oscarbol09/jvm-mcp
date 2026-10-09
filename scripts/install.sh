#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'
OS="$(uname -s)"
ARCH="$(uname -m)"
if [[ "${OS}" == "Linux" ]]; then
    if [[ "${ARCH}" == "x86_64" || "${ARCH}" == "amd64" ]]; then
        DOWNLOAD_URL="https://github.com/oscarbol09/jvm-mcp/releases/latest/download/jvm-mcp-linux-amd64.tar.gz"
    else
        echo "Error: Unsupported Linux arch ${ARCH}" >&2; exit 1
    fi
elif [[ "${OS}" == "Darwin" ]]; then
    if [[ "${ARCH}" == "arm64" || "${ARCH}" == "aarch64" ]]; then
        DOWNLOAD_URL="https://github.com/oscarbol09/jvm-mcp/releases/latest/download/jvm-mcp-macos-aarch64.tar.gz"
    else
        echo "Error: Unsupported macOS arch ${ARCH}" >&2; exit 1
    fi
else
    echo "Error: Unsupported OS ${OS}" >&2; exit 1
fi
readonly TMP_DIR=$(mktemp -d)
cleanup() {
    local ec=$?; rm -rf "${TMP_DIR}"; exit "${ec}"
}
trap cleanup EXIT ERR INT TERM
INSTALL_DIR="${HOME}/.jvm-mcp"
curl -fsSL "${DOWNLOAD_URL}" -o "${TMP_DIR}/jvm-mcp.tar.gz"
mkdir -p "${INSTALL_DIR}"
tar -xzf "${TMP_DIR}/jvm-mcp.tar.gz" -C "${INSTALL_DIR}"
BIN_DIR="${HOME}/.local/bin"
if [[ -w "/usr/local/bin" ]]; then BIN_DIR="/usr/local/bin"; fi
mkdir -p "${BIN_DIR}"
ln -sf "${INSTALL_DIR}/jvm-mcp" "${BIN_DIR}/jvm-mcp"

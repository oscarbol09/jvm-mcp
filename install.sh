#!/usr/bin/env bash
set -euo pipefail

# Configuration
REPO="dariux2016/jvm-mcp" # Replace with actual owner/repo or keep dynamic
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"

echo "========================================"
echo "    JVM-MCP Universal Installer"
echo "========================================"

# 1. Detect OS
OS="$(uname -s)"
case "${OS}" in
    Linux*)     OS_NAME="linux" ;;
    Darwin*)    OS_NAME="macos" ;;
    *)          echo "Error: Unsupported OS: ${OS}"; exit 1 ;;
esac

# 2. Detect Architecture
ARCH="$(uname -m)"
case "${ARCH}" in
    x86_64|amd64) ARCH_NAME="amd64" ;;
    aarch64|arm64) ARCH_NAME="aarch64" ;;
    *)             echo "Error: Unsupported architecture: ${ARCH}"; exit 1 ;;
esac

# Wait, the release artifact is a tar.gz for Mac and Linux because it bundles the JRE!
RELEASE_ASSET="jvm-mcp-${OS_NAME}-${ARCH_NAME}.tar.gz"
DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf -- "$TMP_DIR"' EXIT

echo "Downloading ${RELEASE_ASSET} from GitHub..."
curl -fsSL "${DOWNLOAD_URL}" -o "${TMP_DIR}/${RELEASE_ASSET}"

echo "Extracting bundled JRE..."
INSTALL_DIR="${HOME}/.jvm-mcp"
rm -rf "${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"
tar -xzf "${TMP_DIR}/${RELEASE_ASSET}" -C "${INSTALL_DIR}"

mkdir -p "${BIN_DIR}"
# Create a symlink to the bash launcher
ln -sf "${INSTALL_DIR}/${EXECUTABLE_NAME}-${OS_NAME}-${ARCH_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"

# 3. Update PATH if necessary
if [[ ":$PATH:" != *":${BIN_DIR}:"* ]]; then
    echo "Adding ${BIN_DIR} to PATH in shell configuration..."
    
    # Update Bash
    if [ -f "${HOME}/.bashrc" ]; then
        echo "export PATH=\"${BIN_DIR}:\$PATH\"" >> "${HOME}/.bashrc"
    fi
    # Update Zsh
    if [ -f "${HOME}/.zshrc" ]; then
        echo "export PATH=\"${BIN_DIR}:\$PATH\"" >> "${HOME}/.zshrc"
    fi
    echo "Please restart your terminal or run: source ~/.bashrc (or ~/.zshrc)"
fi

echo "✅ Successfully installed ${EXECUTABLE_NAME} to ${BIN_DIR}"

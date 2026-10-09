#!/usr/bin/env bash
set -euo pipefail

# Configuration
REPO="oscarbol09/jvm-mcp"
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"
INSTALL_DIR="${HOME}/.local/share/jvm-mcp"

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
    aarch64|arm64) ARCH_NAME="amd64" ; echo "Note: Using amd64 build on arm64 via Rosetta/translation" ;;
    *)             echo "Error: Unsupported architecture: ${ARCH}"; exit 1 ;;
esac

RELEASE_ASSET="jvm-mcp-${OS_NAME}-${ARCH_NAME}.tar.gz"
DOWNLOAD_URL="https://github.com/${REPO}/releases/latest/download/${RELEASE_ASSET}"

echo "Downloading ${RELEASE_ASSET} from GitHub..."
mkdir -p "${INSTALL_DIR}"
curl -fsSL "${DOWNLOAD_URL}" -o "/tmp/${RELEASE_ASSET}"

echo "Extracting JVM-MCP..."
tar -xzf "/tmp/${RELEASE_ASSET}" -C "${INSTALL_DIR}"
rm "/tmp/${RELEASE_ASSET}"

echo "Creating symlink in ${BIN_DIR}..."
mkdir -p "${BIN_DIR}"
ln -sf "${INSTALL_DIR}/jvm-mcp-${OS_NAME}-${ARCH_NAME}" "${BIN_DIR}/${EXECUTABLE_NAME}"

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

echo "Successfully installed ${EXECUTABLE_NAME} to ${BIN_DIR}"
echo "Run 'jvm-mcp --help' to get started."

#!/usr/bin/env bash
set -euo pipefail

# Configuration
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"
INSTALL_DIR="${HOME}/.local/share/jvm-mcp"

echo "Uninstalling JVM-MCP..."

if [ -L "${BIN_DIR}/${EXECUTABLE_NAME}" ] || [ -f "${BIN_DIR}/${EXECUTABLE_NAME}" ]; then
    rm -f "${BIN_DIR}/${EXECUTABLE_NAME}"
    echo "Removed symlink from ${BIN_DIR}/${EXECUTABLE_NAME}"
fi

if [ -d "${INSTALL_DIR}" ]; then
    rm -rf "${INSTALL_DIR}"
    echo "Removed installation files from ${INSTALL_DIR}"
fi

echo "JVM-MCP has been completely uninstalled from Linux/macOS."

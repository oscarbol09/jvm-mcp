#!/usr/bin/env bash
set -euo pipefail

# Configuration
BIN_DIR="${HOME}/.local/bin"
EXECUTABLE_NAME="jvm-mcp"
INSTALL_DIR="${HOME}/.local/share/jvm-mcp"

# Remove only complete blocks that the installer added. Never truncate a shell
# profile if a user has edited or accidentally removed one of the markers.
clean_shell_profiles() {
    local rc temp
    for rc in "${HOME}/.bashrc" "${HOME}/.zshrc" "${HOME}/.bash_profile" "${HOME}/.profile"; do
        [ -f "$rc" ] && [ ! -L "$rc" ] || continue
        grep -Fxq '# >>> jvm-mcp >>>' "$rc" || continue

        temp="$(mktemp "${rc}.jvm-mcp.XXXXXX")"
        cp -p "$rc" "$temp"
        if awk '
            /^# >>> jvm-mcp >>>$/ { if (inside) exit 1; inside = 1; next }
            /^# <<< jvm-mcp <<<$/{ if (!inside) exit 1; inside = 0; next }
            !inside { print }
            END { if (inside) exit 1 }
        ' "$rc" > "$temp"; then
            mv -f "$temp" "$rc"
            echo "Cleaned JVM-MCP PATH block from ${rc}"
        else
            rm -f "$temp"
            echo "Warning: unmatched JVM-MCP markers in ${rc}; left unchanged" >&2
            return 1
        fi
    done
}

echo "Uninstalling JVM-MCP..."

if [ -L "${BIN_DIR}/${EXECUTABLE_NAME}" ] || [ -f "${BIN_DIR}/${EXECUTABLE_NAME}" ]; then
    rm -f "${BIN_DIR}/${EXECUTABLE_NAME}"
    echo "Removed symlink from ${BIN_DIR}/${EXECUTABLE_NAME}"
fi

if [ -d "${INSTALL_DIR}" ]; then
    rm -rf "${INSTALL_DIR}"
    echo "Removed installation files from ${INSTALL_DIR}"
fi

clean_shell_profiles

echo "JVM-MCP has been completely uninstalled from Linux/macOS."

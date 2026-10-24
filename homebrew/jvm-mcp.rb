# typed: false
# frozen_string_literal: true
#
# Homebrew Formula Template for JVM-MCP
# Note: This file serves as the distribution template. The version and SHA-256
# placeholders (REPLACE_WITH_*) are automatically calculated and synchronized
# by .github/workflows/release.yml when a GitHub release tag is published.
class JvmMcp < Formula
  desc "Native Java Model Context Protocol (MCP) server for live Spring Boot inspection"
  homepage "https://github.com/oscarbol09/jvm-mcp"
  version "1.1.1"

  on_macos do
    on_arm do
      url "https://github.com/oscarbol09/jvm-mcp/releases/download/v#{version}/jvm-mcp-macos-aarch64.tar.gz"
      sha256 "REPLACE_WITH_MACOS_AARCH64_SHA256"
    end
    on_intel do
      url "https://github.com/oscarbol09/jvm-mcp/releases/download/v#{version}/jvm-mcp-macos-amd64.tar.gz"
      sha256 "REPLACE_WITH_MACOS_AMD64_SHA256"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/oscarbol09/jvm-mcp/releases/download/v#{version}/jvm-mcp-linux-amd64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_AMD64_SHA256"
    end
    on_arm do
      url "https://github.com/oscarbol09/jvm-mcp/releases/download/v#{version}/jvm-mcp-linux-aarch64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_AARCH64_SHA256"
    end
  end

  def install
    libexec.install Dir["*"]
    bin.install_symlink libexec/"jvm-mcp"
  end

  test do
    system "#{bin}/jvm-mcp", "--version"
  end
end

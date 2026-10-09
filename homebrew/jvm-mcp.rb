class JvmMcp < Formula
  desc "Native Java Model Context Protocol (MCP) server for live JVM inspection"
  homepage "https://github.com/oscarbol09/jvm-mcp"
  
  # For the initial template, we map to the latest release tarball.
  # A Homebrew releaser GitHub Action should automatically update the url and sha256.
  url "https://github.com/oscarbol09/jvm-mcp/releases/latest/download/jvm-mcp-macos-amd64.tar.gz"
  version "1.0.0"
  sha256 "REPLACE_WITH_ACTUAL_SHA256"

  def install
    # The tarball contains:
    # - custom-jre/
    # - app.jar
    # - jvm-mcp-macos-amd64 (bash launcher script)
    
    # We install the internal payload to libexec so it's not exposed in the user's PATH
    libexec.install "custom-jre", "app.jar", "jvm-mcp-macos-amd64"
    
    # We create a symlink in bin/ that points to the bash launcher in libexec/
    bin.install_symlink libexec/"jvm-mcp-macos-amd64" => "jvm-mcp"
  end

  test do
    system "#{bin}/jvm-mcp", "--version"
  end
end

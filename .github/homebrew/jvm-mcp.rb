class JvmMcp < Formula
  desc "Zero-dependency JVM diagnostics and control via Model Context Protocol"
  homepage "https://github.com/dariux2016/jvm-mcp"
  version "1.0.0"

  if OS.mac?
    if Hardware::CPU.arm?
      url "https://github.com/dariux2016/jvm-mcp/releases/download/v#{version}/jvm-mcp-macos-aarch64.tar.gz"
      sha256 "REPLACE_WITH_MAC_ARM_SHA256"
    else
      url "https://github.com/dariux2016/jvm-mcp/releases/download/v#{version}/jvm-mcp-macos-amd64.tar.gz"
      sha256 "REPLACE_WITH_MAC_INTEL_SHA256"
    end
  elsif OS.linux?
    if Hardware::CPU.intel?
      url "https://github.com/dariux2016/jvm-mcp/releases/download/v#{version}/jvm-mcp-linux-amd64.tar.gz"
      sha256 "REPLACE_WITH_LINUX_AMD64_SHA256"
    end
  end

  def install
    # The tar.gz contains custom-jre, app.jar, and the executable launcher
    libexec.install Dir["*"]
    
    if OS.mac? && Hardware::CPU.arm?
      bin.install_symlink libexec/"jvm-mcp-macos-aarch64" => "jvm-mcp"
    elsif OS.mac? && Hardware::CPU.intel?
      bin.install_symlink libexec/"jvm-mcp-macos-amd64" => "jvm-mcp"
    elsif OS.linux?
      bin.install_symlink libexec/"jvm-mcp-linux-amd64" => "jvm-mcp"
    end
  end

  test do
    system "#{bin}/jvm-mcp", "--version"
  end
end

#!/usr/bin/env bash
# Install noum — the Noumenon CLI launcher
# Usage: curl -sSL https://noumenon.dev/install | bash

set -euo pipefail

REPO="leifericf/noumenon"
INSTALL_DIR="${HOME}/.local/bin"

detect_os() {
  case "$(uname -s)" in
    Linux*)  echo "linux" ;;
    Darwin*) echo "macos" ;;
    *)       echo "unsupported" ;;
  esac
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64)  echo "x86_64" ;;
    aarch64|arm64) echo "arm64" ;;
    *)             echo "unsupported" ;;
  esac
}

main() {
  local os arch binary url

  os=$(detect_os)
  arch=$(detect_arch)

  if [ "$os" = "unsupported" ] || [ "$arch" = "unsupported" ]; then
    echo "Error: Unsupported platform $(uname -s)/$(uname -m)"
    echo "Supported: macOS (arm64, x86_64), Linux (arm64, x86_64)"
    exit 1
  fi

  binary="noum-${os}-${arch}"
  echo "Detected platform: ${os}/${arch}"

  # Get latest release URL
  url=$(curl -sSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep "browser_download_url.*${binary}" \
    | head -1 \
    | cut -d '"' -f 4)

  if [ -z "$url" ]; then
    echo "Error: Could not find ${binary} in the latest release."
    echo "Check https://github.com/${REPO}/releases"
    exit 1
  fi

  echo "Downloading ${binary}..."
  mkdir -p "$INSTALL_DIR"
  curl -sSL "$url" -o "${INSTALL_DIR}/noum"
  chmod +x "${INSTALL_DIR}/noum"

  echo ""
  echo "✓ Installed noum to ${INSTALL_DIR}/noum"

  # Check if INSTALL_DIR is on PATH
  if ! echo "$PATH" | tr ':' '\n' | grep -q "^${INSTALL_DIR}$"; then
    echo ""
    echo "Add ${INSTALL_DIR} to your PATH:"
    echo "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.zshrc"
    echo ""
    echo "Then restart your shell or run: source ~/.zshrc"
  fi

  echo ""
  echo "Get started:"
  echo "  noum help"
  echo "  noum import /path/to/repo"
}

main "$@"

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

  # Get SHA256 sidecar URL
  local sha_url
  sha_url=$(curl -sSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep "browser_download_url.*${binary}.sha256" \
    | head -1 \
    | cut -d '"' -f 4)

  echo "Downloading ${binary}..."
  mkdir -p "$INSTALL_DIR"
  curl -sSL "$url" -o "${INSTALL_DIR}/noum"

  # Verify SHA256 integrity if sidecar is available
  if [ -n "$sha_url" ]; then
    local expected actual
    expected=$(curl -sSL "$sha_url")
    if command -v sha256sum >/dev/null 2>&1; then
      actual=$(sha256sum "${INSTALL_DIR}/noum" | cut -d' ' -f1)
    elif command -v shasum >/dev/null 2>&1; then
      actual=$(shasum -a 256 "${INSTALL_DIR}/noum" | cut -d' ' -f1)
    fi
    if [ -n "$actual" ] && [ "$expected" != "$actual" ]; then
      echo "Error: SHA256 mismatch! Expected ${expected}, got ${actual}"
      echo "The downloaded binary may be corrupted or tampered with."
      rm -f "${INSTALL_DIR}/noum"
      exit 1
    fi
    echo "✓ SHA256 verified"
  fi

  chmod +x "${INSTALL_DIR}/noum"

  echo ""
  echo "✓ Installed noum to ${INSTALL_DIR}/noum"

  # Check if INSTALL_DIR is on PATH
  if ! echo "$PATH" | tr ':' '\n' | grep -q "^${INSTALL_DIR}$"; then
    echo ""
    echo "Add ${INSTALL_DIR} to your PATH:"
    case "${SHELL:-}" in
      */fish)
        echo "  fish_add_path ${INSTALL_DIR}"
        echo ""
        echo "Then restart your shell."
        ;;
      */bash)
        local rc="${HOME}/.bashrc"
        [ -f "${HOME}/.bash_profile" ] && rc="${HOME}/.bash_profile"
        echo "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ${rc}"
        echo ""
        echo "Then restart your shell or run: source ${rc}"
        ;;
      *)
        echo "  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.zshrc"
        echo ""
        echo "Then restart your shell or run: source ~/.zshrc"
        ;;
    esac
  fi

  echo ""
  echo "Get started:"
  echo "  noum help"
  echo "  noum import /path/to/repo"
}

main "$@"

(ns noum.install
  "Install Claude Desktop and Claude Code CLI."
  (:require [babashka.process :as proc]
            [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]
            [noum.tui.spinner :as spinner]))

(defn- macos? []
  (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))

(defn- windows? []
  (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))

(defn- command-exists? [cmd]
  (try
    (let [finder (if (windows?) "where" "which")]
      (zero? (:exit (proc/shell {:out :string :err :string :continue true}
                                finder cmd))))
    (catch Exception _ false)))

(defn install-desktop!
  "Install Claude Desktop app."
  []
  (cond
    (not (macos?))
    (do (tui/eprintln "Claude Desktop auto-install is only supported on macOS.")
        (tui/eprintln "Download from: https://claude.ai/download"))

    (not (command-exists? "brew"))
    (do (tui/eprintln "Homebrew required. Install it from https://brew.sh")
        (tui/eprintln "Then run: brew install --cask claude"))

    :else
    (let [s (spinner/start "Installing Claude Desktop via Homebrew...")]
      (proc/shell "brew" "install" "--cask" "claude")
      ((:stop s) "Claude Desktop installed.")
      (tui/eprintln "  Next: run 'noum setup desktop' to connect Noumenon to Claude."))))

(defn install-code!
  "Install Claude Code CLI."
  []
  (cond
    (command-exists? "claude")
    (tui/eprintln (str (style/green "✓") " Claude Code is already installed."))

    (command-exists? "npm")
    (let [s (spinner/start "Installing Claude Code via npm...")]
      (proc/shell "npm" "install" "-g" "@anthropic-ai/claude-code")
      ((:stop s) "Claude Code installed.")
      (tui/eprintln "  Next: run 'noum setup code' from your project directory to connect Noumenon to Claude."))

    (command-exists? "brew")
    (let [s (spinner/start "Installing Claude Code via Homebrew...")]
      (proc/shell "brew" "install" "claude-code")
      ((:stop s) "Claude Code installed.")
      (tui/eprintln "  Next: run 'noum setup code' from your project directory to connect Noumenon to Claude."))

    :else
    (do (tui/eprintln "npm or Homebrew required to install Claude Code.")
        (tui/eprintln "Install npm: https://nodejs.org")
        (tui/eprintln "Or Homebrew: https://brew.sh"))))

(defn install-both!
  "Install both Claude Desktop and Claude Code."
  []
  (install-desktop!)
  (install-code!))

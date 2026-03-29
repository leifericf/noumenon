(ns noum.setup
  "Configure MCP for Claude Desktop and Claude Code."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [noum.tui.core :as tui]
            [noum.tui.style :as style]))

(defn- claude-desktop-config-path []
  (let [os (System/getProperty "os.name")]
    (cond
      (re-find #"Mac" os)
      (str (fs/path (fs/home) "Library" "Application Support" "Claude"
                    "claude_desktop_config.json"))
      (re-find #"Win" os)
      (let [appdata (or (System/getenv "APPDATA")
                        (str (fs/path (fs/home) "AppData" "Roaming")))]
        (str (fs/path appdata "Claude" "claude_desktop_config.json")))
      :else
      (str (fs/path (fs/home) ".config" "Claude" "claude_desktop_config.json")))))

(defn- noum-bin-path []
  (or (System/getenv "NOUM_BIN")
      (str (fs/path (fs/home) ".local" "bin" "noum"))))

(defn- merge-mcp-config [existing]
  (let [config (or existing {})]
    (update config "mcpServers"
            (fn [servers]
              (assoc (or servers {})
                     "noumenon"
                     {"command" (noum-bin-path)
                      "args"    ["serve"]})))))

(defn setup-desktop!
  "Write MCP config for Claude Desktop."
  []
  (let [path     (claude-desktop-config-path)
        existing (when (fs/exists? path)
                   (json/parse-string (slurp path)))
        updated  (merge-mcp-config existing)]
    (fs/create-dirs (fs/parent path))
    (spit path (json/generate-string updated {:pretty true}))
    (tui/eprintln (str (style/green "✓") " Wrote MCP config to " path))
    (tui/eprintln "  Restart Claude Desktop to activate.")))

(defn setup-code!
  "Write .mcp.json in the current directory for Claude Code."
  []
  (let [path    ".mcp.json"
        config  {"mcpServers"
                 {"noumenon"
                  {"command" (noum-bin-path)
                   "args"    ["serve"]}}}]
    (spit path (json/generate-string config {:pretty true}))
    (tui/eprintln (str (style/green "✓") " Wrote " path))
    (tui/eprintln "  Noumenon MCP server is ready.")))

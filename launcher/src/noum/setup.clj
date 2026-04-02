(ns noum.setup
  "Configure MCP for Claude Desktop and Claude Code."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [noum.tui.core :as tui]
            [noum.tui.prompt :as prompt]
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
      (some-> (fs/which "noum") str)
      (str (fs/path (fs/home) ".local" "bin" "noum"))))

(defn- merge-mcp-config [existing]
  (let [config (or existing {})]
    (update config "mcpServers"
            (fn [servers]
              (assoc (or servers {})
                     "noumenon"
                     {"command" (noum-bin-path)
                      "args"    ["serve"]})))))

(def ^:private credentials-path
  (str (fs/path (fs/home) ".noumenon" "credentials")))

(defn- read-credentials
  "Read existing credentials file as {key value} map. Returns {} if missing."
  []
  (if (fs/exists? credentials-path)
    (->> (str/split-lines (slurp credentials-path))
         (keep (fn [line]
                 (when-let [[_ k v] (re-matches #"(?:export\s+)?([A-Z_]+)=(.+)" (str/trim line))]
                   [k (-> v str/trim (str/replace #"^[\"']|[\"']$" ""))])))
         (into {}))
    {}))

(defn- write-credentials!
  "Write credentials map to file. Preserves comments from existing file."
  [creds]
  (fs/create-dirs (str (fs/path (fs/home) ".noumenon")))
  (let [header "# Noumenon credentials\n# https://github.com/leifericf/noumenon\n\n"
        lines  (->> creds
                    (map (fn [[k v]] (str k "=" v)))
                    (str/join "\n"))]
    (spit credentials-path (str header lines "\n"))
    (try
      (fs/set-posix-file-permissions credentials-path "rw-------")
      (catch Exception _))))

(defn- ensure-credentials!
  "Prompt for LLM provider tokens. Idempotent: existing values shown as defaults,
   blank input keeps the current value, new input overwrites."
  []
  (let [existing (read-credentials)
        zai-cur  (get existing "NOUMENON_ZAI_TOKEN")
        ant-cur  (get existing "ANTHROPIC_API_KEY")
        has-any  (or zai-cur ant-cur)]
    (if has-any
      (tui/eprintln (str (style/green "✓") " Credentials configured"
                         (when zai-cur " (GLM)")
                         (when ant-cur " (Anthropic)")
                         "."))
      (tui/eprintln (str (style/dim "  LLM credentials — at least one provider token is needed."))))
    (let [zai-new (prompt/ask-secret
                   (str "GLM token (NOUMENON_ZAI_TOKEN)"
                        (when zai-cur (str " " (style/dim "[configured]")))))
          ant-new (prompt/ask-secret
                   (str "Anthropic API key (ANTHROPIC_API_KEY)"
                        (when ant-cur (str " " (style/dim "[configured]")))))
          final   (cond-> existing
                    zai-new (assoc "NOUMENON_ZAI_TOKEN" zai-new)
                    ant-new (assoc "ANTHROPIC_API_KEY" ant-new))]
      (when (or zai-new ant-new)
        (write-credentials! final)
        (tui/eprintln (str (style/green "✓") " Saved to " credentials-path)))
      (when (and (not has-any) (not zai-new) (not ant-new))
        (tui/eprintln (str (style/dim "  Skipped. Add tokens later: ") credentials-path))))))

(defn setup-desktop!
  "Write MCP config for Claude Desktop."
  []
  (let [path     (claude-desktop-config-path)
        existing (when (fs/exists? path)
                   (json/parse-string (slurp path)))
        updated  (merge-mcp-config existing)
        content  (json/generate-string updated {:pretty true})]
    (if (and (fs/exists? path) (= (slurp path) content))
      (tui/eprintln (str (style/green "✓") " " path " already configured."))
      (do (fs/create-dirs (fs/parent path))
          (spit path content)
          (tui/eprintln (str (style/green "✓") " Wrote MCP config to " path))
          (tui/eprintln "  Restart Claude Desktop to activate."))))
  (ensure-credentials!))

;; --- Hook script ---

(def ^:private hook-script
  "#!/usr/bin/env bash
# PreToolUse hook: enforce MCP-first policy.
# Blocks Read/Glob/Grep until at least one noumenon MCP tool has been called.
set -euo pipefail

INPUT=$(cat)

TOOL_NAME=$(echo \"$INPUT\" | jq -r '.tool_name // empty')
SESSION_ID=$(echo \"$INPUT\" | jq -r '.session_id // empty')
TRANSCRIPT=$(echo \"$INPUT\" | jq -r '.transcript_path // empty')

[ -z \"$TOOL_NAME\" ] && exit 0
[ -z \"$SESSION_ID\" ] && exit 0

case \"$TOOL_NAME\" in
    Read|Glob|Grep) ;;
    *) exit 0 ;;
esac

STATE_DIR=\"${XDG_RUNTIME_DIR:-${HOME}/.noumenon/tmp}/mcp-sessions\"
STATE_FILE=\"$STATE_DIR/$SESSION_ID\"

[ -f \"$STATE_FILE\" ] && exit 0

if [ -n \"$TRANSCRIPT\" ] && [ -f \"$TRANSCRIPT\" ]; then
    if grep -q -m1 \"mcp__noumenon__\" \"$TRANSCRIPT\" 2>/dev/null; then
        mkdir -p \"$STATE_DIR\"
        touch \"$STATE_FILE\"
        exit 0
    fi
fi

cat >&2 <<'JSON'
{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\",\"additionalContext\":\"BLOCKED: Query the Noumenon knowledge graph BEFORE reading files. Call noumenon_status, noumenon_query, or noumenon_ask first. See CLAUDE.md for the required workflow.\"}}
JSON
exit 2
")

(defn- write-hook!
  "Write the MCP-first enforcement hook script."
  []
  (let [dir  ".claude/hooks"
        path (str dir "/noumenon-mcp-first.sh")]
    (fs/create-dirs dir)
    (if (and (fs/exists? path) (= (slurp path) hook-script))
      (tui/eprintln (str (style/green "✓") " Hook already installed."))
      (do (spit path hook-script)
          (.setExecutable (java.io.File. path) true)
          (tui/eprintln (str (style/green "✓") " Wrote " (str (fs/absolutize path))))))))

;; --- settings.local.json ---

(def ^:private hook-matcher
  {"matcher" "Read|Glob|Grep"
   "hooks"   [{"type"    "command"
               "command" ".claude/hooks/noumenon-mcp-first.sh"}]})

(defn- has-noumenon-hook? [settings]
  (some (fn [entry]
          (and (= "Read|Glob|Grep" (get entry "matcher"))
               (some #(str/includes? (get % "command" "") "noumenon-mcp-first")
                     (get entry "hooks" []))))
        (get-in settings ["hooks" "PreToolUse"] [])))

(defn- merge-settings [existing]
  (let [settings (or existing {})]
    (if (has-noumenon-hook? settings)
      settings
      (update-in settings ["hooks" "PreToolUse"]
                 (fn [hooks] (vec (conj (or hooks []) hook-matcher)))))))

(defn- write-settings!
  "Write or merge .claude/settings.local.json with the hook entry."
  []
  (let [path     ".claude/settings.local.json"
        existing (when (fs/exists? path)
                   (json/parse-string (slurp path)))
        updated  (merge-settings existing)
        content  (json/generate-string updated {:pretty true})]
    (fs/create-dirs ".claude")
    (if (and (fs/exists? path) (= (slurp path) content))
      (tui/eprintln (str (style/green "✓") " Settings already configured."))
      (do (spit path content)
          (tui/eprintln (str (style/green "✓") " Wrote " (str (fs/absolutize path))))))))

;; --- CLAUDE.md ---

(def ^:private claude-md-block
  "# Noumenon MCP — Query Before Reading

**Use the Noumenon MCP tools before Read, Glob, or Grep.** This project has a knowledge graph that knows about file structure, dependencies, complexity, and commit history.

1. Call `noumenon_status` to check the graph is populated.
2. Use `noumenon_query` or `noumenon_ask` to find what you need.
3. Then read specific files for implementation details.

A PreToolUse hook enforces this — file-reading tools are blocked until a Noumenon MCP query has been made.
")

(def ^:private claude-md-marker
  "Noumenon MCP")

(defn- write-claude-md!
  "Write or append Noumenon block to CLAUDE.md. Skips if marker already present."
  []
  (let [path "CLAUDE.md"]
    (if (and (fs/exists? path)
             (str/includes? (slurp path) claude-md-marker))
      (tui/eprintln (str (style/green "✓") " CLAUDE.md already has Noumenon instructions."))
      (do (spit path
                (if (fs/exists? path)
                  (str (str/trimr (slurp path)) "\n\n" claude-md-block)
                  claude-md-block))
          (tui/eprintln (str (style/green "✓") " Wrote Noumenon instructions to CLAUDE.md"))))))

;; --- setup-code! ---

(defn setup-code!
  "Write .mcp.json, hook, settings, and CLAUDE.md for Claude Code."
  []
  (tui/eprintln (str (style/dim "  Target directory: ") (str (fs/absolutize "."))))
  (let [path     ".mcp.json"
        abs-path (str (fs/absolutize path))
        config   {"mcpServers"
                  {"noumenon"
                   {"command" (noum-bin-path)
                    "args"    ["serve"]}}}
        content  (json/generate-string config {:pretty true})]
    (if (and (fs/exists? path) (= (slurp path) content))
      (tui/eprintln (str (style/green "✓") " " abs-path " already configured."))
      (do (spit path content)
          (tui/eprintln (str (style/green "✓") " Wrote " abs-path)))))
  (write-hook!)
  (write-settings!)
  (write-claude-md!)
  (ensure-credentials!)
  (tui/eprintln "\n  Run this command from your project directory."))

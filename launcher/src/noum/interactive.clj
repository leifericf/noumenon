(ns noum.interactive
  "Interactive TUI menu for noum (no-args mode)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [noum.api :as api]
            [noum.cli :as cli]
            [noum.tui.choose :as choose]
            [noum.tui.core :as tui]
            [noum.tui.prompt :as prompt]
            [noum.tui.style :as style]))

;; --- Data fetchers ---

(defn- fetch-databases [conn]
  (let [resp (api/get! conn "/api/databases")]
    (when (:ok resp)
      (->> (:data resp)
           (mapv (fn [{:keys [name entities stage]}]
                   {:label (str name (style/dim (str "  " (or entities "?") " entities, " (or stage "unknown"))))
                    :value name}))))))

(defn- fetch-sessions [conn]
  (let [resp (api/get! conn "/api/ask/sessions")]
    (when (:ok resp)
      (->> (:data resp)
           (take 20)
           (mapv (fn [{:keys [id question started-at]}]
                   {:label (str (style/dim (str id "  "))
                                (let [q (or question "—")]
                                  (if (> (count q) 60)
                                    (str (subs q 0 57) "…")
                                    q)))
                    :value id}))))))

(defn- fetch-queries [conn]
  (let [resp (api/get! conn "/api/queries")]
    (when (:ok resp)
      (->> (:data resp)
           (mapv (fn [{:keys [name description]}]
                   {:label (str name (style/dim (str "  " (or description ""))))
                    :value name}))))))

(defn- fetch-introspect-runs [conn]
  (let [resp (api/get! conn "/api/introspect/history?query_name=introspect-runs")]
    (when (:ok resp)
      (->> (:data resp)
           (mapv (fn [run]
                   (let [id   (or (:id run) (first (vals run)))
                         repo (or (:repo run) (second (vals run)))]
                     {:label (str id (style/dim (str "  " (or repo ""))))
                      :value id})))))))

(defn- fetch-benchmark-runs [conn repo]
  (let [resp (api/get! conn (str "/api/benchmark/results?repo_path=" (api/url-encode repo)))]
    (when (:ok resp)
      (let [runs (or (:runs (:data resp)) [(:data resp)])]
        (->> runs
             (filter :run-id)
             (mapv (fn [{:keys [run-id started-at score]}]
                     {:label (str run-id (style/dim (str "  " (or started-at "") (when score (str "  score: " score)))))
                      :value run-id})))))))

;; --- Selectors ---

(defn- select-from
  "Fetch items, present menu. Returns selected :value or nil."
  [conn label fetch-fn]
  (if-let [items (seq (fetch-fn conn))]
    (when-let [chosen (choose/select label (vec items))]
      (:value chosen))
    (do (tui/eprintln (str (style/yellow "  No " label " found.")))
        nil)))

(defn- select-repo [conn]
  (select-from conn "repository" fetch-databases))

(defn- select-session [conn]
  (select-from conn "session" fetch-sessions))

(defn- select-query-name [conn]
  (select-from conn "query" fetch-queries))

(defn- select-introspect-run [conn]
  (select-from conn "introspect run" fetch-introspect-runs))

(defn- select-benchmark-run [conn repo]
  (if-let [items (seq (fetch-benchmark-runs conn repo))]
    (when-let [chosen (choose/select "benchmark run" (vec items))]
      (:value chosen))
    (do (tui/eprintln (str (style/yellow "  No benchmark runs found.")))
        nil)))

;; --- Arg collectors ---
;; Each returns a parsed map {:command ... :flags ... :positional ...} or nil to abort.

(defn- collect-repo-command
  "Collect args for commands that just need a repo."
  [conn command]
  (when-let [repo (select-repo conn)]
    {:command command :flags {} :positional [repo]}))

(defn- collect-ask [conn]
  (when-let [repo (select-repo conn)]
    (when-let [question (prompt/ask "Question:")]
      (if (str/blank? question)
        (do (tui/eprintln (style/yellow "  Question cannot be blank."))
            nil)
        {:command "ask" :flags {} :positional [repo question]}))))

(defn- collect-query [conn]
  (when-let [qname (select-query-name conn)]
    (when-let [repo (select-repo conn)]
      {:command "query" :flags {} :positional [qname repo]})))

(defn- collect-feedback [conn]
  (when-let [session-id (select-session conn)]
    (when-let [rating (choose/select "rating" [{:label "positive" :value "positive"}
                                               {:label "negative" :value "negative"}])]
      (let [comment (prompt/ask "Comment (optional):")]
        {:command "feedback"
         :flags   (if (str/blank? comment) {} {:comment comment})
         :positional [session-id (:value rating)]}))))

(defn- collect-compare [conn]
  (when-let [repo (select-repo conn)]
    (tui/eprintln (style/dim "  Select first run:"))
    (when-let [run-a (select-benchmark-run conn repo)]
      (tui/eprintln (style/dim "  Select second run:"))
      (when-let [run-b (select-benchmark-run conn repo)]
        {:command "compare" :flags {} :positional [repo run-a run-b]}))))

(defn- collect-delete [conn]
  (when-let [repo (select-repo conn)]
    {:command "delete" :flags {} :positional [repo]}))

(defn- collect-delta-ensure [conn]
  (when-let [repo (select-repo conn)]
    (when-let [basis (prompt/ask "Basis SHA (40-char hex):")]
      (when-not (str/blank? basis)
        (let [branch (prompt/ask "Branch name (optional, defaults to current):")]
          {:command "delta-ensure"
           :flags   (cond-> {:basis-sha basis}
                      (not (str/blank? branch)) (assoc :branch branch))
           :positional [repo]})))))

(defn- collect-setup [_conn]
  (when-let [target (choose/select "target" [{:label "Claude Desktop" :value "desktop"}
                                             {:label "Claude Code"    :value "code"}])]
    {:command "setup" :flags {} :positional [(:value target)]}))

(defn- collect-introspect [conn]
  (when-let [action (choose/select "introspect"
                                   [{:label "Start new run"   :value :start}
                                    {:label "Check run status" :value :status}
                                    {:label "Stop a run"       :value :stop}
                                    {:label "View history"     :value :history}])]
    (case (:value action)
      :start   (when-let [repo (select-repo conn)]
                 {:command "introspect" :flags {} :positional [repo]})
      :status  (when-let [run-id (select-introspect-run conn)]
                 {:command "introspect" :flags {:status run-id} :positional []})
      :stop    (when-let [run-id (select-introspect-run conn)]
                 {:command "introspect" :flags {:stop run-id} :positional []})
      :history {:command "introspect" :flags {:history true} :positional []}
      nil)))

(defn- collect-sessions [conn]
  (when-let [action (choose/select "sessions"
                                   [{:label "List all sessions" :value :list}
                                    {:label "View session detail" :value :detail}])]
    (case (:value action)
      :list   {:command "sessions" :flags {} :positional []}
      :detail (when-let [sid (select-session conn)]
                {:command "sessions" :flags {} :positional [sid]})
      nil)))

(defn- collect-settings [conn]
  (when-let [action (choose/select "settings"
                                   [{:label "View all settings" :value :list}
                                    {:label "View a setting"    :value :get}
                                    {:label "Change a setting"  :value :set}])]
    (case (:value action)
      :list {:command "settings" :flags {} :positional []}
      :get  (when-let [key (prompt/ask "Setting key:")]
              {:command "settings" :flags {} :positional [key]})
      :set  (when-let [key (prompt/ask "Setting key:")]
              (when-let [val (prompt/ask "Value:")]
                {:command "settings" :flags {} :positional [key val]}))
      nil)))

(defn- collect-history [_conn]
  (when-let [atype (choose/select "artifact type" [{:label "rules"  :value "rules"}
                                                   {:label "prompt" :value "prompt"}])]
    (if (= "rules" (:value atype))
      {:command "history" :flags {} :positional ["rules"]}
      (let [prompt-names (->> (io/resource "prompts/")
                              io/file .listFiles seq
                              (keep #(when (str/ends-with? (.getName %) ".edn")
                                       (str/replace (.getName %) ".edn" "")))
                              sort)
            options      (mapv (fn [n] {:label n :value n}) prompt-names)]
        (when-let [pname (choose/select "prompt" options)]
          {:command "history" :flags {} :positional ["prompt" (:value pname)]})))))

(def ^:private arg-collectors
  "Commands that need interactive arg collection. Missing = dispatch immediately."
  {"digest"     collect-repo-command
   "import"     collect-repo-command
   "analyze"    collect-repo-command
   "enrich"     collect-repo-command
   "synthesize" collect-repo-command
   "update"     collect-repo-command
   "watch"      collect-repo-command
   "delta-ensure" (fn [conn _] (collect-delta-ensure conn))
   "ask"        (fn [conn _] (collect-ask conn))
   "query"      (fn [conn _] (collect-query conn))
   "bench"      collect-repo-command
   "results"    collect-repo-command
   "compare"    (fn [conn _] (collect-compare conn))
   "introspect" (fn [conn _] (collect-introspect conn))
   "sessions"   (fn [conn _] (collect-sessions conn))
   "settings"   (fn [conn _] (collect-settings conn))
   "status"     collect-repo-command
   "schema"     collect-repo-command
   "delete"     (fn [conn _] (collect-delete conn))
   "feedback"   (fn [conn _] (collect-feedback conn))
   "setup"      (fn [conn _] (collect-setup conn))
   "history"    (fn [conn _] (collect-history conn))
   "connect"    (fn [_conn _]
                  (when-let [target (prompt/ask "URL or name (or 'local'):")]
                    (when-not (str/blank? target)
                      (if (= target "local")
                        {:command "connect" :flags {} :positional ["local"]}
                        (let [token (prompt/ask "Token (optional):")
                              cname (prompt/ask "Connection name (optional):")]
                          {:command "connect"
                           :flags   (cond-> {}
                                      (not (str/blank? token)) (assoc :token token)
                                      (not (str/blank? cname)) (assoc :name cname))
                           :positional [target]})))))
   "disconnect" (fn [_conn _]
                  (when-let [name (prompt/ask "Connection name:")]
                    (when-not (str/blank? name)
                      {:command "disconnect" :flags {} :positional [name]})))})

;; --- Commands to exclude from interactive menu ---

(def ^:private excluded-commands
  #{"serve" "start" "stop" "ping" "upgrade" "help" "version" "open"})

;; --- Menu loop ---

(defn- menu-groups
  "Build interactive menu groups, filtering out excluded commands and empty groups."
  []
  (->> cli/command-groups
       (map (fn [[group cmds]]
              [group (remove excluded-commands cmds)]))
       (remove (comp empty? second))
       vec))

(defn- command-options
  "Build choose options for commands in a group."
  [cmds]
  (conj (mapv (fn [cmd]
                {:label (str cmd (style/dim (str "  " (:summary (cli/commands cmd) ""))))
                 :value cmd})
              cmds)
        {:label (style/dim "← Back") :value :back}))

(defn- group-options
  "Build choose options for groups."
  [groups]
  (conj (mapv (fn [[group _]] {:label group :value group}) groups)
        {:label (style/dim "Quit") :value :quit}))

(def ^:private no-backend-commands
  "Commands that don't need the backend daemon."
  #{"setup" "connect" "disconnect" "connections" "help" "version"})

(defn run!
  "Interactive menu loop. Requires dispatch table and do-api-command fallback."
  [dispatch-table default-handler]
  (let [groups (menu-groups)
        conn   (atom nil)
        ensure-conn! (fn [] (or @conn (reset! conn (api/ensure-backend! {}))))]
    (tui/eprintln (str "\n" (style/bold "noumenon") " — interactive mode\n"))
    (loop []
      (when-let [group (choose/select "What would you like to do?" (group-options groups))]
        (when-not (= :quit (:value group))
          (let [cmds (second (first (filter #(= (:value group) (first %)) groups)))]
            (when-let [cmd (choose/select (:value group) (command-options cmds))]
              (when-not (= :back (:value cmd))
                (let [command   (:value cmd)
                      ;; Only start the daemon when the command actually needs it
                      conn-val  (when-not (no-backend-commands command)
                                  (ensure-conn!))
                      collector (get arg-collectors command)
                      parsed    (if collector
                                  (collector conn-val command)
                                  {:command command :flags {} :positional []})]
                  (when parsed
                    (let [handler (get dispatch-table command default-handler)]
                      (tui/eprintln "")
                      (handler parsed)
                      (tui/eprintln "")))))))
          (recur))))
    0))

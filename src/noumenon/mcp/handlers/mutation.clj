(ns noumenon.mcp.handlers.mutation
  "MCP tool handlers for write-side traffic — import / update / analyze /
   enrich / synthesize / digest, plus the LLM-driven `ask` agent."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.agent :as agent]
            [noumenon.analyze :as analyze]
            [noumenon.artifacts :as artifacts]
            [noumenon.benchmark :as bench]
            [noumenon.db :as db]
            [noumenon.embed :as embed]
            [noumenon.files :as files]
            [noumenon.git :as git]
            [noumenon.imports :as imports]
            [noumenon.llm :as llm]
            [noumenon.mcp.util :as mu]
            [noumenon.repo :as repo]
            [noumenon.sync :as sync]
            [noumenon.synthesize :as synthesize]
            [noumenon.util :as util :refer [log!]]))

(defn- format-import-summary [git-r files-r]
  (str "Import complete. "
       (:commits-imported git-r) " commits imported, "
       (:commits-skipped git-r) " skipped. "
       (:files-imported files-r) " files imported, "
       (:files-skipped files-r) " skipped. "
       (:dirs-imported files-r) " directories imported."))

(defn handle-import [args defaults]
  (mu/with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [git-r   (git/import-commits! conn repo-path repo-path (:progress-fn defaults))
            files-r (files/import-files! conn repo-path repo-path)]
        (mu/tool-result (format-import-summary git-r files-r))))))

(defn- format-update-changes
  "Format update result as a human-readable summary string."
  [result]
  (let [changes (str (when (pos? (:added result 0)) (str " " (:added result) " files added."))
                     (when (pos? (:modified result 0)) (str " " (:modified result) " modified."))
                     (when (pos? (:deleted result 0)) (str " " (:deleted result) " deleted."))
                     (when (pos? (:commits result 0)) (str " " (:commits result) " new commits.")))]
    (if (seq changes)
      (str "Update complete." changes)
      "Already up to date.")))

(defn handle-update [args defaults]
  (util/validate-string-length! "repo_path" (args "repo_path") mu/max-repo-path-len)
  (mu/validate-llm-inputs! args)
  (let [db-dir    (util/resolve-db-dir defaults)
        {:keys [repo-path db-name]}
        (repo/resolve-repo (args "repo_path") db-dir {:lookup-uri-fn mu/lookup-repo-uri})
        conn      (mu/get-or-create-conn db-dir db-name)
        meta-conn (db/ensure-meta-db db-dir)
        analyze?  (args "analyze")
        selector  (mu/selector-opts args)
        opts      (if analyze?
                    (let [{:keys [prompt-fn model-id]}
                          (llm/wrap-as-prompt-fn-from-opts (mu/provider+model args defaults))]
                      (assoc selector
                             :concurrency 8 :analyze? true
                             :meta-db (d/db meta-conn)
                             :model-id model-id :invoke-llm prompt-fn))
                    (assoc selector :concurrency 8))
        result    (sync/update-repo! conn repo-path repo-path opts)]
    (mu/tool-result (format-update-changes result))))

(defn- format-ask-result
  "Pure: turn an agent/ask result into {:kind :ok|:error :text \"…\"}."
  [{:keys [status answer session-id usage]} max-iter]
  (cond
    (and (= :budget-exhausted status) answer)
    {:kind :ok
     :text (str answer
                "\n\n[Session " session-id " saved — to continue exploring, "
                "call noumenon_ask with continue_from=\"" session-id "\"]")}

    (= :budget-exhausted status)
    {:kind :error
     :text (str "Budget exhausted after " max-iter " iterations"
                " (" (:input-tokens usage 0) " in / "
                (:output-tokens usage 0) " out tokens) with no answer. "
                "Try increasing max_iterations or narrowing the question.")}

    :else
    {:kind :ok
     :text (or answer
               "The agent completed without finding an answer. Try rephrasing the question or increasing max_iterations.")}))

(defn handle-ask [args defaults]
  (util/validate-string-length! "question" (args "question") mu/max-question-len)
  (mu/validate-llm-inputs! args)
  (mu/with-conn args defaults
    (fn [{:keys [db meta-db db-dir db-name]}]
      (let [{:keys [invoke-fn]} (llm/make-messages-fn-from-opts
                                 (mu/provider+model args defaults
                                                    {:temperature 0.3 :max-tokens 4096}))
            max-iter (min (or (args "max_iterations") 10) 50)
            eidx     (embed/get-cached-index db-dir db-name)
            result   (agent/ask meta-db db (args "question")
                                {:invoke-fn      invoke-fn
                                 :repo-name      db-name
                                 :embed-index    eidx
                                 :max-iterations max-iter
                                 :continue-from  (args "continue_from")})
            {:keys [status usage]} result]
        (log! "agent/done"
              (str "status=" status
                   " iterations=" (:iterations usage)
                   " tokens=" (+ (:input-tokens usage 0) (:output-tokens usage 0))))
        (let [{:keys [kind text]} (format-ask-result result max-iter)]
          (case kind
            :ok    (mu/tool-result text)
            :error (mu/tool-error  text)))))))

;; Reanalyze scope set + retract helper live in noumenon.sync so the
;; CLI, HTTP, and MCP surfaces all share one definition.

(defn handle-analyze [args defaults]
  (mu/validate-llm-inputs! args)
  (let [reanalyze (args "reanalyze")]
    (when (and reanalyze (not (sync/valid-reanalyze-scopes reanalyze)))
      (throw (ex-info (str "Invalid reanalyze scope: " reanalyze
                           ". Must be one of: all, prompt-changed, model-changed, stale")
                      {:scope reanalyze})))
    (mu/with-conn args defaults
      (fn [{:keys [conn meta-db repo-path]}]
        (let [{:keys [prompt-fn model-id provider-kw]}
              (llm/wrap-as-prompt-fn-from-opts (mu/provider+model args defaults))
              prompt-hash (analyze/prompt-hash (:template (analyze/load-prompt-template meta-db)))]
          (sync/prepare-reanalysis! conn (d/db conn) reanalyze
                                    {:prompt-hash prompt-hash :model-id model-id})
          (let [concurrency (min (or (args "concurrency") 3) 20)
                max-files   (args "max_files")
                selector    (mu/selector-opts args)
                result      (analyze/analyze-repo! conn repo-path prompt-fn
                                                   (cond-> (assoc selector
                                                                  :meta-db meta-db
                                                                  :model-id model-id
                                                                  :provider (name provider-kw)
                                                                  :concurrency concurrency
                                                                  :no-promote? (boolean (args "no_promote"))
                                                                  :progress-fn (:progress-fn defaults))
                                                     max-files (assoc :max-files max-files)))]
            (mu/tool-result (str "Analysis complete. "
                                 (:files-analyzed result 0) " files analyzed"
                                 (when (pos? (:files-promoted result 0))
                                   (str ", " (:files-promoted result 0) " promoted (cache hit)"))
                                 (when (pos? (:files-parse-errored result 0))
                                   (str ", " (:files-parse-errored result 0) " parse errors"))
                                 (when (pos? (:files-errored result 0))
                                   (str ", " (:files-errored result 0) " errors"))
                                 ". " (get-in result [:total-usage :input-tokens] 0)
                                 " in / " (get-in result [:total-usage :output-tokens] 0) " out tokens"
                                 (when-let [c (get-in result [:total-usage :cost-usd])]
                                   (when (pos? c) (str " ($" (format "%.2f" c) ")")))))))))))

(defn handle-enrich [args defaults]
  (mu/with-conn args defaults
    (fn [{:keys [conn repo-path]}]
      (let [concurrency (min (or (args "concurrency") 8) 20)
            selector    (mu/selector-opts args)
            result      (imports/enrich-repo! conn repo-path
                                              (assoc selector :concurrency concurrency))]
        (mu/tool-result (str "Enrich complete. "
                             (:files-processed result 0) " files processed, "
                             (:imports-resolved result 0) " imports resolved."))))))

(defn handle-synthesize [args defaults]
  (mu/validate-llm-inputs! args)
  (mu/with-conn args defaults
    (fn [{:keys [conn meta-conn db-name]}]
      (artifacts/reseed! meta-conn)
      (let [meta-db   (d/db meta-conn)
            {:keys [prompt-fn model-id provider-kw]}
            (llm/wrap-as-prompt-fn-from-opts (mu/provider+model args defaults))
            result (synthesize/synthesize-repo!
                    conn prompt-fn
                    {:meta-db meta-db :provider (name provider-kw)
                     :model-id model-id :repo-name db-name})]
        (mu/tool-result (str "Synthesis complete. "
                             (:components result 0) " components identified, "
                             (:files-classified result 0) " files classified"
                             (when-let [m (:mode result)]
                               (str " (mode: " (name m)
                                    (when-let [p (:partitions result)] (str ", " p " partitions"))
                                    ")"))
                             (when-let [u (:usage result)]
                               (str " (" (:input-tokens u 0) " in / "
                                    (:output-tokens u 0) " out tokens)"))
                             (when-let [e (:error result)]
                               (str "\nError: " e))
                             "."))))))

(defn- format-digest-summary
  "Format digest pipeline results as a human-readable summary string."
  [r]
  (str "Digest complete."
       (when-let [u (:update r)]
         (str "\nUpdate: " (or (:added u) 0) " added, "
              (or (:modified u) 0) " modified."))
       (when-let [a (:analyze r)]
         (str "\nAnalyze: " (:files-analyzed a 0) " files analyzed"
              (when-let [c (get-in a [:total-usage :cost-usd])]
                (when (pos? c) (str " ($" (format "%.2f" c) ")")))))
       (when-let [b (:benchmark r)]
         (str "\nBenchmark: run-id=" (:run-id b)
              (when-let [fm (get-in b [:aggregate :full-mean])]
                (str ", full=" (format "%.1f%%" (* 100.0 (double fm)))))))))

(defn- repo->uri
  "Translate a repo-path argument to a stable :repo/uri (canonical FS path or db:// URI)."
  [repo-path]
  (if (str/starts-with? (str repo-path) "db://")
    repo-path
    (.getCanonicalPath (java.io.File. (str repo-path)))))

(defn- digest-update-step [conn repo-path repo-uri selector args]
  (when-not (or (args "skip_import") (args "skip_enrich"))
    (sync/update-repo! conn repo-path repo-uri (assoc selector :concurrency 8))))

(defn- digest-analyze-step [conn repo-path prompt-fn selector
                            {:keys [meta-db model-id provider-kw progress-fn]} args]
  (when-not (args "skip_analyze")
    (analyze/analyze-repo! conn repo-path prompt-fn
                           (assoc selector
                                  :meta-db meta-db :model-id model-id
                                  :provider (name provider-kw)
                                  :concurrency 3
                                  :progress-fn progress-fn))))

(defn- digest-synthesize-step [conn meta-db db-name args defaults]
  (when-not (args "skip_synthesize")
    (try
      (let [{:keys [prompt-fn model-id provider-kw]}
            (llm/wrap-as-prompt-fn-from-opts (mu/provider+model args defaults {:max-tokens 16384}))]
        (synthesize/synthesize-repo!
         conn prompt-fn
         {:meta-db meta-db :provider (name provider-kw)
          :model-id model-id :repo-name db-name}))
      (catch Exception e
        (log! "digest/synthesize" (str "skipped: " (.getMessage e)))
        nil))))

(defn- digest-embed-step [db db-dir db-name]
  (try
    (let [idx (embed/build-index! db db-dir db-name)]
      {:entries (count (:entries idx))})
    (catch Exception e
      (log! "digest/embed" (str "skipped: " (.getMessage e)))
      nil)))

(defn- digest-benchmark-step
  [conn db repo-path prompt-fn meta-db db-dir db-name args defaults progress-fn]
  (when-not (args "skip_benchmark")
    (let [layers    (mu/validate-layers (args "layers"))
          mode      (cond-> {} layers (assoc :layers layers))
          model-cfg (mu/provider+model args defaults)
          r         (bench/run-benchmark! db repo-path prompt-fn
                                          :meta-db meta-db :conn conn :mode mode
                                          :model-config model-cfg
                                          :budget {:max-questions (args "max_questions")}
                                          :report? (args "report")
                                          :concurrency 3
                                          :progress-fn progress-fn
                                          :db-dir db-dir :db-name db-name)]
      (select-keys r [:run-id :aggregate :stop-reason :report-path]))))

(defn handle-digest [args defaults]
  (mu/validate-llm-inputs! args)
  (mu/with-conn args defaults
    (fn [{:keys [conn meta-conn db-dir db-name repo-path]}]
      (artifacts/reseed! meta-conn)
      (let [meta-db     (d/db meta-conn)
            llm-opts    (llm/wrap-as-prompt-fn-from-opts (mu/provider+model args defaults))
            prompt-fn   (:prompt-fn   llm-opts)
            repo-uri    (repo->uri repo-path)
            selector    (mu/selector-opts args)
            progress-fn (:progress-fn defaults)
            update-r    (digest-update-step conn repo-path repo-uri selector args)
            analyze-r   (digest-analyze-step conn repo-path prompt-fn selector
                                             (assoc llm-opts :meta-db meta-db
                                                    :progress-fn progress-fn) args)
            synth-r     (digest-synthesize-step conn meta-db db-name args defaults)
            embed-r     (digest-embed-step (d/db conn) db-dir db-name)
            bench-r     (digest-benchmark-step conn (d/db conn) repo-path prompt-fn
                                               meta-db db-dir db-name args defaults progress-fn)]
        (mu/tool-result
         (format-digest-summary
          (cond-> {}
            update-r  (assoc :update    update-r)
            analyze-r (assoc :analyze   analyze-r)
            synth-r   (assoc :synthesize synth-r)
            embed-r   (assoc :embed     embed-r)
            bench-r   (assoc :benchmark bench-r))))))))

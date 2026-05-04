(ns noumenon.cli.commands.introspect
  "CLI command handler for the introspect self-improvement loop."
  (:require [clojure.string :as str]
            [noumenon.cli.util :as cu]
            [noumenon.git :as git]
            [noumenon.introspect :as introspect]
            [noumenon.llm :as llm]
            [noumenon.repo :as repo]
            [noumenon.util :as util :refer [log!]]))

(defn do-introspect
  "Run the introspect self-improvement loop."
  [{:keys [model provider max-iterations max-hours max-cost git-commit target
           eval-runs extra-repos] :as opts}]
  (try
    (util/validate-introspect-targets! target)
    (cu/with-valid-repo
      opts
      (fn [ctx]
        (try
          (cu/with-existing-db
            ctx
            (fn [{:keys [db meta-conn db-name db-dir]}]
              (let [{:keys [invoke-fn]}
                    (llm/make-messages-fn-from-opts {:provider    provider
                                                     :model       model
                                                     :temperature 0.7
                                                     :max-tokens  8192})
                    invoke-fn-factory
                    (fn []
                      (:invoke-fn
                       (llm/make-messages-fn-from-opts {:provider    provider
                                                        :model       model
                                                        :temperature 0.0
                                                        :max-tokens  4096})))
                    extra (repo/resolve-extra-repos extra-repos db-dir)
                    bare-repo? (and git-commit (git/bare-repo? (:repo-path opts)))
                    _ (when bare-repo?
                        (log! "[--git-commit ignored] target is a bare git repo (no working tree); commits cannot be made."))
                    _ (log! (str "\n[COST WARNING] introspect runs up to "
                                 (or max-iterations 10)
                                 " benchmark evaluations. Use --max-cost to set a budget."))
                    result (introspect/run-loop!
                            (cond-> {:db                  db
                                     :repo-name           db-name
                                     :repo-path           (:repo-path opts)
                                     :meta-conn           meta-conn
                                     :invoke-fn-factory   invoke-fn-factory
                                     :optimizer-invoke-fn invoke-fn
                                     :max-iterations      (or max-iterations 10)
                                     :max-hours           max-hours
                                     :max-cost            max-cost
                                     :git-commit?         (and git-commit (not bare-repo?))
                                     :model-config        {:provider provider :model model}
                                     :eval-runs           (or eval-runs 1)
                                     :allowed-targets     (when target
                                                            (set (map keyword
                                                                      (str/split target #","))))}
                              (seq extra)
                              (assoc :extra-repos extra)))]
                (log! (str "\nIntrospect complete: " (:improvements result)
                           " improvements in " (:iterations result)
                           " iterations (final score: "
                           (format "%.1f%%" (* 100.0 (double (:final-score result))))
                           ", run-id: " (:run-id result) ")"))
                {:exit 0 :result result})))
          (catch clojure.lang.ExceptionInfo e
            (cu/print-error! (.getMessage e))
            {:exit 1}))))
    (catch clojure.lang.ExceptionInfo e
      (cu/print-error! (.getMessage e))
      {:exit 1})))

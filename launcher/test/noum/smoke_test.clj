#!/usr/bin/env bb

(ns noum.smoke-test
  "E2E smoke test for the self-contained noum binary.
   Run: bb test/noum/smoke_test.clj [/path/to/noum]

   Tests all user-facing commands against the binary, the JVM daemon,
   and optionally a Docker container. Skips Docker tests gracefully
   when Docker or the image is unavailable."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- Config ---

(def ^:private noum-bin
  (or (first *command-line-args*)
      (str (fs/path (fs/cwd) ".." "target" "noum"))
      "/tmp/noum"))

(def ^:private test-repo
  (str (fs/path (fs/cwd) "..")))

(def ^:private docker-image "noumenon-test")
(def ^:private docker-container "noum-smoke-test")
(def ^:private docker-token "smoke-test-token")
(def ^:private docker-port 7899)

;; --- Pure helpers ---

(defn- shell-result
  "Run a shell command. Returns {:exit N :out string :err string}."
  [opts & args]
  (let [r (apply proc/shell (merge {:out :string :err :string :continue true} opts) args)]
    (select-keys r [:exit :out :err])))

(defn- noum
  "Run the noum binary with args. Returns {:exit N :out string :err string}."
  [& args]
  (shell-result {:cmd (into [noum-bin] args)}))

(defn- combined-output [{:keys [out err]}]
  (str out err))

(defn- json-get
  "HTTP GET, parse JSON response. Returns parsed body or nil."
  [url headers]
  (try
    (let [resp (http/get url {:headers headers :timeout 5000 :throw false})]
      {:status (:status resp)
       :body   (try (json/parse-string (:body resp) true) (catch Exception _ nil))})
    (catch Exception _ nil)))

(defn- json-post
  "HTTP POST JSON, parse response. Returns {:status N :body parsed}."
  [url headers body-map & {:keys [timeout] :or {timeout 30000}}]
  (try
    (let [resp (http/post url {:headers (merge {"Content-Type" "application/json"} headers)
                               :body    (json/generate-string body-map)
                               :timeout timeout
                               :throw   false})]
      {:status (:status resp)
       :body   (try (json/parse-string (:body resp) true) (catch Exception _ nil))})
    (catch Exception _ nil)))

;; --- Assertions (pure — return result maps) ---

(defn- pass [test-name]
  {:test test-name :status :pass})

(defn- fail [test-name detail]
  {:test test-name :status :fail :detail detail})

(defn- assert-exit [test-name {:keys [exit]} expected]
  (if (= exit expected)
    (pass test-name)
    (fail test-name (str "expected exit " expected ", got " exit))))

(defn- assert-contains [test-name result substring]
  (if (str/includes? (combined-output result) substring)
    (pass test-name)
    (fail test-name (str "output missing: " (pr-str substring)))))

(defn- assert-true [test-name condition & [detail]]
  (if condition
    (pass test-name)
    (fail test-name detail)))

;; --- Test definitions (return seqs of result maps) ---

(defn tests-version []
  (let [r (noum "version")]
    [(assert-exit "version exits 0" r 0)
     (assert-contains "version shows noum" r "noum")]))

(defn tests-help []
  (let [r (noum "help")]
    (concat
     [(assert-exit "help exits 0" r 0)
      (assert-contains "help shows Pipeline" r "Pipeline")
      (assert-contains "help shows Query" r "Query")
      (assert-contains "help shows Daemon" r "Daemon")
      (assert-contains "help shows UI" r "UI")]
     (mapcat (fn [cmd]
               (let [r (noum "help" cmd)]
                 [(assert-exit (str "help " cmd " exits 0") r 0)
                  (assert-contains (str "help " cmd " shows usage") r "Usage:")]))
             ["import" "ask" "bench" "setup" "serve"
              "start" "stop" "ping" "upgrade" "databases" "introspect" "synthesize" "open"]))))

(defn tests-arg-validation []
  (concat
   (mapcat (fn [cmd]
             (let [r (noum cmd)]
               [(assert-exit (str cmd " no args exits 1") r 1)
                (assert-contains (str cmd " no args shows usage") r "Usage:")]))
           ["import" "analyze" "enrich" "synthesize" "update" "digest" "bench" "introspect"])
   (let [r (noum "ask" "/tmp")]
     [(assert-exit "ask missing question exits 1" r 1)
      (assert-contains "ask missing question shows usage" r "Usage:")])
   (let [r (noum "query" "hotspots")]
     [(assert-exit "query missing repo exits 1" r 1)
      (assert-contains "query missing repo shows usage" r "Usage:")])
   [(assert-exit "setup no target exits 1" (noum "setup") 1)
    (assert-exit "watch no args exits 1" (noum "watch") 1)
    (assert-exit "delete no args exits 1" (noum "delete") 1)
    (assert-exit "history no type exits 1" (noum "history") 1)]
   (let [r (noum "foobar")]
     [(assert-exit "unknown command exits 1" r 1)
      (assert-contains "unknown command shows help" r "Unknown command")])
   (let [r (noum)]
     [(assert-exit "no args exits 1" r 1)
      (assert-contains "no args shows help" r "Usage:")])))

(defn tests-ping-no-daemon []
  (let [r (noum "ping")]
    [(assert-exit "ping no daemon exits 1" r 1)
     (assert-contains "ping no daemon message" r "not running")]))

(defn tests-stop-no-daemon []
  [(assert-exit "stop no daemon exits 0" (noum "stop") 0)])

(defn tests-setup-code []
  (let [tmp (str (fs/create-temp-dir {:prefix "noum-smoke-"}))
        r   (shell-result {:cmd [noum-bin "setup" "code"] :dir tmp})
        mcp (str (fs/path tmp ".mcp.json"))
        results (concat
                 [(assert-exit "setup code exits 0" r 0)
                  (assert-true "setup code creates .mcp.json" (fs/exists? mcp))]
                 (when (fs/exists? mcp)
                   (let [config (json/parse-string (slurp mcp) true)]
                     [(assert-true "mcp.json has noumenon server"
                                   (some? (get-in config [:mcpServers :noumenon])))])))]
    (fs/delete-tree tmp)
    results))

(defn tests-daemon-lifecycle []
  (noum "stop")
  (Thread/sleep 1000)
  (let [start-r   (noum "start")
        _         (Thread/sleep 2000)
        ping-r    (noum "ping")
        db-r      (noum "databases")
        queries-r (noum "queries")
        import-r  (noum "import" test-repo)
        status-r  (noum "status" "noumenon")
        stop-r    (noum "stop")
        _         (Thread/sleep 1000)
        ping2-r   (noum "ping")
        daemon-f  (str (fs/path (fs/home) ".noumenon" "daemon.edn"))]
    [(assert-exit "start exits 0" start-r 0)
     (assert-exit "ping running exits 0" ping-r 0)
     (assert-contains "ping shows port" ping-r "Daemon running")
     (assert-exit "databases exits 0" db-r 0)
     (assert-exit "queries exits 0" queries-r 0)
     (assert-exit "import exits 0" import-r 0)
     (assert-contains "import shows commits" import-r "commits-imported")
     (assert-exit "status exits 0" status-r 0)
     (assert-contains "status shows commits" status-r "commits")
     (assert-exit "stop exits 0" stop-r 0)
     (assert-exit "ping after stop exits 1" ping2-r 1)
     (assert-true "daemon.edn removed after stop" (not (fs/exists? daemon-f)))]))

(defn tests-mcp-serve []
  (let [init-msg (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
        r (shell-result {:cmd [noum-bin "serve"] :in init-msg :timeout 15000})]
    [(assert-true "serve responds to initialize" (str/includes? (:out r) "protocolVersion"))
     (assert-true "serve returns server name" (str/includes? (:out r) "noumenon"))]))

(defn tests-build-binary []
  (let [launcher-dir (str (fs/path test-repo "launcher"))
        bb-real      (str/trim (:out (proc/shell {:out :string} "readlink" "-f"
                                                 (str/trim (:out (proc/shell {:out :string} "which" "bb"))))))
        tmp          (str (fs/create-temp-dir {:prefix "noum-build-"}))
        jar-path     (str (fs/path tmp "noum.jar"))
        bin-path     (str (fs/path tmp "noum"))
        build-r      (shell-result {:dir launcher-dir}
                                   "bb" "uberjar" jar-path "-m" "noum.main" "--classpath" "src:resources")
        _            (when (zero? (:exit build-r))
                       (with-open [out (io/output-stream bin-path)]
                         (io/copy (io/file bb-real) out)
                         (io/copy (io/file jar-path) out))
                       (fs/set-posix-file-permissions bin-path "rwxr-xr-x"))
        results (concat
                 [(assert-exit "uberjar build exits 0" build-r 0)
                  (assert-true "uberjar exists" (fs/exists? jar-path))]
                 (when (fs/exists? bin-path)
                   (let [ver-r  (shell-result {:cmd [bin-path "version"]})
                         help-r (shell-result {:cmd [bin-path "help"]})]
                     [(assert-true "binary exists" (fs/exists? bin-path))
                      (assert-true "binary is executable" (fs/executable? bin-path))
                      (assert-exit "fresh binary version exits 0" ver-r 0)
                      (assert-contains "fresh binary shows noum" ver-r "noum")
                      (assert-exit "fresh binary help exits 0" help-r 0)
                      (assert-contains "fresh binary shows Pipeline" help-r "Pipeline")])))]
    (fs/delete-tree tmp)
    results))

;; --- Docker tests ---

(defn- docker-available? []
  (try (zero? (:exit (shell-result {} "docker" "info")))
       (catch Exception _ false)))

(defn- docker-image-exists? []
  (try (zero? (:exit (shell-result {} "docker" "image" "inspect" docker-image)))
       (catch Exception _ false)))

(defn- docker-cleanup! []
  (shell-result {} "docker" "stop" docker-container)
  (shell-result {} "docker" "rm" docker-container))

(defn- with-docker
  "Run test-fn only if Docker and image are available. Returns results or skip message."
  [group-name test-fn]
  (cond
    (not (docker-available?))   (do (println (str "  (skipped — Docker not available)")) [])
    (not (docker-image-exists?)) (do (println (str "  (skipped — " docker-image " not found)")) [])
    :else                        (test-fn)))

(defn tests-docker-cli []
  (with-docker "Docker CLI"
    (fn []
      (let [ver-r  (shell-result {} "docker" "run" "--rm" docker-image "--version")
            help-r (shell-result {} "docker" "run" "--rm" docker-image "--help")]
        [(assert-exit "docker --version exits 0" ver-r 0)
         (assert-true "docker --version shows version" (re-find #"\d+\.\d+\.\d+" (:out ver-r)))
         (assert-exit "docker --help exits 0" help-r 0)
         (assert-contains "docker --help shows subcommands" help-r "Subcommands")]))))

(defn tests-docker-no-token []
  (with-docker "Docker Auth Enforcement"
    (fn []
      (let [r (shell-result {} "docker" "run" "--rm" docker-image
                            "daemon" "--bind" "0.0.0.0" "--port" "7898")]
        [(assert-true "docker refuses 0.0.0.0 without token" (not= 0 (:exit r)))
         (assert-contains "docker error mentions token" r "NOUMENON_TOKEN")]))))

(defn tests-docker-mcp []
  (with-docker "Docker MCP"
    (fn []
      (let [init-msg (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
            r (shell-result {:cmd ["docker" "run" "--rm" "-i" docker-image "serve"]
                             :in init-msg :timeout 20000})]
        [(assert-true "docker MCP responds to initialize" (str/includes? (:out r) "protocolVersion"))
         (assert-true "docker MCP returns server name" (str/includes? (:out r) "noumenon"))]))))

(defn- docker-api-tests
  "Run HTTP API tests against a running Docker container. Pure — returns result seq."
  [port token]
  (let [base (str "http://localhost:" port)
        auth {"Authorization" (str "Bearer " token)}
        ;; Import
        import-r (json-post (str base "/api/import") auth {:repo_path "/repo"} :timeout 120000)
        ;; GET endpoints
        db-r      (json-get (str base "/api/databases") auth)
        status-r  (json-get (str base "/api/status/repo") auth)
        schema-r  (json-get (str base "/api/schema/repo") auth)
        queries-r (json-get (str base "/api/queries") auth)
        ;; POST endpoints
        query-r   (json-post (str base "/api/query") auth {:repo_path "/repo" :query_name "top-contributors"})
        ask-r     (json-post (str base "/api/ask") auth {:repo_path "/repo"})
        raw-q-r   (json-post (str base "/api/query-raw") auth
                             {:repo_path "/repo" :query "[:find (count ?e) :where [?e :file/path _]]"})
        reseed-r  (json-post (str base "/api/reseed") auth {})
        ;; Error cases
        notfound  (json-get (str base "/nonexistent") auth)
        bad-method (try (http/post (str base "/api/databases") {:headers auth :throw false})
                        (catch Exception _ nil))
        bad-auth  (try (http/get (str base "/health")
                                 {:headers {"Authorization" "Bearer wrong"} :throw false})
                       (catch Exception _ nil))
        ;; Non-root
        whoami    (shell-result {} "docker" "exec" docker-container "whoami")]
    [(assert-true "POST /api/import ok" (get-in import-r [:body :ok]))
     (assert-true "POST /api/import commits" (pos? (get-in import-r [:body :data :commits-imported] 0)))
     (assert-true "GET /api/databases ok" (get-in db-r [:body :ok]))
     (assert-true "GET /api/databases has repo" (some #(= "repo" (:name %)) (get-in db-r [:body :data])))
     (assert-true "GET /api/status/:repo ok" (get-in status-r [:body :ok]))
     (assert-true "GET /api/status/:repo has commits" (pos? (get-in status-r [:body :data :commits] 0)))
     (assert-true "GET /api/schema/:repo ok" (get-in schema-r [:body :ok]))
     (assert-true "GET /api/schema/:repo has content" (pos? (count (get-in schema-r [:body :data :schema] ""))))
     (assert-true "GET /api/queries ok" (get-in queries-r [:body :ok]))
     (assert-true "GET /api/queries count" (pos? (count (get-in queries-r [:body :data]))))
     (assert-true "POST /api/query ok" (get-in query-r [:body :ok]))
     (assert-true "POST /api/query has results" (some? (get-in query-r [:body :data :total])))
     (assert-true "POST /api/ask rejects missing question" (not (get-in ask-r [:body :ok])))
     (assert-true "POST /api/query-raw ok" (get-in raw-q-r [:body :ok]))
     (assert-true "POST /api/query-raw has results" (some? (get-in raw-q-r [:body :data :total])))
     (assert-true "POST /api/reseed ok" (get-in reseed-r [:body :ok]))
     (assert-true "GET /nonexistent returns 404" (= 404 (:status notfound)))
     (assert-true "POST to GET route returns 404" (and bad-method (= 404 (:status bad-method))))
     (assert-true "wrong token returns 401" (and bad-auth (= 401 (:status bad-auth))))
     (assert-true "docker runs as non-root" (= "noumenon" (str/trim (:out whoami))))]))

(defn tests-docker-http-endpoints []
  (with-docker "Docker HTTP Endpoints"
    (fn []
      (docker-cleanup!)
      (let [port  7898
            token "http-test-token"
            tmp   (str (fs/create-temp-dir {:prefix "noum-docker-http-"}))]
        (try
          (shell-result {} "docker" "run" "-d" "--name" docker-container
                        "-p" (str port ":" port)
                        "-e" (str "NOUMENON_TOKEN=" token)
                        "-v" (str test-repo ":/repo")
                        "-v" (str tmp ":/data")
                        docker-image
                        "daemon" "--port" (str port) "--bind" "0.0.0.0" "--db-dir" "/data")
          (Thread/sleep 8000)
          (docker-api-tests port token)
          (finally
            (docker-cleanup!)
            (fs/delete-tree tmp)))))))

(defn tests-docker-backend []
  (with-docker "Docker Headless Backend"
    (fn []
      (docker-cleanup!)
      (let [tmp-data (str (fs/create-temp-dir {:prefix "noum-docker-smoke-"}))]
        (try
          (let [start-r (shell-result {} "docker" "run" "-d" "--name" docker-container
                                      "-p" (str docker-port ":" docker-port)
                                      "-e" (str "NOUMENON_TOKEN=" docker-token)
                                      "-v" (str test-repo ":/repo")
                                      "-v" (str tmp-data ":/data")
                                      docker-image
                                      "daemon" "--port" (str docker-port)
                                      "--bind" "0.0.0.0" "--db-dir" "/data")
                _       (Thread/sleep 8000)
                auth    {"Authorization" (str "Bearer " docker-token)}
                base    (str "http://localhost:" docker-port)
                unauth  (try (http/get (str base "/health") {:timeout 5000 :throw false})
                             (catch Exception _ nil))
                health  (json-get (str base "/health") auth)
                import-r (json-post (str base "/api/import") auth {:repo_path "/repo"} :timeout 120000)
                db-r    (json-get (str base "/api/databases") auth)
                q-r     (json-get (str base "/api/queries") auth)]
            [(assert-exit "docker start exits 0" start-r 0)
             (assert-true "docker rejects unauthenticated" (or (nil? unauth) (not= 200 (:status unauth))))
             (assert-true "docker health responds" (some? health))
             (assert-true "docker health ok" (= "ok" (get-in health [:body :data :status])))
             (assert-true "docker import ok" (get-in import-r [:body :ok]))
             (assert-true "docker import has commits" (pos? (get-in import-r [:body :data :commits-imported] 0)))
             (assert-true "docker databases ok" (get-in db-r [:body :ok]))
             (assert-true "docker databases non-empty" (seq (get-in db-r [:body :data])))
             (assert-true "docker queries ok" (get-in q-r [:body :ok]))
             (assert-true "docker queries has entries" (pos? (count (get-in q-r [:body :data]))))])
          (finally
            (docker-cleanup!)
            (fs/delete-tree tmp-data)))))))

(defn tests-docker-host-flag []
  (with-docker "Docker --host flag"
    (fn []
      (docker-cleanup!)
      (let [port  7897
            token "host-test-token"
            tmp   (str (fs/create-temp-dir {:prefix "noum-docker-host-"}))]
        (try
          (shell-result {} "docker" "run" "-d" "--name" docker-container
                        "-p" (str port ":" port)
                        "-e" (str "NOUMENON_TOKEN=" token)
                        "-v" (str test-repo ":/repo")
                        "-v" (str tmp ":/data")
                        docker-image
                        "daemon" "--port" (str port) "--bind" "0.0.0.0" "--db-dir" "/data")
          (Thread/sleep 8000)
          ;; Test noum --host flag against the Docker daemon
          (let [host-flag (str "localhost:" port)
                db-r (shell-result {:cmd [noum-bin "databases" "--host" host-flag "--token" token]})]
            [(assert-exit "noum --host databases exits 0" db-r 0)])
          (finally
            (docker-cleanup!)
            (fs/delete-tree tmp)))))))

(defn tests-docker-sse []
  (with-docker "Docker SSE streaming"
    (fn []
      (docker-cleanup!)
      (let [port  7896
            token "sse-test-token"
            tmp   (str (fs/create-temp-dir {:prefix "noum-docker-sse-"}))]
        (try
          (shell-result {} "docker" "run" "-d" "--name" docker-container
                        "-p" (str port ":" port)
                        "-e" (str "NOUMENON_TOKEN=" token)
                        "-v" (str test-repo ":/repo")
                        "-v" (str tmp ":/data")
                        docker-image
                        "daemon" "--port" (str port) "--bind" "0.0.0.0" "--db-dir" "/data")
          (Thread/sleep 8000)
          ;; Request SSE import and check for event stream
          (let [resp (http/post (str "http://localhost:" port "/api/import")
                                {:headers {"Content-Type" "application/json"
                                           "Authorization" (str "Bearer " token)
                                           "Accept" "text/event-stream"}
                                 :body (json/generate-string {:repo_path "/repo"})
                                 :timeout 120000
                                 :as :string})
                body (:body resp)]
            [(assert-true "SSE returns event stream"
                          (str/includes? body "event:"))
             (assert-true "SSE has progress events"
                          (str/includes? body "event: progress"))
             (assert-true "SSE has result event"
                          (str/includes? body "event: result"))
             (assert-true "SSE has done event"
                          (str/includes? body "event: done"))])
          (finally
            (docker-cleanup!)
            (fs/delete-tree tmp)))))))

;; --- Runner (pure results, side effects only here) ---

(def ^:private test-groups
  [["Version"                tests-version]
   ["Help"                   tests-help]
   ["Arg Validation"         tests-arg-validation]
   ["Ping (no daemon)"       tests-ping-no-daemon]
   ["Stop (no daemon)"       tests-stop-no-daemon]
   ["Setup Code"             tests-setup-code]
   ["Daemon Lifecycle"       tests-daemon-lifecycle]
   ["MCP Serve"              tests-mcp-serve]
   ["Build Binary"           tests-build-binary]
   ["Docker CLI"             tests-docker-cli]
   ["Docker Auth"            tests-docker-no-token]
   ["Docker MCP"             tests-docker-mcp]
   ["Docker HTTP Endpoints"  tests-docker-http-endpoints]
   ["Docker Headless"        tests-docker-backend]
   ["Docker --host flag"     tests-docker-host-flag]
   ["Docker SSE"             tests-docker-sse]])

(defn- run-group [[group-name test-fn]]
  (println (str "\n== " group-name " =="))
  (let [results (test-fn)]
    (doseq [{:keys [test status detail]} results]
      (if (= :pass status)
        (println (str "  ✓ " test))
        (println (str "  ✗ " test (when detail (str " — " detail))))))
    results))

(defn- print-summary [all-results]
  (let [passes (count (filter #(= :pass (:status %)) all-results))
        fails  (filter #(= :fail (:status %)) all-results)]
    (println (str "\n== Summary =="))
    (println (str "  " passes " passed, " (count fails) " failed"))
    (when (seq fails)
      (println "\nFailed tests:")
      (doseq [{:keys [test detail]} fails]
        (println (str "  ✗ " test (when detail (str " — " detail))))))
    (zero? (count fails))))

(defn -main []
  (println (str "Smoke testing: " noum-bin))
  (println (str "Test repo: " test-repo))
  (when-not (fs/exists? noum-bin)
    (println (str "ERROR: Binary not found: " noum-bin))
    (System/exit 2))
  (let [all-results (mapcat run-group test-groups)]
    (System/exit (if (print-summary all-results) 0 1))))

(-main)

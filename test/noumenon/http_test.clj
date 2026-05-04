(ns noumenon.http-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [noumenon.auth :as auth]
            [noumenon.http :as http]
            [noumenon.http.handlers.query]
            [noumenon.repo-manager :as repo-mgr]
            [noumenon.util :as util]))

(deftest health-endpoint
  (let [handler (http/make-handler {:db-dir    "data/datomic/"
                                    :started-at (System/currentTimeMillis)})
        resp    (handler {:request-method :get :uri "/health"})]
    (is (= 200 (:status resp)))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (:ok body))
      (is (= "ok" (get-in body [:data :status]))))))

(deftest not-found
  (let [handler (http/make-handler {:db-dir "data/datomic/"})
        resp    (handler {:request-method :get :uri "/nonexistent"})]
    (is (= 404 (:status resp)))))

(deftest auth-required-when-token-set
  (let [db-dir  (str (System/getProperty "user.dir") "/data/datomic/")
        handler (http/make-handler {:db-dir db-dir :token "secret"})
        ;; /health is always public (load balancer probes)
        health-no-auth (handler {:request-method :get :uri "/health"})
        ;; API endpoints require auth
        no-auth (handler {:request-method :get :uri "/api/databases"})
        bad-auth (handler {:request-method :get
                           :uri "/api/databases"
                           :headers {"authorization" "Bearer wrong"}})
        good-auth (handler {:request-method :get
                            :uri "/api/databases"
                            :headers {"authorization" "Bearer secret"}})]
    (is (= 200 (:status health-no-auth)))
    (is (= 401 (:status no-auth)))
    (is (= 401 (:status bad-auth)))
    (is (= 200 (:status good-auth)))))

(deftest auth-token-lookup-failure-returns-401-not-500
  (let [db-dir   (str (System/getProperty "user.dir") "/data/datomic/")
        handler  (http/make-handler {:db-dir db-dir :token "secret"})
        resp     (with-redefs [auth/validate-token
                               (fn [_ _] (throw (java.nio.channels.ClosedChannelException.)))]
                   (handler {:request-method :get
                             :uri "/api/databases"
                             :headers {"authorization" "Bearer some-bad-token"}}))]
    (is (= 401 (:status resp)))
    (is (re-find #"^application/json" (or (get-in resp [:headers "Content-Type"]) "")))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (false? (:ok body)))
      (is (re-find #"(?i)unauthorized" (:error body))))))

(deftest routing-method-mismatch
  (let [handler (http/make-handler {:db-dir "data/datomic/"})
        resp    (handler {:request-method :post :uri "/health"})]
    (is (= 404 (:status resp)) "GET-only endpoint should not match POST")))

(deftest path-traversal-in-db-name-rejected
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        traverse (handler {:request-method :delete :uri "/api/databases/.."})
        dots     (handler {:request-method :delete :uri "/api/databases/..."})
        blank    (handler {:request-method :get :uri "/api/status/.."})]
    (is (= 400 (:status traverse)) ".. should be rejected")
    (is (= 400 (:status dots)) "... should be rejected")
    (is (= 400 (:status blank)) ".. in status should be rejected")))

(deftest control-chars-in-db-name-rejected
  (testing "validate-db-name! used to allow null bytes / newlines /
            tabs / spaces because the validator only rejected `/`,
            `\\`, blank, and pure-dot. The actual filesystem effect
            today is just a 404, but the validator's contract is
            'reject names that could escape or confuse the storage
            layer', and JVM NIO has historically had path-truncation
            bugs around null bytes (CVE-2014-3578 family). Tighten to
            the [a-zA-Z0-9._-]+ allowlist that derive-db-name uses,
            so legitimate names still pass and exotic ones fail at
            the boundary."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          probe   (fn [encoded]
                    (let [resp (handler {:request-method :get
                                         :uri (str "/api/status/" encoded)})
                          body (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error body)}))]
      (testing "null byte rejected"
        (let [{:keys [status error]} (probe "foo%00bar")]
          (is (= 400 status))
          (is (re-find #"(?i)invalid database name" (str error)) error)))
      (testing "newline rejected"
        (let [{:keys [status error]} (probe "foo%0Abar")]
          (is (= 400 status))
          (is (re-find #"(?i)invalid database name" (str error)) error)))
      (testing "tab rejected"
        (let [{:keys [status error]} (probe "foo%09bar")]
          (is (= 400 status))
          (is (re-find #"(?i)invalid database name" (str error)) error)))
      (testing "space rejected"
        (let [{:keys [status error]} (probe "foo%20bar")]
          (is (= 400 status))
          (is (re-find #"(?i)invalid database name" (str error)) error)))
      (testing "emoji / non-ASCII rejected"
        (let [{:keys [status error]} (probe "%F0%9F%92%80")]
          (is (= 400 status))
          (is (re-find #"(?i)invalid database name" (str error)) error))))))

(deftest delete-meta-db-rejected
  (testing "DELETE /api/databases/noumenon-internal must not let callers
            wipe the meta DB — it stores tokens, settings, prompts, rules,
            ask sessions, and benchmark history, and the daemon's cached
            :meta-conn would silently break for the rest of the process
            lifetime."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          resp    (handler {:request-method :delete :uri "/api/databases/noumenon-internal"})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (re-find #"reserved" (str (:error body)))
          (str "expected 'reserved' wording in error, got: " (:error body))))))

(deftest url-encoded-path-params-decoded
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        ;; URL-encoded db name "my%2Erepo" decodes to "my.repo" — passes
        ;; the [a-zA-Z0-9._-]+ allowlist (so it isn't rejected at the
        ;; validate-db-name! gate) but doesn't exist on disk, so the
        ;; 404 message includes the decoded form. Earlier this test
        ;; used "my%20repo" → "my repo" with a space, but spaces no
        ;; longer pass validation so we'd never reach the 404 branch.
        resp    (handler {:request-method :get :uri "/api/status/my%2Erepo"})
        body    (json/read-str (:body resp) :key-fn keyword)]
    (is (= 404 (:status resp)))
    (is (re-find #"my\.repo" (:error body)) "db-name should be URL-decoded")))

(deftest databases-endpoint-empty
  (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        resp    (handler {:request-method :get :uri "/api/databases"})]
    (is (= 200 (:status resp)))
    (let [body (json/read-str (:body resp) :key-fn keyword)]
      (is (:ok body))
      (is (= [] (:data body))))))

(defn- post-with-body
  "Build a Ring-shaped POST request with a JSON body."
  [uri body-map]
  {:request-method :post
   :uri            uri
   :headers        {}
   :body           (java.io.ByteArrayInputStream.
                    (.getBytes ^String (json/write-str body-map) "UTF-8"))})

(deftest query_name-length-cap-uniform-across-endpoints
  (let [handler   (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
        long-name (apply str (repeat 257 \a))
        send      (fn [uri extra]
                    (let [resp (handler (post-with-body uri (merge {:repo_path "any"
                                                                    :query_name long-name}
                                                                   extra)))
                          body (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error body)}))]
    (testing "POST /api/query rejects 257-char query_name with the shared
              max-length message — earlier the unknown-query lookup echoed
              the 300-char name back unchecked"
      (let [{:keys [status error]} (send "/api/query" {})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))
    (testing "POST /api/query-as-of also rejects oversized query_name
              before the as_of validation, so the failure mode is uniform"
      (let [{:keys [status error]} (send "/api/query-as-of" {:as_of "2026-01-01T00:00:00Z"})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))
    (testing "POST /api/query-federated continues to reject oversized
              query_name (this endpoint already had the cap; the test
              guards against regression as the constant gets centralized)"
      (let [{:keys [status error]} (send "/api/query-federated"
                                         {:basis_sha "31389f9382cbc440aafeeb1b854c27830cf1e26f"})]
        (is (= 400 status))
        (is (re-find #"query_name exceeds maximum length" (str error))
            error)))))

(deftest ask-empty-question-returns-400
  (testing "POST /api/ask with question='' must surface as 400 'question
            is required' rather than reaching agent/ask and dying as
            500. The existing missing-field check (when-not …) only
            caught nil; empty string was truthy and slipped past it."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          ;; Send a repo_path that exists shape-wise so the question-blank
          ;; check is the gate that fires first; without my fix, empty
          ;; question would slip past every check and 500 inside agent/ask
          ;; (or, with no repo on disk, surface as 404 from resolve-repo —
          ;; either way, NOT the 400 about question that the contract wants).
          resp    (handler {:request-method :post
                            :uri            "/api/ask"
                            :headers        {}
                            :body (java.io.ByteArrayInputStream.
                                   (.getBytes (json/write-str {:repo_path "/tmp/whatever-path"
                                                               :question  ""})
                                              "UTF-8"))})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp))
          (str "expected 400 from blank-question gate, got "
               (:status resp) " '" (:error body) "'"))
      (is (re-find #"(?i)question" (str (:error body)))
          (str "expected 'question' in error, got: " (:error body))))))

(deftest refresh-clone-missing-uses-db-name-not-abs-path
  (testing "refresh-repo!'s 'Clone not found' ex-info used to embed the
            absolute clone-path so anyone reading the daemon log could
            deduce the db-dir layout. Now references the db-name only;
            full path stays in :ex-data for daemon-side debugging but
            isn't in the message string the http error log prints."
    (let [tmp (str "/tmp/noumenon-clone-leak-test-" (System/currentTimeMillis))]
      (try
        (repo-mgr/refresh-repo! tmp "ghost-name")
        (is false "expected ex-info — the on-disk clone doesn't exist")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"ghost-name" (.getMessage e))
              (str "error should mention db-name, got: " (.getMessage e)))
          (is (not (re-find (re-pattern (java.util.regex.Pattern/quote tmp))
                            (.getMessage e)))
              (str "error must not include the absolute db-dir, got: "
                   (.getMessage e))))))))

(deftest repo-remove-and-refresh-unknown-name-404
  (testing "DELETE /api/repos/<unknown> and POST /api/repos/<unknown>/refresh
            used to surface as 500 'Internal server error' because the
            handlers called repo-mgr without an existence check and the
            downstream ex-info had no :status. They now return a clean
            404 'Repo not registered: <name>'."
    (let [tmp     (str "/tmp/noumenon-repo-mgmt-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          delete  (handler {:request-method :delete :uri "/api/repos/ghost"})
          refresh (handler {:request-method :post   :uri "/api/repos/ghost/refresh"})
          dbody   (json/read-str (:body delete) :key-fn keyword)
          rbody   (json/read-str (:body refresh) :key-fn keyword)]
      (is (= 404 (:status delete)))
      (is (re-find #"(?i)not registered|not found" (str (:error dbody))) (str dbody))
      (is (= 404 (:status refresh)))
      (is (re-find #"(?i)not registered|not found" (str (:error rbody))) (str rbody)))))

(deftest ask-feedback-on-missing-session-returns-404
  (testing "POST /api/ask/sessions/<unknown>/feedback used to write
            feedback to a non-existent session and return 200, leaving
            an orphan attribute set in the meta DB and lying to the
            client. The handler now looks up the session first and
            404s on miss, matching handle-ask-session-detail."
    (let [tmp     (str "/tmp/noumenon-feedback-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          resp    (handler {:request-method :post
                            :uri            "/api/ask/sessions/ghostly/feedback"
                            :headers        {}
                            :body (java.io.ByteArrayInputStream.
                                   (.getBytes (json/write-str {:feedback "positive"}) "UTF-8"))})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 404 (:status resp)))
      (is (re-find #"(?i)session.*not found|not found" (str (:error body)))
          (str "expected 'not found' wording, got: " (:error body))))))

(deftest malformed-json-body-returns-400
  (testing "POST with a syntactically broken JSON body must surface as
            400 'Invalid JSON body', not as a generic 500. The previous
            parse-json-body let json/read-str's exception flow up into
            the make-handler catch-all, where it became 'Internal
            server error' even though the issue was wholly the
            client's input."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          request {:request-method :post
                   :uri            "/api/import"
                   :headers        {}
                   :body           (java.io.ByteArrayInputStream.
                                    (.getBytes "{not json" "UTF-8"))}
          resp    (handler request)
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (re-find #"(?i)invalid json" (str (:error body)))
          (str "expected 'Invalid JSON' wording, got: " (:error body))))))

(deftest settings-strings-stay-strings
  (testing "POST /api/settings with `{\"value\": \"42\"}` used to round-trip
            as the integer 42 — the handler ran every string value
            through edn/read-string and silently re-typed it. The
            handler now stores strings as strings; typed callers can
            send `{\"value\": 42}` (JSON int) for an integer. Cross-
            language clients (Electron UI, future GUIs) get the type
            they sent."
    (let [tmp     (str "/tmp/noumenon-settings-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          set-it  (fn [k v]
                    (handler (post-with-body "/api/settings" {:key k :value v})))
          read-it (fn []
                    (let [resp (handler {:request-method :get :uri "/api/settings"})]
                      (json/read-str (:body resp) :key-fn keyword)))]
      (testing "string \"42\" stays a string (was the integer 42)"
        (set-it "test/string" "42")
        (let [body (read-it)]
          (is (= "42" (get-in body [:data (keyword "test/string")]))
              (str "expected string '42', got: " (pr-str (:data body))))))
      (testing "string \"true\" stays a string (was the boolean true)"
        (set-it "test/bool-string" "true")
        (let [body (read-it)]
          (is (= "true" (get-in body [:data (keyword "test/bool-string")]))
              (str "expected string 'true', got: " (pr-str (:data body))))))
      (testing "JSON integer is still stored as integer"
        (set-it "test/int" 42)
        (let [body (read-it)]
          (is (= 42 (get-in body [:data (keyword "test/int")]))
              (str "expected int 42, got: " (pr-str (:data body))))))
      (testing "JSON boolean is still stored as boolean"
        (set-it "test/bool" true)
        (let [body (read-it)]
          (is (= true (get-in body [:data (keyword "test/bool")]))
              (str "expected bool true, got: " (pr-str (:data body)))))))))

(deftest as_of-non-string-non-number-clean-error
  (testing "as_of with a non-string, non-number value used to surface
            the JVM ClassCastException message verbatim, leaking
            class names like 'clojure.lang.PersistentVector cannot be
            cast to java.lang.Number'. The handler now type-checks
            as_of first and produces a clean
            'as_of must be an ISO-8601 string or epoch milliseconds'
            message. The string-but-unparseable branch (e.g. typo
            'not-a-date') still passes the JVM message through so
            the user sees the actual parse complaint."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          send    (fn [as-of]
                    (let [resp (handler (post-with-body "/api/query-as-of"
                                                        {:repo_path  "any"
                                                         :query_name "recent-commits"
                                                         :as_of      as-of}))
                          body (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error body)}))]
      (testing "vector triggers clean message, no class name leak"
        (let [{:keys [status error]} (send [123])]
          (is (= 400 status))
          (is (re-find #"(?i)as_of must be" (str error)) error)
          (is (not (re-find #"clojure\.lang\." (str error)))
              (str "expected no JVM class names, got: " error))))
      (testing "boolean triggers clean message"
        (let [{:keys [status error]} (send true)]
          (is (= 400 status))
          (is (re-find #"(?i)as_of must be" (str error)) error)
          (is (not (re-find #"java\.lang\.Boolean" (str error))) error)))
      (testing "object triggers clean message"
        (let [{:keys [status error]} (send {:foo 1})]
          (is (= 400 status))
          (is (re-find #"(?i)as_of must be" (str error)) error)
          (is (not (re-find #"PersistentArrayMap" (str error))) error)))
      (testing "string-but-unparseable preserves Instant/parse message"
        (let [{:keys [status error]} (send "not-a-date")]
          (is (= 400 status))
          ;; the JVM message branch is intentionally kept so users see
          ;; the actual parse complaint
          (is (re-find #"(?i)not-a-date|could not be parsed" (str error)) error))))))

(deftest with-repo-rejects-bad-repo_path-shapes
  (testing "with-repo runs type+length+blank checks before FS work, so
            non-string / oversized / empty repo_path values get a clean
            400 instead of leaking ClassCastException as 500. Covers
            every endpoint that funnels through with-repo (~16); the
            previous fix only patched delta-ensure."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          send    (fn [body]
                    (let [resp (handler (post-with-body "/api/import" body))
                          parsed (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error parsed)}))]
      (testing "Long (number) is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path 42})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "vector is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path ["a"]})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "object is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path {:a 1}})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "boolean is rejected with 400, not 500"
        (let [{:keys [status error]} (send {:repo_path true})]
          (is (= 400 status))
          (is (re-find #"must be a string" (str error)) error)))
      (testing "empty string is rejected with 400 (was 500 because empty
                fell through to the bare-db-name branch and shelled out
                to git log against db://)"
        (let [{:keys [status error]} (send {:repo_path ""})]
          (is (= 400 status))
          (is (re-find #"(?i)blank|empty|required" (str error)) error)))
      (testing "repo_path over the 4096-char length cap is rejected with
                400, not echoed back in a 404 — request-amplification
                mitigation. (Anything > max-repo-path-len triggers the
                cap; 5 KB is enough to exercise the gate without
                ballooning failure output.)"
        (let [{:keys [status error]} (send {:repo_path (apply str (repeat 5000 \A))})]
          (is (= 400 status))
          (is (re-find #"exceeds maximum length" (str error)) error))))))

(deftest federated-delta-opts-derives-parent-metadata
  (let [opts-fn @#'noumenon.http.handlers.query/federated-delta-opts]
    (is (= {:parent-db-name "myrepo"
            :branch-name    "feat"
            :parent-host    "noum.example.com:8765"}
           (opts-fn {:headers {"host" "noum.example.com:8765"}}
                    "myrepo"
                    "feat"))
        "auto-derives parent-db-name from the resolved trunk repo and
         parent-host from the request's Host header, so federated query
         calls leave a breadcrumb on the delta's branch entity even when
         the caller didn't pass them explicitly")
    (is (= {:parent-db-name "myrepo"}
           (opts-fn {:headers {}} "myrepo" nil))
        "missing Host header (unusual proxy setup) and missing branch —
         parent-host is omitted, parent-db-name is still set; the
         breadcrumb is informational and partial is acceptable")))

(defn- make-tmp-git-repo!
  "Create a fresh git repo with one commit. Returns the absolute path."
  [name]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/noumenon-import-test-"
                 name "-" (System/currentTimeMillis))
        sh  (fn [& args]
              (let [{:keys [exit err]} (apply shell/sh (concat args [:dir dir]))]
                (when (not= 0 exit)
                  (throw (ex-info (str "git failed: " err) {:exit exit})))))]
    (.mkdirs (java.io.File. dir))
    (sh "git" "init" "-q")
    (sh "git" "config" "user.email" "t@t")
    (sh "git" "config" "user.name" "t")
    (spit (str dir "/a.txt") "hello")
    (sh "git" "add" "a.txt")
    (sh "git" "commit" "-q" "-m" "init")
    dir))

(deftest artifact-history-prompt-without-name-returns-400
  (testing "GET /api/artifacts/history?type=prompt (no `name`) used to
            return 500 'Internal server error' because the handler
            forwarded a nil name into a Datalog query expecting it
            bound. The MCP handler already rejected this; the HTTP
            handler now matches with a clean 400 'name is required
            when type is prompt'."
    (let [tmp     (str "/tmp/noumenon-arthist-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          resp    (handler {:request-method :get
                            :uri "/api/artifacts/history"
                            :query-string "type=prompt"
                            :headers {}})
          body    (json/read-str (:body resp) :key-fn keyword)]
      (is (= 400 (:status resp)))
      (is (re-find #"(?i)name" (str (:error body)))
          (str "expected 'name is required' wording, got: " (:error body)))
      (is (not (re-find #"(?i)internal server error" (str (:error body))))
          (str "must not surface as generic 500, got: " (:error body))))))

(deftest query-params-non-map-rejected-with-400
  (testing "POST /api/query (and friends) used to return 500 'Internal
            server error' when `params` was a JSON array instead of an
            object — the handler's keywordization step destructured each
            scalar as a [k v] pair and threw IllegalArgumentException
            before any validator ran. Now `params` is type-checked up
            front and a non-object surfaces as 400 with 'params must be
            an object'."
    (let [handler (http/make-handler {:db-dir "/tmp/noumenon-http-test-nonexistent/"})
          send    (fn [uri body]
                    (let [resp (handler (post-with-body uri body))
                          parsed (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error parsed)}))]
      (testing "/api/query rejects array params"
        (let [{:keys [status error]} (send "/api/query"
                                           {:repo_path "any"
                                            :query_name "recent-commits"
                                            :params [1 2]})]
          (is (= 400 status))
          (is (re-find #"(?i)params" (str error)) error)
          (is (re-find #"(?i)object|map" (str error))
              (str "expected 'object/map' wording, got: " error))))
      (testing "/api/query-as-of rejects array params"
        (let [{:keys [status error]} (send "/api/query-as-of"
                                           {:repo_path "any"
                                            :query_name "recent-commits"
                                            :as_of "2026-01-01T00:00:00Z"
                                            :params [1 2]})]
          (is (= 400 status))
          (is (re-find #"(?i)params" (str error)) error)))
      (testing "/api/query-federated rejects array params"
        (let [{:keys [status error]} (send "/api/query-federated"
                                           {:repo_path "/nonexistent"
                                            :query_name "recent-commits"
                                            :basis_sha "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                                            :params [1 2]})]
          (is (= 400 status))
          (is (re-find #"(?i)params" (str error)) error)))
      (testing "/api/query rejects scalar params"
        (let [{:keys [status error]} (send "/api/query"
                                           {:repo_path "any"
                                            :query_name "recent-commits"
                                            :params 42})]
          (is (= 400 status))
          (is (re-find #"(?i)params" (str error)) error))))))

(deftest repos-register-rejects-bad-urls-with-400
  (testing "POST /api/repos with file://, http://localhost, http://127.0.0.1
            (and other URLs that fail git/validate-clone-url!) used to
            surface as a bare 500 'Internal server error' because the
            validator's ex-info didn't carry :status. The handler now
            renders these as 400 with the actual rejection reason, so
            HTTP clients can tell invalid input from a server fault."
    (let [tmp     (str "/tmp/noumenon-repos-test-" (System/currentTimeMillis))
          handler (http/make-handler {:db-dir tmp})
          send    (fn [url]
                    (let [resp (handler (post-with-body "/api/repos" {:url url}))
                          body (json/read-str (:body resp) :key-fn keyword)]
                      {:status (:status resp) :error (:error body)}))]
      (testing "file:// scheme is rejected as 400"
        (let [{:keys [status error]} (send "file:///tmp/x")]
          (is (= 400 status))
          (is (re-find #"(?i)scheme|invalid url" (str error))
              (str "expected scheme-rejection wording, got: " error))
          (is (not (re-find #"(?i)internal server error" (str error)))
              (str "must not surface as generic 500, got: " error))))
      (testing "http://localhost is rejected as 400"
        (let [{:keys [status error]} (send "http://localhost/x.git")]
          (is (= 400 status))
          (is (re-find #"(?i)private|loopback" (str error))
              (str "expected loopback-rejection wording, got: " error))))
      (testing "http://127.0.0.1 is rejected as 400"
        (let [{:keys [status error]} (send "http://127.0.0.1/x.git")]
          (is (= 400 status))
          (is (re-find #"(?i)private|loopback" (str error))
              (str "expected loopback-rejection wording, got: " error)))))))

(deftest http-import-writes-head-sha
  (testing "POST /api/import populates :repo/head-sha so a follow-up
            GET /api/status/<db-name> returns the actual SHA. MCP
            noumenon_status documents 'compare with git rev-parse HEAD'
            as the freshness check, which is meaningless when head-sha
            is null after the documented first step (import)."
    (let [repo-path   (make-tmp-git-repo! "head-sha")
          db-dir      (str "/tmp/noumenon-import-test-db-" (System/currentTimeMillis))
          handler     (http/make-handler {:db-dir db-dir})
          db-name     (util/derive-db-name repo-path)
          import-resp (handler (post-with-body "/api/import" {:repo_path repo-path}))
          status-resp (handler {:request-method :get
                                :uri (str "/api/status/" db-name)
                                :headers {}})
          status-body (json/read-str (:body status-resp) :key-fn keyword)
          head-sha    (-> (shell/sh "git" "-C" repo-path "rev-parse" "HEAD")
                          :out str/trim)]
      (is (= 200 (:status import-resp)))
      (is (= 200 (:status status-resp)))
      (is (= head-sha (get-in status-body [:data :head-sha]))
          (str "import must persist the HEAD sha; got: "
               (pr-str (:data status-body)))))))

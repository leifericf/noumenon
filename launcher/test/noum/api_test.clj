(ns noum.api-test
  "Unit tests for noum.api launcher helpers. Run via:
       bb -cp src:resources:test -m clojure.test/run-tests noum.api-test"
  (:require [clojure.test :refer [deftest is testing]]
            [noum.api :as api]
            [noum.cli :as cli]
            [noum.tui.choose :as choose]))

(deftest launcher-setting-key?-routes-by-namespace-prefix
  (testing "federation/* keys are launcher-local"
    (is (true? (api/launcher-setting-key? "federation/auto-route")))
    (is (true? (api/launcher-setting-key? :federation/auto-route))))
  (testing "non-federation keys go through the daemon"
    (is (false? (api/launcher-setting-key? "ask/concurrency")))
    (is (false? (api/launcher-setting-key? "model"))))
  (testing "blank or single-segment keys are not launcher-local"
    (is (false? (api/launcher-setting-key? "")))
    (is (false? (api/launcher-setting-key? "no-namespace")))))

(deftest detect-federation-context-bails-on-local-mode
  (testing "nil conn (local mode) returns nil — never auto-federates"
    (is (nil? (api/detect-federation-context "/tmp" nil))))
  (testing "non-hosted conn (no :host) returns nil"
    (is (nil? (api/detect-federation-context "/tmp" {:port 7891})))))

(deftest blocked-address?-classifies-ipv6-without-reflection
  ;; Bug: bb's native-image lacks reflection metadata for Inet6Address's
  ;; instance methods (.isLoopbackAddress, .getAddress, etc.). Any host
  ;; whose DNS lookup returned an Inet6Address used to surface as
  ;; MissingReflectionRegistrationError. Verify against synthetic
  ;; addresses that the classifier returns the right answer with no
  ;; reflection error.
  (let [blocked? @#'noum.api/blocked-address?]
    (testing "IPv6 loopback ::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "::1"))))))
    (testing "IPv6 link-local fe80::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "fe80::1"))))))
    (testing "IPv6 unique local fd00::1 is private"
      (is (true? (boolean (blocked? (java.net.InetAddress/getByName "fd00::1"))))))
    (testing "IPv4 public 8.8.8.8 is not private"
      (is (false? (boolean (blocked? (java.net.InetAddress/getByName "8.8.8.8"))))))
    (testing "public IPv6 2001:4860:4860::8888 is not private (used to crash with MissingReflectionRegistrationError)"
      (is (false? (boolean (blocked? (java.net.InetAddress/getByName "2001:4860:4860::8888"))))))))

(defn- assert-no-http-call
  "Run f with api/post! / api/get! / api/ensure-backend! redefined to
   throw — proves the launcher rejected without trying to talk to the
   daemon."
  [f]
  (require 'noum.api)
  (with-redefs [noum.api/post!           (fn [& _] (throw (ex-info "post! must not be called" {})))
                noum.api/get!            (fn [& _] (throw (ex-info "get! must not be called" {})))
                noum.api/ensure-backend! (fn [& _] (throw (ex-info "ensure-backend! must not be called" {})))]
    (f)))

(defn- with-stderr
  "Run f with *err* redirected to a string buffer; return [exit captured-stderr]."
  [f]
  (let [buf (java.io.StringWriter.)
        ec  (binding [*err* buf] (f))]
    [ec (str buf)]))

(deftest do-introspect-handles-valueless-flags
  ;; Bug: --status / --stop with no following value booleanized to true.
  ;; (string? true) was false, so the cond fell through to do-api-command
  ;; which emitted "Use `noum databases`…" — totally unrelated to the
  ;; user's actual intent of checking a run-id.
  (require 'noum.main)
  (let [do-introspect (resolve 'noum.main/do-introspect)]
    (testing "--status with no value: exit 1, message names --status (not databases)"
      (let [[ec err] (with-stderr
                       #(assert-no-http-call
                         (fn [] (do-introspect {:command "introspect" :flags {:status true} :positional []}))))]
        (is (= 1 ec))
        (is (re-find #"--status" err))
        (is (not (re-find #"noum databases" err)))))
    (testing "--stop with no value: exit 1, message names --stop"
      (let [[ec err] (with-stderr
                       #(assert-no-http-call
                         (fn [] (do-introspect {:command "introspect" :flags {:stop true} :positional []}))))]
        (is (= 1 ec))
        (is (re-find #"--stop" err))
        (is (not (re-find #"noum databases" err)))))))

(deftest do-query-rejects-blank-as-of-and-raw
  ;; Bug: --as-of "" and --raw "" passed launcher's truthy / string?
  ;; checks because empty string is truthy. The launcher then made an
  ;; HTTP call so the user could see a server-side blank-rejection.
  (require 'noum.main)
  (let [do-query (resolve 'noum.main/do-query)]
    (testing "--as-of blank rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-query {:flags {:as-of ""} :positional ["q" "/tmp"]})))))
    (testing "--as-of whitespace rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-query {:flags {:as-of "   "} :positional ["q" "/tmp"]})))))
    (testing "--raw blank rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-query {:flags {:raw ""} :positional ["/tmp"]})))))
    (testing "--raw whitespace rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-query {:flags {:raw "  "} :positional ["/tmp"]})))))))

(deftest do-delta-ensure-validates-basis-sha
  ;; Bug: `noum delta-ensure /tmp --basis-sha` (flag with no value) made
  ;; the launcher pass the boolean `true` through to the server, which
  ;; validated and rejected with a 400 — wasted round-trip. Same for an
  ;; obviously bogus SHA shape.
  (require 'noum.main)
  (let [do-delta-ensure (resolve 'noum.main/do-delta-ensure)]
    (testing "boolean-true basis-sha rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-delta-ensure {:flags {:basis-sha true} :positional ["/tmp"]})))))
    (testing "non-hex SHA rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-delta-ensure {:flags {:basis-sha "not-hex"} :positional ["/tmp"]})))))
    (testing "uppercase-hex SHA rejected with no API call"
      (is (= 1 (assert-no-http-call
                #(do-delta-ensure {:flags {:basis-sha "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4"} :positional ["/tmp"]})))))))

(deftest serve-rejects-host-flag
  ;; Bug: `noum serve --host X` silently dropped --host and ran the MCP
  ;; server colocated with the local daemon. Reject the combination
  ;; up front; users wanting remote should `noum connect` first.
  (require 'noum.main)
  (let [do-serve (resolve 'noum.main/do-serve)]
    (testing "serve --host rejected with exit 1"
      (is (= 1 (do-serve {:flags {:host "127.0.0.1:9999" :insecure true}}))))))

(deftest ping-target-respects-host-flag
  ;; Bug: do-ping called daemon/connection directly, ignoring --host /
  ;; --token / --insecure entirely. The new ping-target helper resolves
  ;; the connection to ping based on flags first, then active named
  ;; connection, then local daemon — without spawning anything.
  (require 'noum.main)
  (let [ping-target (resolve 'noum.main/ping-target)]
    (testing "explicit --host wins"
      (is (= {:host "203.0.113.5:9000" :token "abc" :insecure true}
             (ping-target {:host "203.0.113.5:9000" :token "abc" :insecure true}))))
    (testing "no flags, no active connection, no local daemon → nil"
      ;; with-redefs on api/active-connection + daemon/connection so the
      ;; helper has nothing to fall back to
      (require 'noum.api 'noum.daemon)
      (is (nil? (with-redefs [noum.api/active-connection (constantly nil)
                              noum.daemon/connection      (constantly nil)]
                  (ping-target {})))))))

(deftest read-arrow!-returns-nil-on-bare-esc
  ;; Bug: choose/select unconditionally read two more bytes after ESC,
  ;; which blocked indefinitely if the user pressed ESC alone (no
  ;; following arrow code). The new read-arrow! helper peeks via
  ;; InputStream/available with a small grace period.
  (require 'noum.tui.choose)
  (let [read-arrow! (resolve 'noum.tui.choose/read-arrow!)]
    (testing "ESC followed by [ A (arrow up) is :up"
      (let [stream (java.io.ByteArrayInputStream. (byte-array [91 65]))]
        (is (= :up (read-arrow! stream)))))
    (testing "ESC followed by [ B (arrow down) is :down"
      (let [stream (java.io.ByteArrayInputStream. (byte-array [91 66]))]
        (is (= :down (read-arrow! stream)))))
    (testing "bare ESC (empty stream after) returns nil — does NOT block"
      (let [stream (java.io.ByteArrayInputStream. (byte-array 0))]
        (is (nil? (read-arrow! stream)))))))

(deftest parse-setting-value-doesnt-silently-null-overflow
  ;; Bug: an integer string that overflows Long (e.g. 21 digits) made
  ;; parse-long return nil, which parse-setting-value passed through —
  ;; so the daemon got `:value nil` and replied "key and value are
  ;; required". User typed a value; launcher silently nulled it.
  (require 'noum.main)
  (let [parse (resolve 'noum.main/parse-setting-value)]
    (testing "nil"
      (is (nil? (parse nil))))
    (testing "true / false"
      (is (true? (parse "true")))
      (is (false? (parse "false"))))
    (testing "small int parses to long"
      (is (= 42 (parse "42")))
      (is (= -7 (parse "-7"))))
    (testing "too-large int falls back to string instead of nil"
      (is (= "999999999999999999999" (parse "999999999999999999999"))))
    (testing "arbitrary string passes through"
      (is (= "claude-haiku" (parse "claude-haiku"))))))

(deftest run-handler!-converts-thrown-exception-to-clean-exit
  ;; Bug: -main called (System/exit (handler parsed)) directly. If the
  ;; handler threw (e.g. daemon/start! timing out, ensure-backend!
  ;; failing), the bb runtime dumped the raw stack trace with source
  ;; paths and context. The new run-handler! catches any exception,
  ;; prints a clean error, and returns exit code 1.
  (require 'noum.main)
  (let [run-handler! (resolve 'noum.main/run-handler!)]
    (testing "happy path: handler return value passes through"
      (is (= 0 (run-handler! (constantly 0) {}))))
    (testing "handler throws ex-info → exit 1, no stack trace propagated"
      (is (= 1 (run-handler!
                (fn [_] (throw (ex-info "Daemon failed to start within 30 seconds" {})))
                {}))))
    (testing "handler throws plain Exception → exit 1"
      (is (= 1 (run-handler!
                (fn [_] (throw (Exception. "boom")))
                {}))))))

(deftest get!-returns-error-on-connection-refused
  ;; Bug: api/get! relied on :throw false to swallow HTTP-level errors,
  ;; but it didn't catch ConnectException / UnknownHostException /
  ;; SocketTimeoutException — those bubbled to the user as raw stack
  ;; traces. Verify the return shape with a closed port (port 1 is
  ;; reliably refused on every OS).
  (let [resp (api/get! {:host "127.0.0.1:1" :insecure true} "/health")]
    (is (false? (:ok resp)))
    (is (string? (:error resp)))
    (is (re-find #"(?i)connect|refused|cannot reach" (:error resp)))))

(deftest parse-body-tolerates-non-json
  ;; Bug: parse-body called cheshire/parse-string directly, so a non-JSON
  ;; response (e.g. text/plain HTTP 500 with a Datomic stack-trace echo)
  ;; threw JsonParseException. The user saw a raw Clojure stack trace
  ;; instead of a clean error message.
  (testing "valid JSON parses normally"
    (is (= {:ok true :data {:status :ok}}
           (api/parse-body "{\"ok\":true,\"data\":{\"status\":\"ok\"}}"))))
  (testing "non-JSON returns nil instead of throwing"
    (is (nil? (api/parse-body "processing clause: [?t :token/hash ?h]"))))
  (testing "empty string returns nil"
    (is (nil? (api/parse-body ""))))
  (testing "nil input returns nil (existing contract)"
    (is (nil? (api/parse-body nil)))))

(deftest parse-watch-interval
  ;; Bug: `noum watch /tmp --interval -5` used `parse-long` directly,
  ;; which returned -5 unchecked. The watch loop printed "polling every
  ;; -5s" and crashed Thread/sleep on the first iteration. `--interval
  ;; abc` silently fell back to 30. The new helper returns either a
  ;; positive integer or an `:error` map.
  (require 'noum.main)
  (let [parse-interval (resolve 'noum.main/parse-watch-interval)]
    (testing "no flag → default 30"
      (is (= 30 (parse-interval nil))))
    (testing "valid positive integer"
      (is (= 7 (parse-interval "7"))))
    (testing "negative interval rejected"
      (is (= {:error "--interval must be a positive integer (got -5)"}
             (parse-interval "-5"))))
    (testing "zero rejected"
      (is (= {:error "--interval must be a positive integer (got 0)"}
             (parse-interval "0"))))
    (testing "non-numeric rejected (no silent fallback to 30)"
      (is (= {:error "--interval must be a positive integer (got abc)"}
             (parse-interval "abc"))))))

(deftest history-prompt-no-name-doesnt-throw
  ;; Bug: `noum history prompt` (no name) tried to enumerate prompt files
  ;; via `(io/resource "prompts/")`, which returns nil from the launcher's
  ;; bb classpath. `(io/file nil)` then NPE'd. The handler should produce
  ;; a clean Usage message instead.
  (require 'noum.main)
  (let [do-history (resolve 'noum.main/do-history)
        result (do-history {:flags {} :positional ["prompt"]})]
    (testing "no NPE; returns a non-zero exit code"
      (is (= 1 result)))))

(deftest parse-args-drops-blank-positionals
  (testing "an empty string positional is dropped (used to silently resolve to cwd basename)"
    (let [parsed (cli/parse-args ["status" ""])]
      (is (= [] (:positional parsed)))))
  (testing "a whitespace-only positional is dropped"
    (let [parsed (cli/parse-args ["status" "   "])]
      (is (= [] (:positional parsed)))))
  (testing "a NUL byte is treated as blank"
    (let [parsed (cli/parse-args ["status" " "])]
      (is (= [] (:positional parsed)))))
  (testing "a literal '.' is kept (current-directory shorthand)"
    (let [parsed (cli/parse-args ["status" "."])]
      (is (= ["."] (:positional parsed)))))
  (testing "non-blank positionals are unchanged"
    (let [parsed (cli/parse-args ["compare" "/tmp/repo" "run-a" "run-b"])]
      (is (= ["/tmp/repo" "run-a" "run-b"] (:positional parsed)))))
  (testing "internal whitespace is preserved (NUL stripping must not eat spaces)"
    (let [parsed (cli/parse-args ["ask" "/tmp/repo" "what is this thing"])]
      (is (= ["/tmp/repo" "what is this thing"] (:positional parsed))))))

(deftest parse-args-accumulates-repeated-param
  (testing "a single --param parses normally"
    (let [parsed (cli/parse-args ["query" "name" "repo" "--param" "k=v"])]
      (is (= ["k=v"] (-> parsed :flags :param)))))
  (testing "repeated --param flags accumulate (the help text claims 'repeat command as needed')"
    (let [parsed (cli/parse-args ["query" "name" "repo"
                                  "--param" "k1=v1"
                                  "--param" "k2=v2"
                                  "--param" "k3=v3"])]
      (is (= ["k1=v1" "k2=v2" "k3=v3"] (-> parsed :flags :param))))))

(deftest base-url-honors-loopback-with-or-without-scheme
  (testing "bare localhost / 127.0.0.1 are allowed"
    (is (= "http://localhost:7895"
           (api/base-url {:host "localhost:7895" :insecure true})))
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:host "127.0.0.1:7895" :insecure true}))))
  (testing "scheme-prefixed localhost / 127.0.0.1 are allowed (the SSRF check used to block these)"
    (is (= "http://localhost:7895"
           (api/base-url {:host "http://localhost:7895" :insecure true})))
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:host "http://127.0.0.1:7895" :insecure true})))
    (is (= "https://localhost:7895"
           (api/base-url {:host "https://localhost:7895"}))))
  (testing "no host falls back to local-daemon port"
    (is (= "http://127.0.0.1:7895"
           (api/base-url {:port 7895})))))

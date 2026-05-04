(ns noum.daemon-test
  "Unit tests for noum.daemon stop-path behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [noum.daemon :as daemon]))

(defn- capture-eprintln
  "Run f with noum.tui.core/eprintln redirected to a string-builder.
   Returns the captured text (one entry per call, joined by newlines)."
  [f]
  (let [out (java.io.StringWriter.)
        eprintln-fn (fn [s] (.write out (str s "\n")))]
    (with-redefs [noum.tui.core/eprintln eprintln-fn]
      (f))
    (str out)))

(deftest stop-without-daemon-edn-but-no-orphan-prints-friendly-message
  (testing "When daemon.edn is absent and no orphan holds the lock, the
            launcher prints the existing 'No managed daemon to stop'
            message — regression guard for the old behavior."
    (let [text (capture-eprintln
                #(with-redefs [daemon/read-daemon-info (fn [] nil)
                               daemon/lock-holder-pid (fn [] nil)]
                   (daemon/stop!)))]
      (is (re-find #"(?i)no managed daemon" text) text))))

(deftest stop-without-daemon-edn-adopts-orphan-and-kills-it
  (testing "When daemon.edn is absent but lsof finds a PID holding the
            meta-db lock, stop! must adopt that PID and SIGTERM-then-
            SIGKILL it instead of giving up. Without this, an orphan
            daemon (missing daemon.edn but live JVM) was unkillable
            through the launcher and required `kill -9` from outside."
    (let [killed (atom [])
          text   (capture-eprintln
                  #(with-redefs [daemon/read-daemon-info (fn [] nil)
                                 daemon/lock-holder-pid (fn [] 99999)
                                 daemon/pid-cmdline     (fn [_] "java -jar fake")
                                 daemon/kill-pid!       (fn [pid]
                                                          (swap! killed conj pid)
                                                          :term)]
                     (daemon/stop!)))]
      (is (= [99999] @killed) "kill-pid! must be invoked on the lsof-reported PID")
      (is (re-find #"(?i)orphan" text)
          (str "user-facing output should call out the orphan path; got: " text))
      (is (re-find #"99999" text)
          (str "output should name the orphan PID; got: " text)))))

(deftest stop-orphan-with-stuck-process-throws
  (testing "If even SIGKILL fails on the orphan, stop! propagates an
            error so the user knows the lock is still held."
    (with-redefs [daemon/read-daemon-info (fn [] nil)
                  daemon/lock-holder-pid (fn [] 99999)
                  daemon/pid-cmdline     (fn [_] nil)
                  daemon/kill-pid!       (fn [_] :alive)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"refused to die"
           (daemon/stop!))))))

(deftest stop-with-daemon-edn-takes-recorded-path
  (testing "Backward-compat: when daemon.edn is present, stop! still
            uses the recorded PID and the existing 'Daemon stopped'
            wording."
    (let [killed (atom [])
          deleted (atom 0)
          text (capture-eprintln
                #(with-redefs [daemon/read-daemon-info (fn [] {:pid 12345 :port 9999})
                               daemon/kill-pid!       (fn [pid]
                                                        (swap! killed conj pid)
                                                        :term)
                               babashka.fs/delete-if-exists (fn [_] (swap! deleted inc) true)]
                   (daemon/stop!)))]
      (is (= [12345] @killed))
      (is (= 1 @deleted) "daemon.edn must be deleted on success")
      (is (re-find #"Daemon stopped \(PID 12345\)" text) text)
      (is (not (re-find #"(?i)orphan" text))
          (str "recorded-PID path must not call itself an orphan: " text)))))

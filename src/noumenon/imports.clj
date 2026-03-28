(ns noumenon.imports
  "Deterministic import extraction — parses source code to build a file→file
   dependency graph without an LLM. Dispatches on :file/lang via multimethods."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.namespace.parse :as ns-parse]
            [datomic.client.api :as d]
            [noumenon.analyze :as analyze]
            [noumenon.files :as files]
            [noumenon.util :refer [log!]])
  (:import [java.io PushbackReader StringReader]))

;; ---------------------------------------------------------------------------
;; Multimethods — dispatch on :file/lang keyword
;; ---------------------------------------------------------------------------

(defmulti extract-imports
  "Extract import/require names from source text. Returns a seq of strings.
   Each string is an unresolved module/namespace name."
  (fn [lang _text] lang))

(defmethod extract-imports :default [_ _] [])

(defmulti resolve-import
  "Resolve an import name to a repo-relative file path.
   Returns the path string, or nil if unresolvable (external dep)."
  (fn [lang _import-name _source-path _all-paths] lang))

(defmethod resolve-import :default [_ _ _ _] nil)

;; ---------------------------------------------------------------------------
;; Tool probing — check which external tools are available
;; ---------------------------------------------------------------------------

(defn- tool-available?
  "Check if a command-line tool is on PATH."
  [cmd]
  (try
    (let [{:keys [exit]} (shell/sh "which" cmd)]
      (zero? exit))
    (catch Exception _ false)))

(defn probe-tools
  "Probe for external tools needed by non-Clojure parsers.
   Returns a map of {:lang {:tool \"name\" :available? bool}}."
  []
  {:python     {:tool "python3" :available? (tool-available? "python3")}
   :javascript {:tool "node"    :available? (tool-available? "node")}
   :typescript {:tool "node"    :available? (tool-available? "node")}
   :elixir     {:tool "elixir"  :available? (tool-available? "elixir")}
   :c          {:tool "clang"   :available? (or (tool-available? "clang")
                                                (tool-available? "gcc"))}
   :cpp        {:tool "clang"   :available? (or (tool-available? "clang")
                                                (tool-available? "gcc"))}
   :go         {:tool "go"      :available? (tool-available? "go")}})

(defn log-tool-availability!
  "Log which tools are available and which languages will be skipped.
   Returns a vec of {:lang :tool :file-count} for unavailable tools that
   have files — callers can use this to emit a completion warning."
  [tools file-counts]
  (let [available   (->> tools (filter (comp :available? val)) (map key))
        unavailable (->> tools (remove (comp :available? val)) (map key))
        skipped     (->> unavailable
                         (keep (fn [lang]
                                 (let [n (get file-counts lang 0)]
                                   (when (pos? n)
                                     {:lang lang
                                      :tool (get-in tools [lang :tool])
                                      :file-count n}))))
                         vec)]
    (log! (str "  Postprocess tools — available: Clojure (built-in)"
               (when (seq available)
                 (str ", " (str/join ", " (map name available))))
               (when (seq unavailable)
                 (str " | skipped: "
                      (str/join ", "
                                (map (fn [lang]
                                       (str (name lang) " ("
                                            (get file-counts lang 0) " files)"))
                                     unavailable))))))
    skipped))

;; ---------------------------------------------------------------------------
;; Clojure — deep parsing via tools.namespace
;; ---------------------------------------------------------------------------

(defmethod extract-imports :clojure [_ text]
  (try
    (with-open [rdr (PushbackReader. (StringReader. text))]
      (when-let [ns-decl (ns-parse/read-ns-decl rdr)]
        (->> (ns-parse/deps-from-ns-decl ns-decl)
             (mapv str))))
    (catch Exception _ [])))

(defmethod extract-imports :clojurescript [_ text]
  (extract-imports :clojure text))

(defn- ns->paths
  "Convert a namespace symbol string to candidate file paths.
   foo.bar-baz → [\"foo/bar_baz.clj\" \"foo/bar_baz.cljc\" \"foo/bar_baz.cljs\"]"
  [ns-str]
  (let [base (-> ns-str (str/replace "." "/") (str/replace "-" "_"))]
    [(str base ".clj") (str base ".cljc") (str base ".cljs")]))

(defn- resolve-clj-import
  "Try to resolve a Clojure namespace to a repo file path.
   Handles mono-repos by matching any path ending with the namespace-derived
   suffix (e.g., ring.util.codec -> */ring/util/codec.clj)."
  [ns-str all-paths]
  (let [candidates (ns->paths ns-str)
        ;; First try direct prefixed candidates (fast path)
        prefixed   (mapcat (fn [c] [(str "src/" c) (str "test/" c) (str "dev/" c) c])
                           candidates)
        direct     (first (filter all-paths prefixed))]
    (or direct
        ;; Fall back to suffix matching for mono-repos with subproject dirs
        (let [suffixes (mapcat (fn [c] [(str "/src/" c) (str "/test/" c) (str "/dev/" c)])
                               candidates)]
          (first (for [suffix suffixes
                       p      all-paths
                       :when  (str/ends-with? p suffix)]
                   p))))))

(defmethod resolve-import :clojure [_ import-name _source-path all-paths]
  (resolve-clj-import import-name all-paths))

(defmethod resolve-import :clojurescript [_ import-name source-path all-paths]
  (resolve-import :clojure import-name source-path all-paths))

;; ---------------------------------------------------------------------------
;; Python — python3 ast stdlib via subprocess
;; ---------------------------------------------------------------------------

(def ^:private python-extract-script
  "import ast,json,sys
code=sys.stdin.read()
try:
    tree=ast.parse(code)
except: print('[]'); sys.exit(0)
imports=[]
for n in ast.walk(tree):
    if isinstance(n,ast.Import):
        imports.extend(a.name for a in n.names)
    elif isinstance(n,ast.ImportFrom):
        if n.module and n.level==0: imports.append(n.module)
print(json.dumps(imports))")

(defmethod extract-imports :python [_ text]
  (try
    (let [{:keys [exit out]} (shell/sh "python3" "-c" python-extract-script
                                       :in text)]
      (when (zero? exit)
        (json/read-str (str/trim out))))
    (catch Exception _ [])))

(defn- resolve-python-import [import-name all-paths]
  (let [base     (str/replace import-name "." "/")
        suffixes [(str base ".py") (str base "/__init__.py")]
        direct   (first (filter all-paths suffixes))]
    (or direct
        ;; Handle src-layout projects (src/pkg/...) and other prefixed layouts
        (first (for [suffix (map #(str "/" %) suffixes)
                     p      all-paths
                     :when  (str/ends-with? p suffix)]
                 p)))))

(defmethod resolve-import :python [_ import-name _source-path all-paths]
  (resolve-python-import import-name all-paths))

;; ---------------------------------------------------------------------------
;; JavaScript / TypeScript — node built-in parser via subprocess
;; ---------------------------------------------------------------------------

(def ^:private node-extract-script
  "const fs=require('fs');
const code=fs.readFileSync('/dev/stdin','utf8');
const re=/(?:import\\s+.*?from\\s+['\"]([^'\"]+)['\"]|require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)|export\\s+.*?from\\s+['\"]([^'\"]+)['\"])/g;
const imports=[];let m;
while(m=re.exec(code)){imports.push(m[1]||m[2]||m[3])}
console.log(JSON.stringify(imports))")

(defmethod extract-imports :javascript [_ text]
  (try
    (let [{:keys [exit out]} (shell/sh "node" "-e" node-extract-script :in text)]
      (when (zero? exit)
        (json/read-str (str/trim out))))
    (catch Exception _ [])))

(defmethod extract-imports :typescript [_ text]
  (extract-imports :javascript text))

(defn- resolve-js-import
  "Resolve a relative JS/TS import to a repo file path."
  [import-name source-path all-paths]
  (when (str/starts-with? import-name ".")
    (let [dir  (str/join "/" (butlast (str/split source-path #"/")))
          base (str dir "/" (str/replace import-name #"^\.\/" ""))
          exts [".js" ".ts" ".tsx" ".jsx" "/index.js" "/index.ts"]]
      (first (filter all-paths (cons base (map #(str base %) exts)))))))

(defmethod resolve-import :javascript [_ import-name source-path all-paths]
  (resolve-js-import import-name source-path all-paths))

(defmethod resolve-import :typescript [_ import-name source-path all-paths]
  (resolve-js-import import-name source-path all-paths))

;; ---------------------------------------------------------------------------
;; C / C++ — clang/gcc -MM compiler dependency output
;; ---------------------------------------------------------------------------

(defn- find-c-compiler []
  (cond
    (tool-available? "clang") "clang"
    (tool-available? "gcc")   "gcc"
    :else                     nil))

(defmethod extract-imports :c [_ _text]
  ;; C extraction happens at resolve time via compiler — return empty here.
  ;; The resolve-import method handles both extraction and resolution.
  [])

(defmethod extract-imports :cpp [_ _text]
  [])

(defn- under-repo-path?
  "True when full-path's canonical form is inside repo-path's canonical tree."
  [repo-path full-path]
  (let [canon     (.getCanonicalPath (java.io.File. full-path))
        repo-root (.getCanonicalPath (java.io.File. (str repo-path)))]
    (.startsWith canon (str repo-root "/"))))

(defn- extract-c-includes-from-compiler
  "Run clang/gcc -MM on a file and parse the makefile output into dependency paths."
  [repo-path source-path]
  (when-let [cc (find-c-compiler)]
    (try
      (let [full-path (str repo-path "/" source-path)
            _         (when-not (under-repo-path? repo-path full-path)
                        (throw (ex-info "Path traversal blocked"
                                        {:source-path source-path})))
            {:keys [exit out]} (shell/sh cc "-MM" full-path
                                         :dir (str repo-path))]
        (when (zero? exit)
          (->> (str/replace out #"\\\n" " ")
               (re-seq #"\S+")
               rest                     ; skip target: prefix
               (remove #(str/ends-with? % ":"))
               (map #(str/replace % (str repo-path "/") ""))
               (remove #(str/starts-with? % "/")))))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Elixir — AST parser via Code.string_to_quoted + Macro.prewalk
;; ---------------------------------------------------------------------------

(def ^:private elixir-extract-script
  "code = IO.read(:stdio, :eof)
case Code.string_to_quoted(code) do
  {:ok, ast} ->
    {_, deps} = Macro.prewalk(ast, [], fn
      {d, _, [{:__aliases__, _, parts} | _]} = n, acc
        when d in [:alias, :import, :use, :require] ->
        {n, [Enum.map_join(parts, \".\", &to_string/1) | acc]}
      {d, _, [{{:., _, [{:__aliases__, _, pfx}, :{}]}, _, sfxs}]} = n, acc
        when d in [:alias, :import, :use, :require] ->
        p = Enum.map_join(pfx, \".\", &to_string/1)
        ms = for {:__aliases__, _, pts} <- sfxs do
          p <> \".\" <> Enum.map_join(pts, \".\", &to_string/1)
        end
        {n, ms ++ acc}
      n, acc -> {n, acc}
    end)
    deps |> Enum.uniq() |> Enum.sort() |> Enum.join(\"\\n\") |> IO.puts()
  _ -> :ok
end")

(defmethod extract-imports :elixir [_ text]
  (try
    (let [{:keys [exit out]} (shell/sh "elixir" "-e" elixir-extract-script
                                       :in text)]
      (when (zero? exit)
        (->> (str/split-lines out)
             (remove str/blank?)
             vec)))
    (catch Exception _ [])))

(defn- pascal->snake
  "Convert PascalCase to snake_case: MyApp -> my_app, HTTPClient -> http_client."
  [s]
  (-> s
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1_$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1_$2")
      str/lower-case))

(defn- elixir-module->paths
  "Convert an Elixir module name to candidate file paths.
   MyApp.Accounts -> [\"lib/my_app/accounts.ex\" ...]"
  [module-name]
  (let [base (->> (str/split module-name #"\.")
                  (map pascal->snake)
                  (str/join "/"))]
    [(str "lib/" base ".ex")
     (str "test/" base ".exs")
     (str base ".ex")
     (str base ".exs")]))

(defmethod resolve-import :elixir [_ import-name _source-path all-paths]
  (let [candidates (elixir-module->paths import-name)
        direct     (first (filter all-paths candidates))]
    (or direct
        ;; Handle umbrella apps with subproject prefixes (apps/myapp/lib/...)
        (first (for [c candidates
                     p all-paths
                     :when (str/ends-with? p (str "/" c))]
                 p)))))

;; ---------------------------------------------------------------------------
;; Erlang — include directives
;; ---------------------------------------------------------------------------

(defmethod extract-imports :erlang [_ text]
  (->> (re-seq #"(?m)^-(?:include|include_lib)\([\"']([^)\"']+)[\"']\)" text)
       (mapv second)))

(defn- resolve-erlang-import [import-name all-paths]
  (first (filter all-paths [import-name
                            (str "src/" import-name)])))

(defmethod resolve-import :erlang [_ import-name _source-path all-paths]
  (resolve-erlang-import import-name all-paths))

;; ---------------------------------------------------------------------------
;; Go — go list -json
;; ---------------------------------------------------------------------------

(defmethod extract-imports :go [_ _text]
  ;; Go extraction happens at the package level via go list — return empty.
  [])

;; ---------------------------------------------------------------------------
;; Rust — mod declarations
;; ---------------------------------------------------------------------------

(defmethod extract-imports :rust [_ text]
  (->> (re-seq #"(?m)^\s*(?:pub\s+)?mod\s+(\w+)\s*;" text)
       (mapv second)))

(defn- resolve-rust-mod [mod-name source-path all-paths]
  (let [dir (str/join "/" (butlast (str/split source-path #"/")))]
    (first (filter all-paths [(str dir "/" mod-name ".rs")
                              (str dir "/" mod-name "/mod.rs")]))))

(defmethod resolve-import :rust [_ import-name source-path all-paths]
  (resolve-rust-mod import-name source-path all-paths))

;; ---------------------------------------------------------------------------
;; Java — import statements
;; ---------------------------------------------------------------------------

(defmethod extract-imports :java [_ text]
  (->> (re-seq #"(?m)^import\s+([\w.]+)\s*;" text)
       (mapv second)))

(defn- resolve-java-import [import-name all-paths]
  (let [path (str (str/replace import-name "." "/") ".java")]
    (or (all-paths path)
        ;; Handle Maven/Gradle layout: src/main/java/..., src/test/java/...
        (first (for [p all-paths :when (str/ends-with? p (str "/" path))] p)))))

(defmethod resolve-import :java [_ import-name _source-path all-paths]
  (resolve-java-import import-name all-paths))

;; ---------------------------------------------------------------------------
;; C# — using directives
;; ---------------------------------------------------------------------------

(defmethod extract-imports :csharp [_ text]
  (->> (re-seq #"(?m)^\s*(?:global\s+)?using\s+(?:static\s+)?([\w.]+)\s*;" text)
       (mapv second)))

(defn- resolve-csharp-import
  "Resolve a C# using directive to repo files. C# namespaces map to directories
   or individual files, so we try: exact .cs match, then suffix .cs match, then
   all .cs files under the namespace-as-directory."
  [import-name all-paths]
  (when-not (re-matches #"(?:System|Microsoft)\..*" import-name)
    (let [as-path (str/replace import-name "." "/")
          exact   (str as-path ".cs")
          suffix  (str "/" as-path ".cs")
          dir-pfx (str as-path "/")]
      (or (all-paths exact)
          (first (for [p all-paths :when (str/ends-with? p suffix)] p))
          ;; Namespace → directory: find .cs files under that path
          (first (for [p all-paths
                       :when (and (str/ends-with? p ".cs")
                                  (or (str/starts-with? p dir-pfx)
                                      (let [idx (.indexOf ^String p (str "/" dir-pfx))]
                                        (pos? idx))))]
                   p))))))

(defmethod resolve-import :csharp [_ import-name _source-path all-paths]
  (resolve-csharp-import import-name all-paths))

;; ---------------------------------------------------------------------------
;; MSBuild — .csproj / .vcxproj project files
;; ---------------------------------------------------------------------------

(defn- normalize-msbuild-path
  "Normalize an MSBuild Include path relative to the source file's directory."
  [ref-path source-path]
  (let [ref-path  (str/replace ref-path "\\" "/")
        source-dir (str/join "/" (butlast (str/split source-path #"/")))]
    (loop [parts (str/split (if (seq source-dir)
                              (str source-dir "/" ref-path)
                              ref-path)
                            #"/")
           acc []]
      (if-let [part (first parts)]
        (cond
          (= ".." part) (recur (rest parts) (if (seq acc) (pop acc) acc))
          (= "." part)  (recur (rest parts) acc)
          :else         (recur (rest parts) (conj acc part)))
        (str/join "/" acc)))))

(defmethod extract-imports :msbuild-project [_ text]
  (->> (re-seq #"(?i)<(?:ProjectReference|ClInclude|ClCompile)\s+Include=\"([^\"]+)\"" text)
       (mapv second)))

(defmethod resolve-import :msbuild-project [_ import-name source-path all-paths]
  (let [resolved (normalize-msbuild-path import-name source-path)]
    (all-paths resolved)))

;; ---------------------------------------------------------------------------
;; MSBuild — .sln solution files
;; ---------------------------------------------------------------------------

(defmethod extract-imports :msbuild-solution [_ text]
  (->> (re-seq #"(?m)^Project\(.+?\)\s*=\s*\"[^\"]*\",\s*\"([^\"]+)\"" text)
       (mapv second)))

(defmethod resolve-import :msbuild-solution [_ import-name source-path all-paths]
  (let [resolved (normalize-msbuild-path import-name source-path)]
    (all-paths resolved)))

;; ---------------------------------------------------------------------------
;; Pure core — enrich a single file
;; ---------------------------------------------------------------------------

(defn enrich-file
  "Parse imports from source text and resolve to repo file paths.
   Returns {:resolved [file-paths] :raw [import-names]}."
  [lang content source-path all-paths]
  (let [raw-imports (extract-imports lang content)
        resolved    (->> raw-imports
                         (keep #(resolve-import lang % source-path all-paths))
                         (remove #{source-path})
                         distinct
                         vec)]
    {:resolved resolved
     :raw      (distinct (vec raw-imports))}))

;; ---------------------------------------------------------------------------
;; Impure shell — orchestrate enrichment for a repo
;; ---------------------------------------------------------------------------

(defn- files-with-lang
  "Query all file entities that have a :file/lang.  Excludes sensitive files."
  [db]
  (let [candidates (->> (d/q '[:find ?path ?lang
                               :where
                               [?e :file/path ?path]
                               [?e :file/lang ?lang]]
                             db)
                        (mapv (fn [[path lang]] {:file/path path :file/lang lang})))
        {sensitive true safe false} (group-by #(files/sensitive-path? (:file/path %))
                                              candidates)]
    (when (seq sensitive)
      (log! (str "Skipping " (count sensitive) " sensitive file(s) from enrichment")))
    (sort-by :file/path safe)))

(defn- file->tx-data
  "Build tx-data for one file's resolved imports and raw dependency names."
  [file-path {:keys [resolved raw]}]
  (when (or (seq resolved) (seq raw))
    (cond-> {:file/path file-path}
      (seq resolved) (assoc :file/imports (mapv (fn [p] [:file/path p]) resolved))
      (seq raw)      (assoc :sem/dependencies raw))))

(def ^:private batch-size 50)

(defn- flush-batch!
  "Transact a batch of tx-data maps if non-empty. Returns empty vector."
  [conn batch]
  (when (seq batch)
    (d/transact conn {:tx-data (conj batch {:db/id "datomic.tx"
                                            :tx/op :enrich
                                            :tx/source :deterministic})}))
  [])

(defn- extract-one
  "Extract and resolve imports for a single file. Returns tx-data map or nil."
  [repo-path all-paths {:keys [file/path file/lang]}]
  (try
    (let [content (analyze/git-show repo-path path)
          result  (enrich-file lang content path all-paths)]
      (file->tx-data path result))
    (catch Exception _
      {:error? true :file/path path})))

(defn- extract-one-c
  "Extract includes for a C/C++ file via compiler. Returns tx-data map or nil."
  [repo-path all-paths {:keys [file/path]}]
  (try
    (when-let [deps (extract-c-includes-from-compiler repo-path path)]
      (let [resolved (->> deps (filter all-paths) (remove #{path}) distinct vec)]
        (file->tx-data path {:resolved resolved :raw (vec deps)})))
    (catch Exception _
      {:error? true :file/path path})))

(def ^:private no-imports
  "Sentinel for files with no imports (nil cannot traverse core.async channels)."
  ::no-imports)

(def ^:private progress-interval
  "Log progress every N files."
  500)

(defn- with-progress
  "Wrap extract-fn to log periodic progress. Returns wrapped fn."
  [extract-fn total]
  (let [counter (atom 0)]
    (fn [file]
      (let [result (extract-fn file)
            n      (swap! counter inc)]
        (when (zero? (mod n progress-interval))
          (log! (str "  [" n "/" total "] extracting imports...")))
        result))))

(defn- run-extraction
  "Run extract-fn over files with bounded concurrency. Returns vec of results."
  [extract-fn files concurrency]
  (let [total (count files)
        f     (if (> total progress-interval)
                (with-progress extract-fn total)
                extract-fn)]
    (if (<= concurrency 1)
      (mapv f files)
      (let [in-ch  (async/to-chan! (vec files))
            out-ch (async/chan (max 1 total))]
        (async/pipeline-blocking
         concurrency out-ch
         (map #(or (f %) no-imports))
         in-ch)
        (loop [results (transient [])]
          (if-let [v (async/<!! out-ch)]
            (recur (conj! results (when-not (identical? v no-imports) v)))
            (persistent! results)))))))

(defn- extract-all
  "Extract imports for standard (non-C) files. Returns vec of results."
  [repo-path all-paths files concurrency]
  (run-extraction #(extract-one repo-path all-paths %) files concurrency))

(defn- extract-all-c
  "Extract includes for C/C++ files via compiler. Returns vec of results."
  [repo-path all-paths files concurrency]
  (run-extraction #(extract-one-c repo-path all-paths %) files concurrency))

(defn- tally-and-transact!
  "Tally results and transact in batches. Returns summary map."
  [conn results]
  (reduce (fn [{:keys [batch] :as acc} result]
            (cond
              (nil? result)
              (update acc :files-processed inc)

              (:error? result)
              (-> acc
                  (update :files-processed inc)
                  (update :files-errored inc))

              :else
              (let [batch' (conj batch result)
                    acc'   (-> acc
                               (update :files-processed inc)
                               (update :imports-resolved + (count (:file/imports result))))]
                (if (>= (count batch') batch-size)
                  (assoc acc' :batch (flush-batch! conn batch'))
                  (assoc acc' :batch batch')))))
          {:files-processed 0 :imports-resolved 0
           :files-skipped 0 :files-errored 0 :batch []}
          results))

(defn- partition-files-by-lang
  "Split files into standard and C/C++ groups, filtering unavailable tool langs."
  [all-files tools]
  (let [c-langs    #{:c :cpp}
        needs-tool #{:python :javascript :typescript :elixir :go}
        c-files    (filter (comp c-langs :file/lang) all-files)
        std-files  (->> (remove (comp c-langs :file/lang) all-files)
                        (remove (fn [{:keys [file/lang]}]
                                  (and (needs-tool lang)
                                       (not (get-in tools [lang :available?]))))))]
    {:std-files std-files :c-files c-files}))

(defn- log-enrich-summary!
  "Log the enrichment summary and skipped-tool warnings."
  [{:keys [files-processed imports-resolved files-errored]} concurrency skipped-tools]
  (log! (str "  Enriched " files-processed " files, "
             imports-resolved " import edges resolved"
             (when (> concurrency 1) (str " (concurrency=" concurrency ")"))
             (when (pos? files-errored) (str ", " files-errored " errors"))))
  (let [files-skipped (reduce + (map :file-count skipped-tools))]
    (when (pos? files-skipped)
      (log! (str "  Warning: " files-skipped " files skipped because "
                 (str/join ", " (map (fn [{:keys [tool]}] (str tool " is not on PATH"))
                                     skipped-tools))
                 ". Install and re-run enrich to resolve their imports.")))
    files-skipped))

(defn enrich-repo!
  "Extract cross-file import graph deterministically and transact into Datomic.
   Returns a summary map. `opts` may include :concurrency (default 8)."
  ([conn repo-path] (enrich-repo! conn repo-path {}))
  ([conn repo-path {:keys [concurrency] :or {concurrency 8}}]
   (let [db        (d/db conn)
         all-files (files-with-lang db)
         all-paths (into #{} (map :file/path) all-files)
         tools     (probe-tools)
         skipped-tools (log-tool-availability! tools (frequencies (map :file/lang all-files)))
         {:keys [std-files c-files]} (partition-files-by-lang all-files tools)
         total     (+ (count std-files) (count c-files))
         _         (log! (str "  Extracting imports from " total " files"
                              (when (> concurrency 1) (str " (concurrency=" concurrency ")"))
                              "..."))
         std-results (extract-all repo-path all-paths std-files concurrency)
         c-results   (when (and (seq c-files) (get-in tools [:c :available?]))
                       (extract-all-c repo-path all-paths c-files concurrency))
         final       (-> (tally-and-transact! conn (into std-results c-results))
                         (update :batch #(flush-batch! conn %)))]
     (when (zero? (:imports-resolved final))
       (d/transact conn {:tx-data [{:db/id "datomic.tx"
                                    :tx/op :enrich
                                    :tx/source :deterministic}]}))
     (let [files-skipped (log-enrich-summary! final concurrency skipped-tools)]
       (-> final
           (dissoc :batch)
           (assoc :files-skipped files-skipped :skipped-tools skipped-tools))))))

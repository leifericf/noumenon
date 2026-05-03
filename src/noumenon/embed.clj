(ns noumenon.embed
  "TF-IDF vector search for semantic file/component discovery.
   Builds a vocabulary from analyzed summaries, computes IDF weights,
   and produces per-document vectors for cosine similarity search.
   Index is persisted as a Nippy file alongside the Datomic database."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [noumenon.util :refer [log!]]
            [taoensso.nippy :as nippy])
  (:import [java.io File]))

;; --- Tokenizer ---

(def ^:private stopwords
  #{"a" "an" "the" "and" "or" "but" "in" "on" "at" "to" "for" "of"
    "with" "by" "from" "is" "are" "was" "were" "be" "been" "being"
    "have" "has" "had" "do" "does" "did" "will" "would" "could"
    "should" "may" "might" "can" "this" "that" "these" "those"
    "it" "its" "not" "no" "if" "then" "else" "when" "which" "what"
    "who" "how" "all" "each" "every" "both" "few" "more" "most"
    "other" "some" "such" "than" "too" "very" "just" "also" "into"
    "over" "after" "before" "between" "through" "during" "about"
    "as" "so" "up" "out" "only" "own" "same" "any"})

(defn tokenize
  "Split text into lowercase tokens, removing stopwords and short tokens."
  [text]
  (->> (re-seq #"[a-z0-9_-]+" (str/lower-case (str text)))
       (remove stopwords)
       (remove #(< (count %) 2))
       vec))

;; --- TF-IDF ---

(def ^:private default-max-vocab 2000)

(defn build-vocab
  "Build vocabulary from token lists. Returns {token → index}."
  ([token-lists] (build-vocab token-lists default-max-vocab))
  ([token-lists max-size]
   (->> (mapcat identity token-lists)
        frequencies
        (sort-by val >)
        (take max-size)
        (map-indexed (fn [i [w _]] [w i]))
        (into {}))))

(defn compute-idf
  "IDF = log((1 + N) / (1 + df)). Smooth variant that avoids zero IDF
   for terms appearing in all documents. Returns double-array."
  ^doubles [vocab token-lists]
  (let [n   (count token-lists)
        idf (double-array (count vocab))]
    (doseq [tokens (map set token-lists)
            token  tokens
            :let   [idx (vocab token)]
            :when  idx]
      (aset idf (int idx) (+ (aget idf (int idx)) 1.0)))
    (dotimes [i (alength idf)]
      (aset idf i (Math/log (/ (+ 1.0 (double n)) (+ 1.0 (aget idf i))))))
    idf))

(defn tfidf-vec
  "TF-IDF vector for a single document. Returns double-array."
  ^doubles [vocab ^doubles idf tokens]
  (let [v  (double-array (count vocab))
        tf (frequencies tokens)
        n  (max 1 (count tokens))]
    (doseq [[token freq] tf
            :let [idx (vocab token)]
            :when idx]
      (aset v (int idx) (* (/ (double freq) n) (aget idf (int idx)))))
    v))

;; --- Vector math ---

(defn cosine-similarity
  "Cosine similarity between two double-arrays. Returns 0.0–1.0."
  ^double [^doubles a ^doubles b]
  (let [n (alength a)]
    (loop [i 0, dot 0.0, na 0.0, nb 0.0]
      (if (>= i n)
        (let [denom (* (Math/sqrt na) (Math/sqrt nb))]
          (if (zero? denom) 0.0 (/ dot denom)))
        (let [ai (aget a i) bi (aget b i)]
          (recur (inc i)
                 (+ dot (* ai bi))
                 (+ na (* ai ai))
                 (+ nb (* bi bi))))))))

(defn top-k
  "Find the k entries most similar to query-vec. Returns seq of entries with :score."
  [query-vec entries k]
  (->> entries
       (map #(assoc % :score (cosine-similarity query-vec (:vec %))))
       (sort-by :score >)
       (take k)))

;; --- Persistence ---

(def ^:private index-filename "tfidf-index.nippy")

(defn index-path
  "Path to the TF-IDF index file for a given db directory."
  ^String [db-dir db-name]
  (.getAbsolutePath (File. (str db-dir "/noumenon/" db-name) index-filename)))

(defn save-index!
  "Serialize the TF-IDF index to a Nippy file."
  [index path]
  (nippy/freeze-to-file path index)
  (log! (str "embed: saved index to " path
             " (" (count (:entries index)) " entries, "
             (count (:vocab index)) " vocab terms)")))

(defn load-index
  "Deserialize a TF-IDF index from a Nippy file. Returns nil if not found."
  [path]
  (let [f (File. ^String path)]
    (when (.exists f)
      (nippy/thaw-from-file path))))

;; --- Index cache ---

(defonce ^:private index-cache (atom {}))

(defn get-cached-index
  "Load index from cache or disk. Returns nil if no index exists."
  [db-dir db-name]
  (let [path (index-path db-dir db-name)]
    (or (get @index-cache path)
        (when-let [idx (load-index path)]
          (swap! index-cache assoc path idx)
          idx))))

(defn invalidate-cache!
  "Clear the cached index for a db, forcing reload on next access."
  [db-dir db-name]
  (swap! index-cache dissoc (index-path db-dir db-name)))

(defn release-cache!
  "Drop every cached TF-IDF index. Called on daemon shutdown."
  []
  (reset! index-cache {}))

;; --- Index builder ---

(defn- query-file-texts
  "Query all analyzed files with summaries. Returns [{:path :text}]."
  [db]
  (->> (d/q '[:find ?path ?summary ?purpose
              :where
              [?e :file/path ?path]
              [?e :sem/summary ?summary]
              [(get-else $ ?e :sem/purpose "") ?purpose]]
            db)
       (mapv (fn [[path summary purpose]]
               {:kind :file
                :path path
                :text (str summary " " purpose)}))))

(defn- query-component-texts
  "Query all synthesized components with summaries. Returns [{:name :text}]."
  [db]
  (->> (d/q '[:find ?name ?summary ?purpose
              :where
              [?e :component/name ?name]
              [?e :component/summary ?summary]
              [(get-else $ ?e :component/purpose "") ?purpose]]
            db)
       (mapv (fn [[name summary purpose]]
               {:kind :component
                :name name
                :text (str summary " " purpose)}))))

(defn build-index
  "Build a TF-IDF index from analyzed files and synthesized components.
   Returns the index map (does not save to disk)."
  [db]
  (let [files      (query-file-texts db)
        components (query-component-texts db)
        docs       (into files components)
        _          (when (empty? docs)
                     (log! "embed: no analyzed files or components found — index will be empty"))
        token-lists (mapv #(tokenize (:text %)) docs)
        vocab       (build-vocab token-lists)
        idf         (compute-idf vocab token-lists)
        entries     (mapv (fn [doc tokens]
                            (assoc doc :vec (tfidf-vec vocab idf tokens)))
                          docs token-lists)]
    {:vocab   vocab
     :idf     idf
     :entries entries}))

(defn build-index!
  "Build TF-IDF index and save to disk. Returns the index."
  [db db-dir db-name]
  (let [idx  (build-index db)
        path (index-path db-dir db-name)]
    (.mkdirs (.getParentFile (File. ^String path)))
    (save-index! idx path)
    (invalidate-cache! db-dir db-name)
    idx))

;; --- Search ---

(defn search
  "Search the index for entries similar to a query string.
   Returns [{:kind :path/:name :text :score}] sorted by score descending."
  [index query-text & {:keys [limit] :or {limit 8}}]
  (when (and index (seq (:entries index)))
    (let [tokens    (tokenize query-text)
          query-vec (tfidf-vec (:vocab index) (:idf index) tokens)]
      (->> (top-k query-vec (:entries index) limit)
           (mapv #(dissoc % :vec))))))

(ns noumenon.ui.graph.data
  "Transform query results into graph nodes and edges for d3-force.")

(def layer-colors
  {:core      "#60a5fa"
   :subsystem "#34d399"
   :driver    "#fbbf24"
   :api       "#f472b6"
   :util      "#94a3b8"
   nil        "#334155"})

(defn- file->node [file-path {:keys [layers churn]}]
  {:id    file-path
   :group (get layers file-path)
   :size  (get churn file-path 1)
   :color (get layer-colors (get layers file-path))})

(defn build-nodes
  "Build node list from hotspots and layer data.
   hotspots: [[path count] ...]
   layers: [[path layer-kw] ...]"
  [hotspots layers]
  (let [churn-map  (into {} (map (fn [row] [(first row) (second row)])) hotspots)
        layer-map  (into {} (map (fn [row] [(first row) (keyword (second row))])) layers)
        all-files  (into (set (keys churn-map)) (keys layer-map))]
    (->> all-files
         (mapv #(file->node % {:layers layer-map :churn churn-map})))))

(defn build-edges
  "Build edge list from import pairs.
   imports: [[source target] ...]"
  [imports]
  (->> imports
       (mapv (fn [row] {:source (first row) :target (second row)}))))

(defn build-co-change-edges
  "Build edges from co-change data.
   co-changes: [[file1 file2 count] ...]"
  [co-changes]
  (->> co-changes
       (mapv (fn [row] {:source (first row)
                        :target (second row)
                        :weight (nth row 2 1)}))))

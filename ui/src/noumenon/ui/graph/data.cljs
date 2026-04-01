(ns noumenon.ui.graph.data
  "Transform query results into graph nodes and edges for d3-force.
   Returns lazy sequences — realized to vectors at simulation boundary via clj->js."
  (:require [clojure.string :as str]))

(def layer-colors
  {:core      "#7b9abf"   ;; muted steel blue
   :subsystem "#6b9e8a"   ;; muted sage
   :driver    "#b8a46e"   ;; muted gold
   :api       "#a87d9e"   ;; muted mauve
   :util      "#7a8694"   ;; slate
   nil        "#4a5568"}) ;; dark slate

(def quality-colors
  {:clean  "#6b9e8a"   ;; muted sage
   :smelly "#b8a46e"   ;; muted gold
   :unsafe "#b07070"}) ;; muted rose

;; --- Lookup-map builders (pure, no caching — inputs are fresh each call) ---

(defn- ->churn-map [rows]
  (into {} (map (juxt first second)) rows))

(defn- ->smells-map [rows]
  (reduce (fn [m [name smell]]
            (update m name (fnil conj #{}) (keyword smell)))
          {} rows))

(defn- ->safety-map [rows]
  (reduce (fn [m [name concern]]
            (update m name (fnil conj #{}) (keyword concern)))
          {} rows))

;; --- File-level builders (existing, unchanged) ---

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

;; --- Component-level builders ---

(defn build-component-nodes
  "Lazy seq of component nodes from query results.
   components: [[name summary layer category complexity file-count] ...]
   churn:      [[name commit-count] ...]"
  [components churn]
  (let [cm (->churn-map churn)]
    (map (fn [[name summary layer category complexity file-count]]
           (let [layer-kw (keyword layer)]
             {:id         name
              :type       :component
              :group      layer-kw
              :size       (or (cm name) file-count 1)
              :file-count file-count
              :color      (layer-colors layer-kw (layer-colors nil))
              :label      name
              :summary    summary
              :category   (keyword category)
              :complexity (keyword complexity)}))
         components)))

(defn build-component-edges
  "Lazy seq of edges from cross-component-imports.
   cross-imports: [[from-comp to-comp count] ...]"
  [cross-imports]
  (map (fn [[from to weight]] {:source from :target to :weight (or weight 1)})
       cross-imports))

;; --- File-cluster builders (level 2) ---

(defn build-file-cluster-nodes
  "Lazy seq of file nodes for a component cluster.
   comp-files: [[path layer complexity] ...]
   churn-map:  {path -> commit-count}
   cx, cy:     parent component's position"
  [comp-files churn-map cx cy]
  (map (fn [[path layer complexity]]
         (let [layer-kw (keyword layer)]
           {:id         path
            :type       :file
            :group      layer-kw
            :size       (get churn-map path 1)
            :color      (layer-colors layer-kw (layer-colors nil))
            :complexity (keyword (or complexity :unknown))
            :x          (+ cx (* (- (rand) 0.5) 20))
            :y          (+ cy (* (- (rand) 0.5) 20))}))
       comp-files))

;; --- Segment-level builders (level 3) ---

(defn- segment-quality [smells safety]
  (cond (seq safety) :unsafe (seq smells) :smelly :else :clean))

(defn build-segment-nodes
  "Lazy seq of segment nodes for a file cluster.
   segments:    [[name kind line-start line-end complexity visibility
                  pure? ai-likelihood purpose error-handling args returns] ...]
   smell-rows:  [[name smell] ...]
   safety-rows: [[name concern] ...]
   cx, cy:      parent file's position"
  [segments smell-rows safety-rows cx cy]
  (let [sm (->smells-map smell-rows)
        sf (->safety-map safety-rows)]
    (map (fn [[name kind line-start line-end complexity visibility
               pure? ai-lhood purpose err-handl args returns]]
           (let [smells  (sm name #{})
                 safety  (sf name #{})
                 quality (segment-quality smells safety)
                 span    (max 1 (- (or line-end 0) (or line-start 0)))]
             {:id             (str name)
              :type           :segment
              :kind           (keyword kind)
              :size           (Math/sqrt span)
              :color          (quality-colors quality)
              :quality        quality
              :complexity     (keyword complexity)
              :visibility     (keyword visibility)
              :pure?          pure?
              :ai-likelihood  (keyword ai-lhood)
              :purpose        purpose
              :error-handling (keyword err-handl)
              :args           args
              :returns        returns
              :smells         smells
              :safety         safety
              :line-start     line-start
              :line-end       line-end
              :x              (+ cx (* (- (rand) 0.5) 15))
              :y              (+ cy (* (- (rand) 0.5) 15))}))
         segments)))

(defn build-segment-edges
  "Lazy filtered seq of intra-file call edges.
   calls: [[caller-name callee-name] ...]
   segment-names: set of names present in the segment list"
  [calls segment-names]
  (keep (fn [[caller callee]]
          (when (and (segment-names caller) (segment-names callee))
            {:source caller :target callee}))
        calls))

;; --- People overlay ---

(defn build-people-nodes
  "Author icon data positioned at fixed offsets around parent.
   authors:   [[name commit-count] ...]
   px, py:    parent node position
   max-n:     how many to show (2-3 for components, 1-2 for files)"
  [authors px py max-n]
  (let [top   (->> authors (sort-by second >) (take max-n))
        step  (/ (* 2 Math/PI) (max 1 (count top)))]
    (map-indexed
     (fn [i [name commits]]
       (let [angle  (* i step)
             radius 35
             initials (-> name (str/split #"\s+") (->> (map first) (apply str))
                          .toUpperCase (subs 0 (min 2 (count name))))]
         {:id      (str "person:" name)
          :type    :person
          :label   initials
          :name    name
          :commits commits
          :x       (+ px (* radius (Math/cos angle)))
          :y       (+ py (* radius (Math/sin angle)))}))
     top)))

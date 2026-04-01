(ns noumenon.ui.graph-data-test
  (:require [cljs.test :refer [deftest is testing]]
            [noumenon.ui.graph.data :as gdata]))

(deftest build-nodes-from-hotspots-and-layers
  (let [hotspots [["src/a.clj" 10] ["src/b.clj" 3]]
        layers   [["src/a.clj" "core"] ["src/b.clj" "util"]]
        nodes    (gdata/build-nodes hotspots layers)]
    (is (= 2 (count nodes)))
    (let [node-a (->> nodes (filter #(= "src/a.clj" (:id %))) first)]
      (is (= :core (:group node-a)))
      (is (= 10 (:size node-a)))
      (is (= "#60a5fa" (:color node-a))))))

(deftest build-nodes-merges-files-from-both-sources
  (let [hotspots [["src/a.clj" 5]]
        layers   [["src/b.clj" "api"]]
        nodes    (gdata/build-nodes hotspots layers)]
    (is (= 2 (count nodes)))
    (is (= 1 (:size (->> nodes (filter #(= "src/b.clj" (:id %))) first))))
    (is (= :api (:group (->> nodes (filter #(= "src/b.clj" (:id %))) first))))))

(deftest build-nodes-unknown-layer-gets-nil-color
  (let [nodes (gdata/build-nodes [["x.clj" 1]] [])]
    (is (= "#334155" (:color (first nodes))))))

(deftest build-edges-from-import-pairs
  (let [imports [["a.clj" "b.clj"] ["b.clj" "c.clj"]]
        edges   (gdata/build-edges imports)]
    (is (= 2 (count edges)))
    (is (= "a.clj" (:source (first edges))))
    (is (= "b.clj" (:target (first edges))))))

(deftest build-edges-empty
  (is (= [] (gdata/build-edges []))))

(deftest build-co-change-edges
  (let [data  [["a.clj" "b.clj" 5] ["c.clj" "d.clj" 2]]
        edges (gdata/build-co-change-edges data)]
    (is (= 2 (count edges)))
    (is (= 5 (:weight (first edges))))))

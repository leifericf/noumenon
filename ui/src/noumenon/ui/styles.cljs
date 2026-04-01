(ns noumenon.ui.styles
  (:require [garden.core :as garden]))

(def ^:private dark-tokens
  {:bg-primary   "#0d1117"
   :bg-secondary "#161b22"
   :bg-tertiary  "#21262d"
   :text-primary "#e6edf3"
   :text-secondary "#8b949e"
   :text-muted   "#484f58"
   :accent       "#58a6ff"
   :accent-hover "#79c0ff"
   :border       "#30363d"
   :danger       "#f85149"
   :success      "#3fb950"
   :warning      "#d29922"
   :radius       "6px"
   :font-mono    "'SF Mono', 'Fira Code', 'Cascadia Code', monospace"})

(def ^:private light-tokens
  {:bg-primary   "#ffffff"
   :bg-secondary "#f6f8fa"
   :bg-tertiary  "#eaeef2"
   :text-primary "#1f2328"
   :text-secondary "#656d76"
   :text-muted   "#8b949e"
   :accent       "#0969da"
   :accent-hover "#0550ae"
   :border       "#d0d7de"
   :danger       "#cf222e"
   :success      "#1a7f37"
   :warning      "#9a6700"
   :radius       "6px"
   :font-mono    "'SF Mono', 'Fira Code', 'Cascadia Code', monospace"})

;; Mutable — reset! when theme changes, components read at render time
(defonce ^:private tokens-atom (atom dark-tokens))

;; Components use this. It's a plain deref at render time.
(def tokens
  "Current theme tokens. Deref of an atom — always returns the active theme."
  (reify IDeref
    (-deref [_] @tokens-atom)
    ILookup
    (-lookup [_ k] (get @tokens-atom k))
    (-lookup [_ k nf] (get @tokens-atom k nf))))

(defn set-theme! [theme]
  (reset! tokens-atom (if (= theme :light) light-tokens dark-tokens)))

(defn current-theme []
  (if (= @tokens-atom dark-tokens) :dark :light))

(defn- build-global-styles [t]
  [[:html :body {:height "100%"
                 :background (:bg-primary t)
                 :color (:text-primary t)
                 :font-family "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
                 :font-size "14px"
                 :line-height "1.5"}]
   [:#app {:height "100%"}]
   [:code {:font-family (:font-mono t)
           :font-size "13px"}]
   [:a {:color (:accent t)
        :text-decoration "none"}
    [:&:hover {:text-decoration "underline"}]]
   ["input:focus, select:focus, textarea:focus, button:focus-visible"
    {:outline "none"
     :box-shadow (str "0 0 0 2px " (:accent t) "40")}]])

(defn inject-styles! []
  (let [el (or (.getElementById js/document "noumenon-styles")
               (let [s (.createElement js/document "style")]
                 (set! (.-id s) "noumenon-styles")
                 (.appendChild (.-head js/document) s)
                 s))]
    (set! (.-textContent el) (garden/css (build-global-styles @tokens-atom)))))

(ns noumenon.ui.portfolio.scenes.markdown-scenes
  (:require [portfolio.replicant :as portfolio]
            [noumenon.ui.components.markdown :as md]))

(portfolio/defscene heading-and-text
  (md/render-markdown "# Title\n\nSome paragraph text.\n\n## Subtitle\n\nMore content here."))

(portfolio/defscene code-block
  (md/render-markdown "Here is some code:\n\n```clojure\n(defn hello [name]\n  (println \"Hello\" name))\n```\n\nAnd some text after."))

(portfolio/defscene inline-code
  (md/render-markdown "Use `defn` to define functions and `let` for local bindings."))

(portfolio/defscene list-items
  (md/render-markdown "Key findings:\n\n- File A has high churn\n- File B is tightly coupled\n- File C needs refactoring"))

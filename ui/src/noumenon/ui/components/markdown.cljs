(ns noumenon.ui.components.markdown
  (:require [clojure.string :as str]
            [noumenon.ui.styles :as styles]))

(defn- code-block [lang content]
  [:pre {:style {:background (:bg-tertiary styles/tokens)
                 :border (str "1px solid " (:border styles/tokens))
                 :border-radius (:radius styles/tokens)
                 :padding "12px 16px"
                 :overflow-x "auto"
                 :margin "8px 0"}}
   (when (seq lang)
     [:div {:style {:font-size "11px"
                    :color (:text-muted styles/tokens)
                    :margin-bottom "4px"
                    :text-transform "uppercase"}}
      lang])
   [:code {:style {:font-family (:font-mono styles/tokens)
                   :font-size "13px"
                   :color (:text-primary styles/tokens)}}
    content]])

(defn- inline-code [text]
  [:code {:style {:background (:bg-tertiary styles/tokens)
                  :padding "2px 6px"
                  :border-radius "3px"
                  :font-family (:font-mono styles/tokens)
                  :font-size "13px"}}
   text])

(defn- process-inline
  "Process inline markdown (bold, italic, inline code)."
  [text]
  (if (string? text)
    (let [parts (str/split text #"(`[^`]+`)")]
      (if (= 1 (count parts))
        text
        (for [part parts]
          (if (and (str/starts-with? part "`")
                   (str/ends-with? part "`"))
            (inline-code (subs part 1 (dec (count part))))
            part))))
    text))

(defn- parse-line [line]
  (cond
    (str/starts-with? line "### ") [:h3 {:style {:margin "16px 0 8px"
                                                 :font-size "15px"}}
                                    (subs line 4)]
    (str/starts-with? line "## ")  [:h2 {:style {:margin "16px 0 8px"
                                                 :font-size "17px"}}
                                    (subs line 3)]
    (str/starts-with? line "# ")   [:h1 {:style {:margin "16px 0 8px"
                                                 :font-size "20px"}}
                                    (subs line 2)]
    (str/starts-with? line "- ")   [:li {:style {:margin-left "16px"
                                                 :margin-bottom "4px"}}
                                    (process-inline (subs line 2))]
    (str/starts-with? line "* ")   [:li {:style {:margin-left "16px"
                                                 :margin-bottom "4px"}}
                                    (process-inline (subs line 2))]
    (str/blank? line)              [:br]
    :else                          [:p {:style {:margin "4px 0"}}
                                    (process-inline line)]))

(defn render-markdown
  "Simple markdown to hiccup. Handles headers, code blocks, lists, inline code."
  [text]
  (when (seq text)
    (let [lines (str/split-lines text)]
      (into [:div {:style {:line-height "1.6"}}]
            (loop [ls lines
                   result []
                   in-code? false
                   code-lang nil
                   code-lines []]
              (if (empty? ls)
                (if in-code?
                  (conj result (code-block code-lang (str/join "\n" code-lines)))
                  result)
                (let [line (first ls)]
                  (cond
               ;; Start of code block
                    (and (not in-code?) (str/starts-with? line "```"))
                    (recur (rest ls) result true (subs line 3) [])

               ;; End of code block
                    (and in-code? (= line "```"))
                    (recur (rest ls)
                           (conj result (code-block code-lang (str/join "\n" code-lines)))
                           false nil [])

               ;; Inside code block
                    in-code?
                    (recur (rest ls) result true code-lang (conj code-lines line))

               ;; Normal line
                    :else
                    (recur (rest ls) (conj result (parse-line line))
                           false nil [])))))))))

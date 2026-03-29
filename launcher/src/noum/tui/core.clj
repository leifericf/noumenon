(ns noum.tui.core
  "TTY detection and output dispatch.")

(defn interactive?
  "True when running in an interactive terminal (not piped, not CI)."
  []
  (and (some? (System/console))
       (nil? (System/getenv "CI"))
       (nil? (System/getenv "NOUM_NON_INTERACTIVE"))))

(defn eprint
  "Print to stderr (human-facing output)."
  [& args]
  (binding [*out* *err*]
    (apply print args)
    (flush)))

(defn eprintln
  "Println to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

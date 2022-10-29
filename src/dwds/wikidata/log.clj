(ns dwds.wikidata.log
  (:require [taoensso.timbre :as log]))

(defn configure!
  [trace?]
  (let [stderr-appender (log/println-appender {:stream :std-err})]
    (log/merge-config!
     {:min-level (if trace? :trace :debug)
      :appenders {:println stderr-appender}})))

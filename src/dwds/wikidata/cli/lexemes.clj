(ns dwds.wikidata.cli.lexemes
  (:require [clojure.data.csv :as csv]
            [dwds.wikidata.dump :as dump]
            [dwds.wikidata.log]))

(defn lexeme->csv
  [{:keys [id lemmas]}]
  (for [[lang {lemma :value}] lemmas] [id (name lang) lemma]))

(defn -main
  [& _]
  (dwds.wikidata.log/configure! true)
  (csv/write-csv *out* (dump/lexemes (mapcat lexeme->csv))))

(ns dwds.wikidata.cli.lexemes
  (:require [clojure.data.csv :as csv]
            [dwds.wikidata.dump :as dump]))

(defn lexeme->csv
  [{:keys [id lemmas]}]
  (for [[lang {lemma :value}] lemmas] [id (name lang) lemma]))

(defn -main
  [& _]
  (csv/write-csv *out* (dump/lexemes (mapcat lexeme->csv))))

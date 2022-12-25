(ns dwds.wikidata.csv
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [dwds.wikidata.dump :as dump]
   [dwds.wikidata.log])
  (:import
   (java.io FileDescriptor FileOutputStream IOException)))

(defn lexeme->csv
  [{:keys [id lemmas]}]
  (for [[lang {lemma :value}] lemmas]
    [id (name lang) lemma]))

(defn -main
  [& _]
  (dwds.wikidata.log/configure! true)
  (try
    (with-open [in  (dump/lexemes-dump-reader)
                out (io/writer (FileOutputStream. FileDescriptor/out))]
      (csv/write-csv out (mapcat lexeme->csv (dump/parse-lexemes in))))
    (catch IOException _)
    (finally (System/exit 0))))

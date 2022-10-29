(ns dwds.wikidata.dump
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as http]
   [jsonista.core :as json]
   [taoensso.timbre :as log])
  (:import
   (java.util.zip GZIPInputStream)))

(def lexemes-dump-url
  "https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz")

(defn lexemes-dump-reader
  []
  (-> (http/get lexemes-dump-url {:as :stream})
      (get :body)
      GZIPInputStream.
      (io/reader :encoding "UTF-8")))

(defn read-json
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(defn log-lexeme
  [i lexeme]
  (when (zero? (mod (inc i) 1000))
    (log/tracef "Parsed lexeme from dump #%,7d" (inc i)))
  lexeme)

(def parse-lexeme-dump-xf
  (comp
   (map str/trim)
   (filter #(< 2 (count %)))
   (map #(str/replace % #",$" ""))
   (map read-json)
   (map-indexed log-lexeme)))

(defn lexemes
  ([]
   (lexemes (map identity)))
  ([xf]
   (with-open [rdr (lexemes-dump-reader)]
     (into [] (comp parse-lexeme-dump-xf xf) (line-seq rdr)))))

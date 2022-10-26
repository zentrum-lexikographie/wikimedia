(ns dwds.wikidata.wikipedia-corpus
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as http]
   [taoensso.timbre :as log])
  (:import
   (java.util.zip GZIPInputStream)))

(def de-texts-url
  "https://download.wmcloud.org/corpora/de.txt.gz")

(defn de-texts-reader
  []
  (-> (http/get de-texts-url {:as :stream})
      (get :body)
      GZIPInputStream.
      (io/reader :encoding "UTF-8")))

(defn clean-chars
  [l]
  (->
   l
   (str/replace #"[\.,_\(\)=\"]" " ")
   (str/replace #"\u00e2\u0080[\u009e\u009f]" " ")
   (str/replace #"\u00e0\u00a5[\u00a4\u00a5]" " ")
   (str/trim)))

(def skipped-tokens-resource
  (io/resource "dwds/wikidata/wikipedia_corpus_skipped.edn"))

(def skipped-token?
  (some-fn #{"" "NEWLINE"}
           #(re-seq #"^\d+$" %)
           (with-open [rdr (io/reader skipped-tokens-resource)]
             (read-string (slurp rdr)))))

(def batch-size
  10000)

(def article-count
  (atom 0))

(defn count-articles-xf
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result article]
     (when (zero? (mod (swap! article-count inc) batch-size))
       (log/debugf "Wikipedia-DE/ Articles tokenized: %,10d" @article-count))
     (rf result article))))

(def tokenizer-xf
  (comp
   count-articles-xf
   (map clean-chars)
   (mapcat #(str/split % #"[ \s]+"))
   (map str/lower-case)
   (remove skipped-token?)))

(def token-min-freq
  10)

(defn tokens
  []
  (with-open [de-texts (de-texts-reader)]
    (let [articles (take 1000 (line-seq de-texts))
          batches  (partition-all batch-size articles)
          batches  (pmap (partial into [] tokenizer-xf) batches)
          tokens   (mapcat identity batches)
          freqs    (frequencies tokens)]
      (into {} (filter (fn [[_ n]] (< token-min-freq n))) freqs))))

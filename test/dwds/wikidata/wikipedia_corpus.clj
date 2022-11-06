(ns dwds.wikidata.wikipedia-corpus
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [hato.client :as http]
   [taoensso.timbre :as log])
  (:import
   (java.io FilterInputStream IOException)
   (java.util.zip GZIPInputStream)))

(def de-texts-url
  "https://download.wmcloud.org/corpora/de.txt.gz")

(defn de-texts-response-stream
  "Wraps the HTTP response stream of the German corpus file.

  The HTTP server reports a wrong content length, resulting in instances of
  `EOFException` being thrown By wrapping the stream, those exceptions are
  ignored."
  [{response-stream :body}]
  (let [response-stream (GZIPInputStream. response-stream)]
    (proxy [FilterInputStream] [response-stream]
      (read
        ([]
         (try (.read response-stream) (catch IOException _ -1)))
        ([b]
         (try (.read response-stream b) (catch IOException _ -1)))
        ([buf off len]
         (try (.read response-stream buf off len) (catch IOException _ -1))))
      (close []
        (try (.close response-stream) (catch IOException _))))))

(defn de-texts-reader
  []
  (-> (http/get de-texts-url {:as :stream})
      de-texts-response-stream
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

(defn count-articles-xf
  [rf]
  (let [article-count (atom 0)]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result article]
       (when (zero? (mod (swap! article-count inc) batch-size))
         (log/debugf "Wikipedia-DE/ Articles tokenized: %,10d" @article-count))
       (rf result article)))))

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
    (let [articles (line-seq de-texts)
          batches  (partition-all batch-size articles)
          batches  (pmap (partial into [] tokenizer-xf) batches)
          tokens   (mapcat identity batches)
          freqs    (frequencies tokens)]
      (into {} (filter (fn [[_ n]] (< token-min-freq n))) freqs))))

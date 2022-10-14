(ns dwds.wikidata.dump
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [jsonista.core :as json]
   [taoensso.timbre :as log]
   [hato.client :as http])
  (:import
   (java.text Collator)
   (java.util Locale)
   (java.util.zip GZIPInputStream)))

(def lexemes-dump-url
  "https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz")

(defn lexemes-dump-reader
  []
  (-> (http/get lexemes-dump-url {:as :stream})
      (get :body)
      GZIPInputStream.
      (io/reader :encoding "UTF-8")))

(defn -main
  [& _]
  (with-open [r (lexemes-dump-reader)]
    (doseq [l (line-seq r)]
      (println l))))

(defn read-json
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(def parse-lexeme-dump-xf
  (comp
   (map str/trim)
   (filter #(< 2 (count %)))
   (map #(str/replace % #",$" ""))
   (map read-json)))

(defn lexemes
  ([]
   (lexemes (map identity)))
  ([xf]
   (with-open [rdr (lexemes-dump-reader)]
     (into [] (comp parse-lexeme-dump-xf xf) (line-seq rdr)))))

(def german-lexem?
  (comp (partial = "Q188") :language))

(def german-collator
  (doto (Collator/getInstance Locale/GERMAN)
    (.setStrength Collator/PRIMARY)))

(defn german-sort-key
  [s]
  (.getCollationKey german-collator s))

(def lemmata-file
  (io/file "lemmata.edn"))

(defn get-lemmata
  [{lemmata :lemmas}]
  (for [[_lang {lemma :value}] lemmata] lemma))

(defn store-lemmata!
  []
  (spit lemmata-file
        (pr-str (into #{} (lexemes (mapcat get-lemmata))))))

(defn stored-lemmata
  []
  (read-string (slurp lemmata-file)))

(comment
  (count (stored-lemmata)))

(defn dwds-forms
  []
  (with-open [input (io/reader "dwds_vollformen_2022-08-15.txt")]
    (into #{} (line-seq input))))

(defn clean-chars
  [l]
  (->
   l
   (str/replace #"[\.,_\(\)=\"]" " ")
   (str/replace #"\u00e2\u0080[\u009e\u009f]" " ")
   (str/replace #"\u00e0\u00a5[\u00a4\u00a5]" " ")
   (str/trim)))

(def skipped-token?
  (some-fn #{"" "NEWLINE"} #(re-seq #"^\d+$" %)))

(defn count-articles
  [rf]
  (let [cnt (atom 0)]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result article]
       (when (zero? (mod (swap! cnt inc) 100000))
         (log/infof ". %8d" @cnt))
       (rf result article)))))

(def corpus-xf
  (comp
   (map clean-chars)
   (mapcat #(str/split % #"\s+"))
   (remove skipped-token?)
   (map str/lower-case)))

#_(defn -main
  [& _]
  (time
   (with-open [input (io/reader (GZIPInputStream. (io/input-stream (io/file "de.txt.gz"))))]
     (let [lines  (sequence count-articles (line-seq input))
           chunks (partition-all 10000 lines)
           chunks (pmap #(sequence corpus-xf %) chunks)
           words  (mapcat identity chunks)
           freqs  (frequencies words)
           freqs  (filter (fn [[_ n]] (< 10 n)) freqs)
           freqs  (into {} freqs)]
       (spit (io/file "wkpd-de-tokens.edn") (pr-str freqs))))))

(defn skipped-wkpd-tokens
  []
  (with-open [input (io/reader "wkpd-de-skipped-tokens.txt")]
    (into #{} (line-seq input))))

(comment
  (let [forms    (into #{} (map str/lower-case) (dwds-forms))
        tokens   (read-string (slurp (io/file "wkpd-de-tokens.edn")))
        skipped  (skipped-wkpd-tokens)
        tokens   (remove (comp skipped first) tokens)
        coverage (reduce
                  (fn [result [word cnt]]
                    (let [covered? (forms word)]
                      (cond-> result
                        :always  (update :total-forms inc)
                        :always  (update :total-tokens + cnt)
                        covered? (update :covered-forms inc)
                        covered? (update :covered-tokens + cnt))))
                  {:total-tokens   0
                   :covered-tokens 0
                   :total-forms    0
                   :covered-forms  0}
                  tokens)]
    (assoc coverage
           :forms-pct (float (/ (:covered-forms coverage) (:total-forms coverage)))
           :tokens-pct (float (/ (:covered-tokens coverage) (:total-tokens coverage)))))
  ;; => {:total-tokens   588095238,
  ;;     :covered-tokens 495474075,
  ;;     :total-forms    1086338,
  ;;     :covered-forms  238975,
  ;;     :forms-pct      0.21998218,
  ;;     :tokens-pct     0.8425065}
  )

(comment
  (with-open [input (io/reader lexemes-dump :encoding "UTF-8")]
    (into
     []
     (take 100)
     (for [line  (line-seq input)
           :let  [line (str/trim line)]
           :when (< 2 (count line))
           :let  [line   (str/replace line #",$" "")
                  lexeme (json/read-value line json/keyword-keys-object-mapper)]
           :when (= "Q188" (get lexeme :language))
           :let [lemma (get-in lexeme [:lemmas :de :value])
                 polysemous? (second (:senses lexeme))]
           :when polysemous? #_(not polysemous?)
           sense (:senses lexeme)
           :let [gloss (get-in sense [:glosses :de :value])]]
       [lemma gloss]))))

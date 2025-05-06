(ns dwds.wikidata.form
  (:require
   [dwds.wikidata.db :as db]
   [clojure.java.io :as io]
   [clojure.data.csv :as csv]
   [dwds.wikidata.env :as env]
   [julesratte.auth :as jr.auth]
   [julesratte.client :as jr.client]
   [julesratte.json :as jr.json]
   [taoensso.timbre :as log]
   [clojure.string :as str]))

(def with-wikidata-forms?
  (comp seq :forms :wikidata_lexeme/entity :wikidata))

(defn wd-form-and-features
  [{:dwdsmor_index/keys [tense number casus person funct pos degree mood gender
                         nonfinite inflected category]}]
  (->>
   (cond-> (sorted-set)
     (= "Sg" number)        (conj "Q110786")
     (= "Pl" number)        (conj "Q146786")
     (= "Masc" gender)      (conj "Q499327")
     (= "Fem" gender)       (conj "Q1775415")
     (= "Neut" gender)      (conj "Q1775461")
     (= "Nom" casus)        (conj "Q131105")
     (= "Gen" casus)        (conj "Q146233")
     (= "Dat" casus)        (conj "Q145599")
     (= "Acc" casus)        (conj "Q146078")
     (= "1" person)         (conj "Q21714344")
     (= "2" person)         (conj "Q51929049")
     (= "3" person)         (conj "Q51929074")
     (= "Pos" degree)       (conj "Q3482678")
     (= "Comp" degree)      (conj "Q14169499")
     (= "Sup" degree)       (conj "Q1817208")
     (= "Attr/Subst" funct) (conj "Q4818723")
     (= "Pred/Adv" funct)   (conj "Q1931259")
     (= "Inf" nonfinite)    (cond->
                                (= "Cl" category)(conj "Q100952920")
                                :else            (conj "Q179230"))
     (= "Part" nonfinite)   (cond->
                                (= "Pres" tense) (conj "Q10345583")
                                (= "Perf" tense) (conj "Q12717679"))
     (= "Ind" mood)         (conj "Q682111")
     (= "Subj" mood)        (cond->
                                (= "Pres" tense) (conj "Q55685962")
                                (= "Past" tense) (conj "Q54671845"))
     (= "Imp" mood)         (conj "Q22716")
     (= "+V"  pos)          (conj "Q1317831") ; all verb forms active?
     (= "Pres" tense)       (conj "Q192613")
     (= "Past" tense)       (conj "Q442485"))
   (into [inflected])))

(defn wd-form?
  [{:dwdsmor_index/keys [funct pos]}]
  (or (not= pos "+ADJ") (= "Pred/Adv" funct)))

(defn assoc-wd-form-and-features
  [{:keys [dwdsmor] :as lexeme}]
  (assoc lexeme :wikidata-forms
         (into (sorted-set)
               (comp (filter wd-form?)
                     (map wd-form-and-features))
               dwdsmor)))

(def grammatical-features
  ["Q110786"
   "Q146786"
   "Q1317831"
   "Q1775415"
   "Q499327"
   "Q146233"
   "Q192613"
   "Q131105"
   "Q146078"
   "Q145599"
   "Q682111"
   "Q442485"
   "Q1775461"
   "Q21714344"
   "Q51929049"
   "Q51929074"
   "Q55685962"
   "Q54671845"
   "Q22716"
   "Q179230"
   "Q100952920"
   "Q1931259"
   "Q3482678"
   "Q12717679"
   "Q10345583"
   "Q14169499"
   "Q1817208"])

(defn wikidata-form->csv
  [[form & features]]
  (let [features (into #{} features)]
    (into [form] (map #(if (features %) 1 0)) grammatical-features)))

(defn sample-data!
  [& _]
  (with-open [w (io/writer (io/file "wikidata-forms-import-sample.csv"))]
    (csv/write-csv w [(into ["Form"] grammatical-features)])
    (db/query
     "where wd.id is not null"
     (comp (remove with-wikidata-forms?)
           (map assoc-wd-form-and-features)
           (random-sample 0.01)
           (mapcat :wikidata-forms)
           (map wikidata-form->csv)
           (partition-all 1024))
     (completing (fn [w records] (doto w (csv/write-csv records)))) w)))

(defn form-data
  [[form & features]]
  {:add                 ""
   :representations     {:de {:language "de" :value form}}
   :grammaticalFeatures (vec features)
   :claims              {}})

(defn forms-data
  [{forms :wikidata-forms}]
  (into [] (map form-data forms)))

(def add-forms-request-params
  {:action "wbeditentity"
   :bot    "true"})

(defn add-forms!
  [url csrf-token dry-run? {{:wikidata_lexeme/keys [id]} :wikidata :as lexeme}]
  (when-not dry-run?
    (->
     (assoc add-forms-request-params
            :id  id
            :data (jr.json/write-value {:forms (forms-data lexeme)})
            :token csrf-token)
     (jr.client/request-with-params)
     (assoc :url url)
     (jr.client/request!)))
  lexeme)

(def sample-lemmata
  #{"Ikarusflug" "Abendsonnenschein"
    "verdummbeuteln" "andrehen" "strudeln"
    "euklidisch" "lebensfeindlich"})

(defn import!
  [{:keys [offset dry-run?] :or {offset 0 dry-run? true}}]
  (jr.auth/with-login env/login
    (let [csrf-token (jr.auth/csrf-token env/api-endpoint)]
      (db/query
       (str "where wd.id is not null "
            "and wd.lemma in "
            "(" (str/join "," (map #(str "'" % "'") sample-lemmata)) ")")
       (comp
        (remove with-wikidata-forms?)
        (map assoc-wd-form-and-features)
        (drop offset)
        (map (partial add-forms! env/api-endpoint csrf-token dry-run?))
        (map-indexed
         (fn [i {[{:dwdsmor_index/keys [analysis pos]}] :dwdsmor
                 :keys                                  [wikidata-forms]}]
           (log/debugf "%010d [%4s] [%02d] %s" (+ offset i) pos
                       (count wikidata-forms) analysis)))
        (map (constantly 1)))
       + 0))))

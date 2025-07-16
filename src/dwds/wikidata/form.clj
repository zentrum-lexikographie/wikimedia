(ns dwds.wikidata.form
  (:require
   [clojure.string :as str]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.env :as env]
   [julesratte.client :as jr]
   [julesratte.json :as jr.json]
   [taoensso.timbre :as log]))

(def with-wikidata-forms?
  (comp seq :forms :wikidata_lexeme/entity :wikidata))

(def grammatical-features
  [["Q179230"    "infinitive"]
   ["Q100952920" "zu infinitive"]
   ["Q682111"    "indicative"]
   ["Q55685962"  "subjunctive I"]
   ["Q54671845"  "subjunctive II"]
   ["Q192613"    "present"]
   ["Q442485"    "preterite"]
   ["Q110786"    "singular"]
   ["Q146786"    "plural"]
   ["Q131105"    "nominative"]
   ["Q146233"    "genitive"]
   ["Q145599"    "dative"]
   ["Q146078"    "accusative"]
   ["Q3482678"   "positive"]
   ["Q14169499"  "comparative"]
   ["Q1817208"   "superlative"]
   ["Q21714344"  "first person"]
   ["Q51929049"  "second person"]
   ["Q51929074"  "third person"]
   ["Q22716"     "imperative"]
   ["Q10345583"  "present participle"]
   ["Q12717679"  "past participle"]])

(def grammatical-feature-labels
  (into {} grammatical-features))

(def grammatical-feature-index
  (into {} (map-indexed (fn [i [qid _]] [qid i]) grammatical-features)))

(def num-grammatical-features
  (count grammatical-features))

(defn wd-form-and-features
  [{:dwdsmor_index/keys [tense number casus person degree mood
                         nonfinite inflected funct]}]
  (cond-> [inflected]
    (= "Inf" nonfinite)  (conj (if (= "Cl" funct) "Q100952920" "Q179230"))
    (= "Pos" degree)     (conj "Q3482678")
    (= "Comp" degree)    (conj "Q14169499")
    (= "Sup" degree)     (conj "Q1817208")
    (= "Ind" mood)       (conj "Q682111")
    (= "Subj" mood)      (cond->
                             (= "Pres" tense) (conj "Q55685962")
                             (= "Past" tense) (conj "Q54671845"))
    (= "Imp" mood)       (conj "Q22716")
    (= "Part" nonfinite) (cond->
                             (= "Pres" tense) (conj "Q10345583")
                             (= "Perf" tense) (conj "Q12717679"))
    (= "Pres" tense)     (cond->
                             (not= "Part" nonfinite) (conj "Q192613"))
    (= "Past" tense)     (conj "Q442485")
    (= "Sg" number)      (conj "Q110786")
    (= "Pl" number)      (conj "Q146786")
    (= "Nom" casus)      (conj "Q131105")
    (= "Gen" casus)      (conj "Q146233")
    (= "Dat" casus)      (conj "Q145599")
    (= "Acc" casus)      (conj "Q146078")
    (= "1" person)       (conj "Q21714344")
    (= "2" person)       (conj "Q51929049")
    (= "3" person)       (conj "Q51929074")))

(defn wd-form?
  [{:dwdsmor_index/keys [funct pos]}]
  (or (not= pos "ADJ") (= "Pred/Adv" funct)))

(defn form-sort-key
  [[_inflected & features]]
  (-> (map grammatical-feature-index features)
      (concat (repeat (- num-grammatical-features (count features)) 0))
      (vec)))

(defn assoc-wd-form-and-features
  [{:keys [dwdsmor] :as lexeme}]
  (assoc lexeme :wikidata-forms
         (->> dwdsmor
              (filter wd-form?)
              (map wd-form-and-features)
              (sort-by form-sort-key)
              (vec))))

(defn sample-data
  [& _]
  (db/query
   "where wd.id is not null and wd.pos = 'V'"
   (comp (remove with-wikidata-forms?)
         (map assoc-wd-form-and-features)
         (random-sample 0.01)
         (take 10)
         (mapcat :wikidata-forms)
         (map (fn [[form & features]]
                (into [form]
                      (map grammatical-feature-labels)
                      features))))
   conj []))

(comment (sample-data))

(defn form-data
  [[form & features]]
  {:add                 ""
   :representations     {:de {:language "de" :value form}}
   :grammaticalFeatures (vec features)
   :claims              {}})

(defn add-forms!
  [csrf-token dry-run? {{:wikidata_lexeme/keys [id]} :wikidata
                        forms                        :wikidata-forms
                        :as                          lexeme}]
  (when-not dry-run?
    (env/api-request! {:action "wbeditentity"
                       :bot    "true"
                       :id     id
                       :data   (jr.json/write-value
                                {:forms (into [] (map form-data) forms)})
                       :token  csrf-token}))
  lexeme)

(defn get-sample-lemmata
  [pos]
  (let [lemmata (db/query
                 (format "where wd.id is not null and wd.pos = '%s'" pos)
                 (comp (remove with-wikidata-forms?)
                       (map (comp (juxt :wikidata_lexeme/id :wikidata_lexeme/lemma)
                                  :wikidata)))
                 conj [])]
    (into {} (take 25) (shuffle lemmata))))

(comment
  (get-sample-lemmata "ADJ"))

(defn clear-forms!
  [id]
  (let [form-ids      (->> (env/api-request! {:action "wbgetentities" :ids [id]})
                           :body :entities vals first :forms (map :id))
        form-removals (into [] (map (fn [id] {:id id :remove ""}))
                            form-ids)]
    (when (seq form-removals)
      (jr/with-login env/login
        (env/api-request! {:action "wbeditentity"
                           :bot    "true"
                           :id     id
                           :data   (jr.json/write-value {:forms form-removals})
                           :token  (env/api-csrf-token)})))))

(def sample-lemmata
  {"L343331" "altklug"
   "L794501" "sprachmächtig" 
   "L809299" "Kastengeist"
   "L861928" "Menschenfuß"
   "L757338" "entgegenrollen"
   "L810517" "vorbeijagen"})

(defn import!
  [{:keys [offset dry-run?] :or {offset 0 dry-run? true}}]
  (jr/with-login env/login
    (db/query
     (str "where wd.id is not null "
          "and wd.lemma in "
          "(" (str/join "," (map #(str "'" % "'") (vals sample-lemmata))) ")")
     (comp
      (remove with-wikidata-forms?)
      (map assoc-wd-form-and-features)
      (drop offset)
      (map (partial add-forms! (env/api-csrf-token) dry-run?))
      (map-indexed
       (fn [i {[{:dwdsmor_index/keys [analysis pos]}] :dwdsmor
               :keys                                  [wikidata-forms]}]
         (log/debugf "%010d [%4s] [%02d] %s" (+ offset i) pos
                     (count wikidata-forms) analysis)))
      (map (constantly 1)))
     + 0)))


(comment
  (import! {:dry-run? false}))

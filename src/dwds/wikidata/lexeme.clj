(ns dwds.wikidata.lexeme
  (:require
   [dwds.wikidata.db :as db]
   [dwds.wikidata.env :as env]
   [julesratte.client :as jr]
   [julesratte.json :as jr.json]
   [taoensso.timbre :as log])
  (:import
   (java.time LocalDate)))

(defn entity-value
  [id]
  {:type  "wikibase-entityid"
   :value {:entity-type "item"
           :numeric-id  (parse-long (subs id 1))
           :id          id}})

(defn str-value
  [s]
  {:type  "string"
   :value s})

(defn ext-id-snak
  [p v]
  {:snaktype  "value"
   :property  (name p)
   :datatype  "external-id"
   :datavalue (str-value v)})

(defn entity-snak
  [p id]
  {:snaktype  "value"
   :property  (name p)
   :datatype  "wikibase-item"
   :datavalue (entity-value id)})

(defn time-snak
  [p ^LocalDate d]
  {:snaktype  "value"
   :property  (name p)
   :datatype  "time"
   :datavalue {:value
               {:calendarmodel "http://www.wikidata.org/entity/Q1985727"
                :timezone      0
                :time          (str "+" (str d) "T00:00:00Z")
                :precision     11
                :after         0
                :before        0}
               :type "time"}})

(defn lemma-id-snak
  [lemma]
  (ext-id-snak :P9940 lemma))

(defn dwds-lemma-id-statement
  [lemma]
  {:type       "statement"
   :mainsnak   (lemma-id-snak lemma)})

(defn plt-statement
  [refs]
  {:type       "statement"
   :mainsnak   (entity-snak :P31 "Q138246")
   :references refs})

(def gender->entity-id
  {"Masc" "Q499327"
   "Fem"  "Q1775415"
   "Neut" "Q1775461"})

(defn genus-statement
  [refs gender]
  (when-let [id (gender->entity-id gender)]
    (list {:type       "statement"
           :mainsnak   (entity-snak :P5185 id)
           :references refs})))

(defn entity-data
  [{[{:dwdsmor_index/keys [analysis pos]} :as forms] :dwdsmor}]
  (let [refs    [{:snaks       {:P248  [(entity-snak :P248 "Q108696977")]
                                :P9940 [(lemma-id-snak analysis)]
                                :P813  [(time-snak :P813 (LocalDate/now))]}
                  :snaks-order ["P248" "P9940" "P813"]}]
        dwds-id [(dwds-lemma-id-statement analysis)]
        noun?   (= "NN" pos)
        genera  (when noun? (map :dwdsmor_index/gender forms))
        genera  (into (sorted-set) genera)
        plt?    (and noun? (some? (genera "UnmGend")))
        plt     (when plt? [(plt-statement refs)])
        genera  (into [] (mapcat (partial genus-statement refs)) genera)]
    {:type            "lexeme"
     :language        "Q188"
     :lexicalCategory (db/pos->lex-cat pos)
     :lemmas          {:de {:value    analysis
                            :language "de"}}
     :claims          (cond-> {}
                        (seq plt)    (assoc :P31 plt)
                        (seq genera) (assoc :P5185 genera)
                        :always      (assoc :P9940 dwds-id))}))

(defn create-entity!
  [summary csrf-token dry-run? lexeme]
  (when-not dry-run?
    (env/api-request! {:action  "wbeditentity"
                       :new     "lexeme"
                       :bot     "true"
                       :summary summary
                       :data    (jr.json/write-value (entity-data lexeme))
                       :token   csrf-token}))
  lexeme)

(defn import!
  ([opts]
   (import! (env/edit-summary "Lexeme Import") opts))
  ([summary {:keys [offset dry-run?] :or {offset 0 dry-run? true}}]
   (jr/with-login env/login
     (db/query
      "where wd.id is null"
      (comp
       (drop offset)
       (map (partial create-entity! summary (env/api-csrf-token) dry-run?))
       (map-indexed (fn [i {[{:dwdsmor_index/keys [analysis pos]}] :dwdsmor}]
                      (log/debugf "%010d [%4s] %s" (+ i offset) pos analysis)))
       (map (constantly 1)))
      + 0))))

(comment
  (import! {}))

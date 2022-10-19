(ns dwds.wikidata.lexeme
  "Support for the data model of Wikidata lexemes."
  (:require
   [dwds.wikidata.lex :as lex]
   [lambdaisland.uri :as uri])
  (:import
   (java.time LocalDate)))

(defn assoc-vocab
  "Maps properties of DWDS lexemes to WikiData item identifiers."
  [vocab {:keys [genera pos] :as lexeme}]
  (cond-> lexeme
    pos    (assoc :pos (get vocab pos))
    genera (assoc :genera (into (sorted-set) (map vocab) genera))))

(defn entity-value
  [n]
  {:type  "wikibase-entityid"
   :value {:entity-type "item"
           :numeric-id  n
           :id          (str "Q" n)}})

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
  [p v]
  {:snaktype  "value"
   :property  (name p)
   :datatype  "wikibase-item"
   :datavalue (entity-value v)})

(defn url-snak
  [p v]
  {:snaktype  "value"
   :property  (name p)
   :datatype  "url"
   :datavalue (str-value v)})

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

(def dwds-wb-uri
  (uri/uri "https://www.dwds.de/wb/"))

(defn lemma-id-snak
  [lemma]
  (ext-id-snak :P9940 lemma))

(defn dwds-lemma-id-statement
  [lemma]
  {:type       "statement"
   :mainsnak   (lemma-id-snak lemma) })

(defn genus-statement
  [dwds lemma v]
  {:type       "statement"
   :mainsnak   (entity-snak :P5185 v)
   :references [{:snaks       {:P248  [(entity-snak :P248 dwds)]
                               :P9940 [(lemma-id-snak lemma)]
                               :P813  [(time-snak :P813 (LocalDate/now))]}
                 :snaks-order ["P248" "P9940" "P813"]}]})

(defn assoc-genera
  [entity dwds lemma genera]
  (let [stmts (into [] (map (partial genus-statement dwds lemma)) genera)]
    (assoc-in entity [:claims :P5185] stmts)))

(defn lex->wb-lemma
  [lang dwds {:keys [lemma _reprs pos genera uri]}]
  (let [entity {:type            "lexeme"
                :language        (str "Q" lang)
                :lexicalCategory (str "Q" pos)
                :lemmas          {:de {:value lemma :language "de"}}
                :claims          {:P9940 [(dwds-lemma-id-statement lemma)]}}]
    (cond-> entity genera (assoc-genera dwds lemma genera))))

(defn lex->wb-xf
  [vocab]
  (let [lang (get vocab lex/lang 188)
        dwds (get vocab lex/source 108696977)]
    (comp (remove :hidx)
          (map (partial assoc-vocab vocab))
          ;; only pass lexemes with a resolved WikiData POS
          (filter :pos)
          ;; only pass lexemes with resolved WikiData genera (if given)
          (remove #(some nil? (:genera %)))
          (map (partial lex->wb-lemma lang dwds)))))

(defn lex->wb
  "Convert DWDS lexeme data to its WikiData counterpart.

  The vocubulary depends on the targetted Wikibase instance, i. e. the central
  WikiData instance vs. a test setup."
  [vocab lemmata]
  (sequence (lex->wb-xf vocab) lemmata))

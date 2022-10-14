(ns dwds.wikidata.lexeme
  "Support for the data model of Wikidata lexemes."
  (:require [dwds.wikidata.lex :as lex]
            [lambdaisland.uri :as uri]))

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

(def dwds-wb-uri
  (uri/uri "https://www.dwds.de/wb/"))

(defn dwds-ref-snak
  [uri]
  (url-snak :P854 (str (uri/join dwds-wb-uri  uri))))

(defn dwds-statement
  [dwds uri]
  {:type       "statement"
   :mainsnak   (entity-snak :P1343 dwds)
   :references [{:snaks {:P854 [(dwds-ref-snak uri)]}}]})

(defn genus-statement
  [dwds v]
  {:type     "statement"
   :mainsnak (entity-snak :P5185 v)
   :references [{:snaks {:P248 [(entity-snak :P248 dwds)]}}]})

(defn assoc-genera
  [entity dwds genera]
  (let [stmts (into [] (map (partial genus-statement dwds)) genera)]
    (assoc-in entity [:claims :P5185] stmts)))

(defn lex->wb-lemma
  [lang dwds {:keys [lemma _reprs pos genera uri]}]
  (let [entity {:type            "lexeme"
                :language        (str "Q" lang)
                :lexicalCategory (str "Q" pos)
                :lemmas          {:de {:value lemma :language "de"}}
                :claims          {:P1343 [(dwds-statement dwds uri)]}}]
    (cond-> entity genera (assoc-genera dwds genera))))

(defn lex->wb-xf
  [vocab]
  (let [lang  (get vocab lex/lang 188)
        dwds  (get vocab lex/source 1225026)]
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

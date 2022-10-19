(ns dwds.wikidata.wdqs
  "Access to Wikidata's (SPARQL) Query Service."
  (:require
   [clojure.string :as str]
   [hato.client :as http]
   [jsonista.core :as json]))

(def wdqs-endpoint
  "https://query.wikidata.org/bigdata/namespace/wdq/sparql")

(defn query!
  "Query the data service, using JSON the response format."
  [sparql-query]
  (->
   {:method      :post
    :url         wdqs-endpoint
    :headers     {"accept" "application/json"}
    :form-params {:query sparql-query}}
   (http/request)
   (get :body)
   (json/read-value json/keyword-keys-object-mapper)))

(defn label->sparql-clause
  [label]
  (format "?item rdfs:label ?label . ?item rdfs:label \"%s\"@en ." label))

(defn vocab-id-query
  "Query item IRIs for a given set of English labels.

  The result set is filtered by a set of item classes relevant to the
  description of lexemes (part-of-speech, language, project etc.)"
  [labels]
  (let [label-clauses (->> (map label->sparql-clause labels)
                           (str/join " } UNION { "))]
    (->>
     ["PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
      "PREFIX wd: <http://www.wikidata.org/entity/>"
      "PREFIX wdt: <http://www.wikidata.org/prop/direct/>"
      ""
      "SELECT ?label ?item WHERE {"
      "?item wdt:P31 ?type {" label-clauses "}"
      "FILTER (?type IN (wd:Q34770, wd:Q82042, wd:Q162378, wd:Q82042, wd:Q3327521))"
      "FILTER (LANG(?label) = \"en\")"
      "}"]
     (str/join \newline))))

(defn wikidata-iri->id
  [s]
  (parse-long (str/replace s #"^https?://[^/]+/entity/Q" "")))

(defn query-vocab!
  "Query the WDQS for a mapping of labels in a vocabulary to numeric WD item
  identifiers."
  [labels]
  (->>
   (-> (vocab-id-query labels) (query!) (get-in [:results :bindings]))
   (map (juxt (comp :value :label) (comp wikidata-iri->id :value :item)))
   (filter (comp labels first))
   (into (sorted-map))))

(comment
  (query-vocab! #{"German" "Digitales Wörterbuch der deutschen Sprache"
                  "adjective" "adverb" "noun" "verb"
                  "feminine" "masculine" "neuter"})
  ;; => {"Digitales Wörterbuch der deutschen Sprache" 1225026,
  ;;     "German" 188,
  ;;     "adjective" 34698,
  ;;     "adverb" 380057,
  ;;     "feminine" 1775415,
  ;;     "masculine" 499327,
  ;;     "neuter" 1775461,
  ;;     "noun" 1084,
  ;;     "verb" 24905}
  )

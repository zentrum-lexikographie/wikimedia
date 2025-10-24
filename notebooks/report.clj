;; # DWDS Donation to Wikidata – Report

(ns report
  {:nextjournal.clerk/toc true}
  (:require
   [clojure.string :as str]
   [dwds.wikidata.db :as db]
   [gremid.xml :as gx]
   [jsonista.core :as json]
   [julesratte.client :as jr]
   [julesratte.wikibase :as jr.wb]
   [nextjournal.clerk :as clerk]))

{:nextjournal.clerk/visibility {:code :show :result :hide}}

;; ## Wikidata API requests
;;
;; In order to report on data donated to Wikidata by the DWDS, we
;; query Wikidata's MediaWiki API.

(def wd-requests!
  (partial jr/requests! (jr/api-url "www.wikidata.org")))

;; ## DwdsBot Edits
;;
;; First we query for all contributions by the `DwdsBot` user and
;; categorize those contributions by the type of data added to the
;; knowledge graph.

(def dwdsbot-edits
  (into
   []
   (comp
    (mapcat #(get-in % [:body :query :usercontribs]))
    (map #(select-keys % [:title :timestamp :comment]))
    (remove (comp #(str/includes? % "Sample Form Import") :comment))
    (map (fn [{:keys [comment] :as edit}]
           (->> (condp #(str/includes? %2 %1) comment
                  "Property:P5185"             :genus
                  "Form Import"                :forms
                  "wbeditentity-create-lexeme" :lexeme
                  :other)
                (assoc edit :category))))
    (remove (comp #{:other} :category))
    (map #(update % :title str/replace #"^Lexeme:" "")))
   (wd-requests!
    {:list        #"usercontribs"
     :ucuser      "DwdsBot"
     :ucnamespace "146"
     :uclimit     "500"}
    10000)))

;; ## Wikidata Properties
;;
;; To resolve identifiers of properties to their datatype and label,
;; we further query Wikidata for all properties.

(def wd-properties
  (into
   {}
   (comp
    (map (comp :export :query :body))
    (mapcat (fn [xml] (->> xml gx/read-events gx/events->node (gx/elements :text))))
    (mapcat gx/texts)
    (map json/read-value)
    (map (fn [{id "id" {{label "value"} "en"} "labels" datatype "datatype"}]
           [id {:label label :datatype datatype}])))
   (wd-requests!
    {:generator    "allpages"
     :gapnamespace "120"
     :gaplimit     "500"
     :export       "true"}
    Long/MAX_VALUE)))

;; ## Concept-based Sense Links for DWDS-linked lemmata
;;
;; While all German lexemes have been retrieved for reporting via a
;; JSONL-formatted dump of Wikidata, their links to lexemes in other
;; languages need to be queried separately. The following SPARQL query
;; run against Wikidata's Query Service yields those links.

(def sense-links
  (jr.wb/query
   `{:select-distinct [?l_de ?lemma_de ?langLabel ?l_other ?lemma_other]
     :where           [[?l_de :pd/P9940 ?dwds_id]
                       [?l_de :ontolex/sense ?s1]
                       [?l_de :dct/language :e/Q188]
                       [?l_de :wikibase/lemma ?lemma_de]
                       [?l_other :ontolex/sense ?s2]
                       [?l_other :dct/language ?lang]
                       [?l_other :wikibase/lemma ?lemma_other]
                       [?s1 :pd/P5137 ?concept]
                       [?s2 :pd/P5137 ?concept]
                       [:filter (not= ?l_de ?l_other)]]}))

;; ## German Lexemes in Wikidata
;;
;; All German lexemes are retrieved from a SQLite database which has
;; been created based on the aforementioned Wikidata dump.

(defn lexemes
  [xform f init]
  (transduce
   (comp (map (partial into {}))
         (partition-all 1024)
         (mapcat #(pmap (comp read-string :wikidata_lexeme/entity) %))
         xform)
   f init
   (db/plan ["select * from wikidata_lexeme"])))

;; Those lexemes are filtered by existing references to the DWDS.

(def dwds-lexemes
  (lexemes (filter (comp :P9940 :claims)) conj []))

;; ## DWDS Lexeme Statistics
;;
;; We count the number of currently existing German lexemes in
;; Wikidata with a reference to a DWDS entry. Also we sort timestamps
;; of the `DwdsBot` contributions to get the time span of the
;; donation.

^{::clerk/visibility {:code :hide :result :show}}
(let [dwdsbot-timestamps (sort (map :timestamp dwdsbot-edits))]
  (->> [["First import" (first dwdsbot-timestamps)]
        ["Last import" (last dwdsbot-timestamps)]]
       (clerk/table)
       (clerk/caption (format "%,d German DWDS lexemes in total"
                              (count dwds-lexemes)))))

^{::clerk/visibility {:code :hide :result :show}}
(let [lex-cat->pos  {"Q34698"  "ADJ"
                     "Q380057" "ADV"
                     "Q1084"   "NN"
                     "Q24905"  "V"}
      lex-cats      (lexemes
                    (map identity)
                    (completing
                     (fn [m {:keys [lexicalCategory]}]
                       (update m (lex-cat->pos lexicalCategory) (fnil inc 0))))
                    {})
      lexeme->edits (reduce
                     (fn [m {:keys [title category]}]
                       (update m title (fnil conj #{}) category))
                     {}
                     dwdsbot-edits)
      contributions (lexemes
                     (map identity)
                     (completing
                      (fn [m {:keys [id lexicalCategory]}]
                        (reduce
                         #(update-in %1 [(lex-cat->pos lexicalCategory) %2] (fnil inc 0))
                         m
                         (get lexeme->edits id #{:existing}))))
                     {})]
  (->>
   (for [pos ["ADV" "ADJ" "V" "NN"]]
     [pos
      (get-in contributions [pos :existing] 0)
      (get-in contributions [pos :lexeme] 0)
      (get-in contributions [pos :forms] 0)
      (get lex-cats pos 0)])
   (cons ["Lexical Category" "# Existing" "# Lexeme Imports" "# Forms Imports" "# DWDS"])
   (clerk/use-headers)
   (clerk/table)
   (clerk/caption "Contributions by Lexical Category")))


;; ## References to external lexical resources
;;
;; For all DWDS-linked lexemes, we collect properties linking to
;; lexical resources.

(def external-ids
  (->> (mapcat (comp keys :claims) dwds-lexemes)
       (map (comp wd-properties name))
       (filter (comp #(= "external-id" %) :datatype))
       (map :label)
       (frequencies)))

;; The frequencies of external links are filtered by a lower bound and
;; displayed as a table.

^{::clerk/visibility {:code :hide :result :show}}
(->> external-ids
     (filter (comp #(<= 100 %) second))
     (sort-by (comp - second))
     (cons (list "External Resource" "# of references"))
     (clerk/use-headers)
     (clerk/table)
     (clerk/caption (format "%,d external identifiers in total"
                            (reduce + (map second external-ids)))))

;; ## Concept-based Sense Links by Language
;;
;; We aggregate sense links of DWDS lexemes, exclude those to other
;; German lexemes and count frequencies by language.

(def sense-links-by-language
  (->> (map :langLabel sense-links)
       (remove #{"German"})
       (frequencies)
       (sort-by (comp - second))))

^{::clerk/visibility {:code :hide :result :show}}
(->> sense-links-by-language
     (clerk/table)
     (clerk/caption (format "%,d sense links in total"
                            (reduce + (map second sense-links-by-language)))))


(ns german-lexemes
  (:require
   [dwds.wikidata.db :as db]
   [julesratte.wikidata :as wd]
   [clojure.string :as str]))

(def dwds-lexemes
  (->>
   (db/execute! ["select * from wikidata_lexeme"])
   (pmap (comp read-string :wikidata_lexeme/entity))
   (filter (comp :P9940 :claims))))


(count dwds-lexemes)

(def external-ids
  (->> (mapcat (comp keys :claims) dwds-lexemes)
       (filter (comp #(str/ends-with? % "-id") name wd/wdt->label))
       (remove #{:P9940})
       (map (juxt identity wd/wdt->label))
       (vec)))

(->> (frequencies external-ids)
     (sort-by (comp - second)))

(count external-ids)

(->> (mapcat :senses dwds-lexemes)
     (mapcat (comp keys :claims) )
     (map (juxt identity wd/wdt->label))
     (frequencies)
     (sort-by (comp - second)))

(->> (mapcat :senses dwds-lexemes)
     (mapcat (comp :P5137 :claims)))

(def sense-links
  (wd/query
   `{:select-distinct [?l_de ?lemma_de ?langLabel ?l_other ?lemma_other]
     :where           [[?l_de :wdt/P9940 ?dwds_id]
                       [?l_de :ontolex/sense ?s1]
                       [?l_de :dct/language :wd/Q188]
                       [?l_de :wikibase/lemma ?lemma_de]
                       [?l_other :ontolex/sense ?s2]
                       [?l_other :dct/language ?lang]
                       [?l_other :wikibase/lemma ?lemma_other]
                       [?s1    :wdt/P5137      ?concept]
                       [?s2    :wdt/P5137      ?concept]
                       [:filter (not= ?l_de ?l_other)]]
     :limit            177383}))

(count sense-links)

(->> (map :langLabel sense-links)
     (frequencies)
     (sort-by (comp - second)))

(count (filter (comp seq :forms) dwds-lexemes))

(def pronounciation-lexemes
  (->> dwds-lexemes
       (filter (comp (partial some (comp :P443 :claims)) :forms))))

(count pronounciation-lexemes)

(->> (mapcat :forms dwds-lexemes)
     (mapcat (comp keys :claims))
     (map (juxt identity wd/wdt->label))
     (frequencies)
     (sort-by (comp - second)))


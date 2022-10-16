(ns dwds.wikidata.lexeme-import-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.entity :as entity]
   [dwds.wikidata.fixture :as fixture]
   [dwds.wikidata.lex :as lex]
   [dwds.wikidata.lexeme :as lexeme]
   [dwds.wikidata.wdqs :as wdqs]))

(use-fixtures :once fixture/test-wb)

(defn lexemes
  [vocab]
  (lexeme/lex->wb vocab (fixture/lex-lemmata)))

(deftest conversion
  (is (every? (comp #{"lexeme"} :type)
              (lexemes (wdqs/query-vocab! lex/vocab)))))

(deftest test-wb-import
  (is @(fixture/with-test-wb-login
         (fn [config]
           (->> (lexemes (db/query-vocab!))
                (entity/import! config))))))

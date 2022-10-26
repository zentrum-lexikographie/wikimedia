(ns dwds.wikidata.coverage-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dwds.wikidata.fixture :as fixture]))

(use-fixtures :once fixture/wikipedia-tokens)

(def initial-coverage
  {:total-tokens   0
   :total-forms    0
   :covered-tokens 0
   :covered-forms  0})

(defn update-coverage
  [covered? coverage [token freq]]
  (let [covered? (covered? token)]
    (cond-> coverage
      :always  (update :total-forms inc)
      :always  (update :total-tokens + freq)
      covered? (update :covered-forms inc)
      covered? (update :covered-tokens + freq))))

(defn measure-wikipedia-coverage
  [covered?]
  (let [rf     (partial update-coverage covered?)
        tokens (fixture/get-wikipedia-tokens)
        c      (reduce rf initial-coverage tokens)]
    (assoc c
           :forms-pct (float (/ (:covered-forms c) (:total-forms c)))
           :tokens-pct (float (/ (:covered-tokens c) (:total-tokens c))))))

(deftest ^:coverage increased-coverage
  (let [forms    #{}
        coverage (measure-wikipedia-coverage forms)]
    (is (< 0.8 (:tokens-pct coverage)))
    (is (< 0.2 (:forms-pct coverage)))))

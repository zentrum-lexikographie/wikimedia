(ns dwds.wikidata.dwdsbot
  (:require [dwds.wikidata.env :as env]
            [clojure.string :as str]
            [dwds.wikidata.db :as db]
            [clojure.data.csv :as csv]
            [taoensso.timbre :as log]))

(defonce id->lemma
  (transduce
   (comp (map (partial into {}))
         (partition-all 1024)
         (mapcat #(pmap (comp read-string
                              :wikidata_lexeme/entity) %))
         (map (juxt :id #(get-in % [:lemmas :de :value]))))
   conj {}
   (db/plan ["select * from wikidata_lexeme"])))

(defonce edits
  (into []
        (comp
         (mapcat #(get-in % [:body :query :usercontribs]))
         (map #(select-keys % [:title :timestamp :comment]))
         (remove (comp #(str/includes? % "Sample Form Import") :comment))
         (map #(update % :title str/replace #"^Lexeme:" ""))
         (map (fn [{:keys [title comment] :as edit}]
                (-> (->> (condp #(str/includes? %2 %1) comment
                           "Property:P5185"             :genus
                           "Form Import"                :forms
                           "wbeditentity-create-lexeme" :lexeme
                           :other)
                         (assoc edit :category))
                    (assoc :lemma (id->lemma title title)))))
         (remove (comp #{:other} :category)))
        (env/api-requests!
         {:list        "usercontribs"
          :ucuser      "DwdsBot"
          :ucnamespace "146"
          :uclimit     "500"}
         10000)))


(defonce lexeme->edits
  (reduce
   (fn [m {:keys [lemma title category]}]
     (-> m
         (update-in [title :category] (fnil conj #{}) category)
         (assoc-in [title :lemma] lemma)))
   {}
   edits))

(defn edits->csv
  [& _]
  (->>
   lexeme->edits
   (map (fn [[id m]] (assoc m :id id)))
   (sort-by #(parse-long (subs (% :id) 1)))
   (map (fn [{:keys [id category :lemma]}]
          [id lemma
           (if (or (category :lexeme) (category :genus)) "1" "0")
           (if (category :forms) "1" "0")]))
   (cons ["Wikidata Lexeme ID" "Lemma"
          "Lexeme Contribution" "Forms Contribution"])
   (csv/write-csv *out*)))

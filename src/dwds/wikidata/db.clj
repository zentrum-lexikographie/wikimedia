(ns dwds.wikidata.db
  (:require
   [dwds.wikidata.env :refer [db]]
   [dwds.wikidata.dwdsmor :as dwdsmor]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as jdbc.sql]
   [julesratte.wikidata.lexemes :as wd.lexemes]
   [taoensso.timbre :as log]))

(require 'next.jdbc.date-time)

(defn pr-edn
  [v]
  (binding [*print-length*   nil
            *print-dup*      nil
            *print-level*    nil
            *print-readably* true]
    (pr v)))

(defn pr-edn-str
  [v]
  (with-out-str (pr-edn v)))

(def execute!
  (partial jdbc/execute! db))

(def plan
  (partial jdbc/plan db))

(def lex-cat->pos
  {"Q34698"  "+ADJ"
   "Q380057" "+ADV"
   "Q1084"   "+NN"
   "Q24905"  "+V"})

(def pos->lex-cat
  (reduce-kv (fn [m lex-cat pos] (assoc m pos lex-cat)) {} lex-cat->pos))

(def pos-of-interest?
  (into #{} (vals lex-cat->pos)))

(defn lexeme->db
  [{{lemma :de} :lemmas lex-cat :lexicalCategory
    id :id last-modified :modified :as lexeme}]
  (when-let [pos (get lex-cat->pos lex-cat)]
    (when (and lemma (<= (count lemma) 256))
      (log/debugf "WD: [%10s][%s][%4s] %s" id last-modified pos lemma)
      (list [id lemma pos last-modified (pr-edn-str lexeme)]))))

(defn insert-wikidata-lexemes!
  [lexemes]
  (with-open [c (jdbc/get-connection db)]
    (jdbc/execute! c ["drop table if exists wikidata_lexeme"])
    (jdbc/execute! c [(str "create table if not exists wikidata_lexeme ("
                           "id varchar(32) not null, "
                           "lemma varchar(256) not null,"
                           "pos char(4) not null,"
                           "last_modified timestamp not null,"
                           "entity text not null"
                           ")")])
    (doseq [batch (partition-all 1024 (mapcat lexeme->db lexemes))]
      (jdbc/with-transaction [tx c]
        (jdbc.sql/insert-multi!
         tx
         :wikidata_lexeme
         [:id :lemma :pos :last_modified :entity]
         batch)))))

(defn valid-wikidata-lemma?
  [s]
  (re-seq #"^[0-9a-zA-ZÄÉÖÜßàáâãäåçèéêîñóôöøùúûüŒœř\₀\₂'…\!\,\-\.\?\ ]+$" s))

(defn form->db
  [{lemma "analysis" pos "pos" lemma-index "lidx" paradigm-index "pidx"
    orth "orthinfo" cap? "charinfo" meta "metainfo"
    :as form}]
  (when (and (pos-of-interest? pos)
             (valid-wikidata-lemma? lemma)
             (every? nil? [lemma-index paradigm-index orth cap? meta]))
    (log/debugf "DWDSmor: [%6s] %s" (form "pos") (form "spec"))
    (list [(form "analysis")
           (form "pos")
           (form "spec")
           (form "inflected")
           (form "gender")
           (form "case")
           (form "person")
           (form "number")
           (form "nonfinite")
           (form "tense")
           (form "degree")
           (form "mood")
           (form "function")
           (form "auxiliary")])))

(defn insert-dwdsmor-index!
  [forms]
  (with-open [c (jdbc/get-connection db)]
    (jdbc/execute! c ["drop table if exists dwdsmor_index"])
    (jdbc/execute! c [(str "create table if not exists dwdsmor_index ("
                           "analysis varchar(256) not null,"
                           "pos char(4) not null,"
                           "spec varchar(256) not null,"
                           "inflected varchar(256) not null,"
                           "gender char(7),"
                           "casus char(7),"
                           "person char(1),"
                           "number char(6),"
                           "nonfinite char(4),"
                           "tense char(4),"
                           "degree char(4),"
                           "mood char(4),"
                           "funct char(10),"
                           "aux char(5)"
                           ")")])
    (doseq [batch (partition-all 1024 (mapcat form->db forms))]
      (jdbc/with-transaction [tx c]
        (jdbc.sql/insert-multi!
         tx
         :dwdsmor_index
         [:analysis :pos :spec :inflected
          :gender :casus :person :number :nonfinite :tense :degree :mood
          :funct :aux]
         batch)))))

(defn create-indices
  []
  (execute! [(str "create index if not exists wikidata_id "
                  "on wikidata_lexeme (id)")])
  (execute! [(str "create index if not exists wikidata_lemma_pos "
                  "on wikidata_lexeme (lemma, pos)")])
  (execute! [(str "create index if not exists dwdsmor_lemma_pos "
                  "on dwdsmor_index (analysis, pos, spec)")]))

(defn build!
  [& _]
  (with-open [r (wd.lexemes/read-dump)]
    (->> (wd.lexemes/parse-dump r)
         (insert-wikidata-lexemes!)))
  (with-open [r (dwdsmor/index-reader)]
    (->> (dwdsmor/parse-index r)
         (insert-dwdsmor-index!)))
  (create-indices))

(defn wikidata-homographs
  []
  (->> (execute! [(str "select lemma, pos from wikidata_lexeme "
                       "group by lemma, pos "
                       "having count(*) > 1")])
       (map (juxt :wikidata_lexeme/lemma :wikidata_lexeme/pos))
       (into (sorted-set))))

(defn wikidata-homograph?
  [homograph-set {:wikidata_lexeme/keys [lemma pos]}]
  (and lemma pos (homograph-set [lemma pos])))

(defn read-wikidata-entity
  [m]
  (update m :wikidata_lexeme/entity (fnil read-string "{}")))

(def wikidata-columns
  [:wikidata_lexeme/id
   :wikidata_lexeme/lemma
   :wikidata_lexeme/pos
   :wikidata_lexeme/last_modified
   :wikidata_lexeme/entity])

(def paradigm-columns
  (into wikidata-columns [:dwdsmor_index/lidx
                          :dwdsmor_index/pidx]))

(defn dissoc-wikidata-columns
  [m]
  (reduce dissoc m paradigm-columns))

(defn group-paradigm
  [[{wd-id :wikidata_lexeme/id :as form} :as paradigm]]
  (cond-> {:dwdsmor (into [] (map dissoc-wikidata-columns) paradigm)}
    wd-id (assoc :wikidata (select-keys form wikidata-columns))))

(defn query-xf
  [xform]
  (comp (map (partial into {}))
        (remove (partial wikidata-homograph? (wikidata-homographs)))
        (partition-all 1024) (mapcat (partial pmap read-wikidata-entity))
        (partition-by (juxt :dwdsmor_index/analysis :dwdsmor_index/pos))
        (map group-paradigm)
        xform))

(defn query
  ([xform f init]
   (query "" xform f init))
  ([where-clause xform f init]
   (transduce
    (query-xf xform)
    f init
    (plan [(str "select "
                "wd.*, di.* "
                "from dwdsmor_index di "
                "left join wikidata_lexeme wd "
                "on di.analysis = wd.lemma and di.pos = wd.pos "
                where-clause
                " order by di.analysis, di.pos, di.spec")]))))

(comment
  (query
   (take 10)
   conj []))


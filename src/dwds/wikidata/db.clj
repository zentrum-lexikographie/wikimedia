(ns dwds.wikidata.db
  (:require
   [dwds.wikidata.env :as env]
   [dwds.wikidata.lex :as lex]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as jdbc.rs]))

(def spec
  {:dbtype         "mysql"
   :host           (env/get-var "WIKIBASE_DB_HOST" "localhost")
   :dbname         (env/get-var "WIKIBASE_DB_NAME" "wikibase")
   :user           (env/get-var "WIKIBASE_DB_USER" "wikibase")
   :password       (env/get-var "WIKIBASE_DB_PASSWORD" "wikibase")
   :serverTimezone "UTC"})

(defn query!
  [q]
  (jdbc/execute! spec (sql/format q)
                 {:builder-fn jdbc.rs/as-unqualified-kebab-maps}))

(defn str->bytes
  [^String s]
  (.getBytes s "UTF-8"))

(defn bytes->str
  [^bytes bs]
  (String. bs "UTF-8"))

(defn entity-id-query
  [labels]
  {:select [[:wbit.wbit-item-id :id] [:wbx.wbx-text :label]]
   :from   [[:wbt-item-terms :wbit]]
   :join   [[:wbt_term_in_lang :wbtl]
            [:= :wbit.wbit-term-in-lang-id :wbtl.wbtl-id]
            [:wbt-text_in_lang :wbxl]
            [:= :wbtl.wbtl-text-in-lang-id :wbxl.wbxl-id]
            [:wbt-text :wbx]
            [:= :wbxl.wbxl-text-id :wbx.wbx-id]]
   :where  [:and
            [:= :wbxl.wbxl-language "en"]
            [:in :wbx.wbx-text (map str->bytes labels)]]})

(defn query-vocab!
  []
  (->>
   (query! (entity-id-query lex/vocab))
   (into (sorted-map) (map (juxt (comp bytes->str :label) :id)))))




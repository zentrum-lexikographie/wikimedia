(ns dwds.wikidata.fixture
  (:require
   [clojure.java.io :as io]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.entity :as entity]
   [dwds.wikidata.lex :as lex]
   [dwds.wikidata.wdqs :as wdqs]
   [julesratte.auth :as mw.auth]
   [julesratte.client :as mw.client]
   [manifold.deferred :as d]
   [dwds.wikidata.env :as env]))

(def wikidata-client-config
  (mw.client/config-for-endpoint
   (mw.client/endpoint-url "www.wikidata.org")))

(defn extract-vocab-entities
  [entities]
  (into [] (map entity/state->data) (vals entities)))

(defn query-vocab-entities!
  [ids]
  @(d/chain (entity/get! wikidata-client-config ids) extract-vocab-entities))

(def test-wb-client-url
  (env/get-var "WIKIBASE_API_URL" (mw.client/endpoint-url "http" "localhost")))

(def test-wb-client-user
  (env/get-var "WIKIBASE_API_USER" "Admin"))

(def test-wb-client-password
  (env/get-var "WIKIBASE_API_PASSWORD" "secret1234"))

(def test-wb-client-config
  (->
   test-wb-client-url
   (mw.client/config-for-endpoint (mw.client/create-session-client))
   (assoc :warn->error? false)))


(defn with-test-wb-login
  [f]
  (mw.auth/with-login-session
    test-wb-client-config
    test-wb-client-user
    test-wb-client-password
    f))

(defn import-test-wb-vocab!
  []
  (let [vocab-ids (wdqs/query-vocab! lex/vocab)
        vocab-ids (map (partial str "Q") (vals vocab-ids))
        entities  (query-vocab-entities! vocab-ids)]
    @(with-test-wb-login (fn [config] (entity/import! config entities)))))

(defn ensure-test-wb-vocab!
  []
  (when (empty? (db/query-vocab!)) (import-test-wb-vocab!)))

(defn test-wb
  [f]
  (ensure-test-wb-vocab!)
  (f))

(def lex-dir
  (io/file "../zdl-wb"))

(defn lex-lemmata
  []
  (->> (lex/lemmata lex-dir)
       #_(filter (comp (partial = "verb") :pos))
       #_(random-sample 0.1)
       (take 10)))

(comment
  @(entity/get-one! test-wb-client-config "L92"))


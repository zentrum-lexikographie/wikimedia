(ns dwds.wikidata.fixture
  (:require
   [clojure.java.io :as io]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.entity :as entity]
   [dwds.wikidata.env :as env]
   [dwds.wikidata.lex :as lex]
   [dwds.wikidata.wdqs :as wdqs]
   [dwds.wikidata.wikipedia-corpus :as wikipedia-corpus]
   [julesratte.auth :as mw.auth]
   [julesratte.client :as mw.client]
   [manifold.deferred :as d]
   [dwds.wikidata.dump :as dump]))

(def wikidata-client-config
  (mw.client/config-for-endpoint
   (mw.client/endpoint-url "www.wikidata.org")))

(defn extract-vocab-entities
  [entities]
  (into [] (map entity/state->data) (vals entities)))

(defn query-vocab-entities!
  [ids]
  @(d/chain (entity/get! wikidata-client-config ids) extract-vocab-entities))

(def test-wb-client-config
  (->
   (str env/test-api-uri)
   (mw.client/config-for-endpoint (mw.client/create-session-client))
   (assoc :warn->error? false)))

(defn with-test-wb-login
  [f]
  (mw.auth/with-login-session
    test-wb-client-config
    env/test-api-user
    env/test-api-password
    f))

(defn import-test-wb-vocab!
  []
  (let [vocab-ids (map (partial str "Q") (vals wdqs/vocab))
        entities  (query-vocab-entities! vocab-ids)]
    @(with-test-wb-login (fn [config] (entity/import! config entities)))))

(defn ensure-test-wb-vocab!
  []
  (when (empty? (db/query-vocab!)) (import-test-wb-vocab!)))

(defn test-wb
  [f]
  (ensure-test-wb-vocab!)
  (f))

(def verb-xf
  (comp (filter (comp (partial = "verb") :pos))
        (take 10)))

(def plt-xf
  (comp (filter :plt?)
        (take 5)))

(def genera-xf
  (comp (filter (comp (partial < 1) count :genera))
        (take 5)))

(def other-xf
  (comp (filter :other)
        (take 5)))

(def random-xf
  (comp (take 10)))

(defn lex-lemmata
  []
  (let [lemmata (lex/lemmata env/lex-dir)
        verbs   (sequence verb-xf lemmata)
        plts    (sequence plt-xf lemmata)
        genera  (sequence genera-xf lemmata)
        other   (sequence other-xf lemmata)
        rands   (sequence random-xf lemmata)]
    (into [] (concat verbs plts genera other rands))))


(def wikipedia-tokens-dump
  (doto (io/file "test-data" "wikipedia-de-tokens.edn")
    (.. (getParentFile) (mkdirs))))

(defn get-wikipedia-tokens
  []
  (read-string (slurp wikipedia-tokens-dump)))

(defn ensure-wikipedia-tokens!
  []
  (when-not (.isFile wikipedia-tokens-dump)
    (spit wikipedia-tokens-dump (pr-str (wikipedia-corpus/tokens)))))

(defn wikipedia-tokens
  [f]
  (ensure-wikipedia-tokens!)
  (f))

(defn lexeme->forms
  [{:keys [lemmas forms]}]
  (concat
   (for [[_lang {lemma :value}] lemmas]
     lemma)
   (for [{reprs :representations} forms
         [_lang {form :value}]    reprs]
     form)))

(defn wikidata-forms
  []
  (with-open [r (dump/lexemes-dump-reader)]
    (into (sorted-set) (mapcat lexeme->forms) (dump/parse-lexemes r))))

(comment
  (count (wikidata-forms)))

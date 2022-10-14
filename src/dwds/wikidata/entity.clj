(ns dwds.wikidata.entity
  "Handling of Wikibase entities (items/lexemes)."
  (:require [clojure.walk :refer [postwalk]]
            [julesratte.auth :as mw.auth]
            [julesratte.client :as mw.client]
            [manifold.deferred :as d]
            [manifold.stream :as s]))

(def statement?
  (comp (partial = "statement") :type))

(def has-snaks?
  (some-fn :mainsnak (comp not-empty :snaks)))

(defn clear-snak-order
  "Clear removed snaks from their respective ordering."
  [order-k snak-k m]
  (if-let [order (get m order-k)]
    (let [snaks (into #{} (map name (keys (get m snak-k))))]
      (assoc m order-k (not-empty (into [] (filter snaks order)))))
    m))

(defn prune-statements
  "Remove statements without snaks."
  [v]
  (let [v (if (statement? v) (when (has-snaks? v) v) v)]
    (cond-> v
      (map? v) (->> (clear-snak-order :snaks-order :snaks)
                    (clear-snak-order :qualifiers-order :qualifiers)))))

(def vector??
  "When walking over a data structure, map entries and vectors look alike."
  (every-pred vector? (complement map-entry?)))

(def coll-and-empty?
  (every-pred coll? empty?))

(defn dissoc-nil-val
  [m [k v]]
  (cond-> m (nil? v) (dissoc k)))

(defn prune
  "Remove `nil` map vals and empty collections."
  [v]
  (let [v (if (map? v) (reduce dissoc-nil-val v v) v)
        v (cond->> v (vector?? v) (into [] (remove nil?)))
        v (when-not (coll-and-empty? v) v)]
    v))

(defn remove-wikibase-entities
  "Removes references to entities."
  [v]
  (when-not (= "wikibase-entityid" (get-in v [:datavalue :type])) v))


(def lang-subset
  #{:de :en :fr})

(defn select-lang-subset
  "Pick only a subset of all entries in multi-language text values."
  [v]
  (if (and (map-entry? v) (#{:labels :aliases :descriptions} (key v)))
    [(key v) (select-keys (val v) lang-subset)]
    v))

(def claim-subset
  #{:P31 :P279 :P646 :P910 :P1709 :P2263 :P2671 :P2888})

(defn select-claim-subset
  "Pick only a subset of all claims."
  [v]
  (if (and (map-entry? v) (= :claims (key v)))
    [:claims (select-keys (val v) claim-subset)]
    v))

(defn remove-remote-data
  "Remove entity data representing state that is specific to a particular Wikibase instance."
  [v]
  (cond-> v (map? v) (dissoc :id :hash :pageid :ns
                             :lastrevid :modified
                             :title :sitelinks)))

(def state->data
  "Cleans a data structure, removing all information pertinent to remote state in
  a particular Wikibase instance."
  (partial postwalk (comp prune
                          prune-statements
                          remove-remote-data
                          select-lang-subset
                          select-claim-subset
                          remove-wikibase-entities)))

(defn assert-edit-successful
  [response]
  (if-not (= 1 (get-in response [:body :success]))
    (d/error-deferred (ex-info "Error while creating entity" response))
    response))

(defn request-create!
  [config {:keys [type] :as entity} csrf-token]
  (->
   (->> (mw.client/request-with-params
         :action "wbeditentity"
         :new type
         :token csrf-token
         :bot "true"
         :data (mw.client/write-json entity))
        (mw.client/request! config))
   (d/chain assert-edit-successful)))

(defn create!
  [config entity]
  (d/chain
   (mw.auth/query-csrf-token! config)
   (partial request-create! config entity)))

(defn get!
  [config ids]
  (->
   (->>
    (mw.client/request-with-params :action "wbgetentities" :ids ids)
    (mw.client/request! config))
   (d/chain (comp :entities :body))))

(defn get-one!
  [config id]
  (d/chain (get! config #{id}) (comp first vals)))

(defn handle-import-success
  [results response]
  (swap! results update :success (fnil conj []) response)
  true)

(defn handle-import-error
  [results ex]
  (swap! results update :errors (fnil conj []) ex)
  false)

(defn handle-import-results
  [results _]
  (let [results (deref results)]
    (cond-> results (:errors results) d/error-deferred)))

(defn do-import!
  [wb-client-config results entity]
  (-> (create! wb-client-config entity)
      (d/chain (partial handle-import-success results))
      (d/catch (partial handle-import-error results))))

(defn import!
  [wb-client-config entities]
  (let [results    (atom {})
        do-import! (partial do-import! wb-client-config results)
        entities   (s/->source entities)]
    (-> (s/consume-async do-import! entities)
        (d/chain (partial handle-import-results results)))))

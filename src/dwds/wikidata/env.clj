(ns dwds.wikidata.env
  (:require
   [clojure.string :as str]
   [com.potetm.fusebox.rate-limit :as rl :refer [with-rate-limit]]
   [julesratte.client :as jr])
  (:import
   (io.github.cdimascio.dotenv Dotenv)))

(def ^Dotenv dot-env
  (.. (Dotenv/configure) (ignoreIfMissing) (load)))

(defn get-var
  ([k]
   (get-var k nil))
  ([k dv]
   (or (System/getenv k) (.get dot-env k) dv)))

(def huggingface-token
  (get-var "HF_TOKEN" ""))

(def db
  {:dbtype "sqlite"
   :dbname "lexemes.db"})

(def api-endpoint
  (jr/api-url "www.wikidata.org"))

(def api-rate-limit
  (rl/init {::rl/bucket-size     1
            ::rl/period-ms       (long (/ 60000 80))
            ::rl/wait-timeout-ms 10000}))

(defn api-request!
  [& args]
  (with-rate-limit api-rate-limit
    (apply jr/request! api-endpoint args)))

(defn api-csrf-token
  [& args]
  (with-rate-limit api-rate-limit
    (apply jr/csrf-token api-endpoint args)))

(def login
  {:url      api-endpoint
   :user     (get-var "API_LOGIN_USER" "DwdsBot@DwdsBot")
   :password (get-var "API_LOGIN_PASSWORD" "DwdsBot@DwdsBot")})

(def bot-signature
  "DwdsBot")

(defn edit-group-signature
  []
  (str/replace (Long/toHexString (. (java.util.Random.) (nextLong))) #"-" ""))

(defn edit-group-link
  []
  (format "[[:toolforge:editgroups/b/%s/%s|details]]"
          bot-signature (edit-group-signature)))

(defn edit-summary
  ([summary]
   (edit-summary summary (edit-group-link)))
  ([summary edit-group-link]
   (format "%s (%s)" summary edit-group-link)))



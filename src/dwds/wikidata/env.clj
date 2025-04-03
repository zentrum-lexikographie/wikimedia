(ns dwds.wikidata.env
  (:require
   [julesratte.client :as jr.client])
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
  (jr.client/api-endpoint "www.wikidata.org"))

(def login
  {:url      api-endpoint
   :user     (get-var "API_LOGIN_USER" "DwdsBot@DwdsBot")
   :password (get-var "API_LOGIN_PASSWORD" "DwdsBot@DwdsBot")})

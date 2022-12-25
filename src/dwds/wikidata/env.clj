(ns dwds.wikidata.env
  (:require
   [lambdaisland.uri :refer [uri]]
   [clojure.java.io :as io])
  (:import
   (io.github.cdimascio.dotenv Dotenv)))

(def ^Dotenv dot-env
  (.. (Dotenv/configure) (ignoreIfMissing) (load)))

(defn get-var
  ([k]
   (get-var k nil))
  ([k dv]
   (or (System/getenv k) (.get dot-env k) dv)))

(def lex-dir
  (io/file (get-var "DWDS_LEX_DIR" "zdl-wb")))

(def db
  {:dbtype         "mysql"
   :host           (get-var "WIKIBASE_DB_HOST" "localhost")
   :dbname         (get-var "WIKIBASE_DB_NAME" "wikibase")
   :user           (get-var "WIKIBASE_DB_USER" "wikibase")
   :password       (get-var "WIKIBASE_DB_PASSWORD" "wikibase")
   :serverTimezone "UTC"})

(def api-uri
  (uri (get-var "WIKIBASE_API_URL" "http://localhost/w/api.php")))

(def api-user
  (get-var "WIKIBASE_API_USER" "Admin"))

(def api-password
  (get-var "WIKIBASE_API_PASSWORD" "secret1234"))

(def test-api-uri
  (uri (get-var "WIKIBASE_TEST_API_URL" "http://localhost/w/api.php")))

(def test-api-user
  (get-var "WIKIBASE_TEST_API_USER" "Admin"))

(def test-api-password
  (get-var "WIKIBASE_TEST_API_PASSWORD" "secret1234"))

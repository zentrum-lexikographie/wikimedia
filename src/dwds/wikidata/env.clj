(ns dwds.wikidata.env
  (:import io.github.cdimascio.dotenv.Dotenv))

(def ^Dotenv dot-env
  (.. (Dotenv/configure) (ignoreIfMissing) (load)))

(defn get-var
  ([k]
   (get-var k nil))
  ([k dv]
   (or (System/getenv k) (.get dot-env k) dv)))

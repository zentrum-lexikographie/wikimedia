(ns dwds.wikidata.http
  (:require [clojure.java.io :as io])
  (:import (java.io File)))

(defn curl-download!
  [url f]
  (let [cmd  ["curl" "-o" (str f) (str url)]
        curl (.. (ProcessBuilder. (into-array String cmd))
                 (redirectInput java.lang.ProcessBuilder$Redirect/INHERIT)
                 (redirectOutput java.lang.ProcessBuilder$Redirect/INHERIT)
                 (redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                 (start))]
    (.waitFor ^Process curl)
    (when-not (zero? (.exitValue ^Process curl))
      (throw (ex-info "Error while downloading via curl" {:url url :file f})))))

(defn data-download!
  [url file-name]
  (let [dir  (doto (io/file "data") (.mkdirs))
        file (io/file dir file-name)]
    (when-not (.isFile ^File file)
      (curl-download! url file))
    file))

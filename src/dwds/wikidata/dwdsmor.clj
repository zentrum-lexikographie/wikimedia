(ns dwds.wikidata.dwdsmor
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [dwds.wikidata.env :as env]
   [hato.client :as http])
  (:import
   (org.apache.commons.compress.compressors.xz XZCompressorInputStream)))

(defn index-reader
  []
  (-> (str "https://huggingface.co/zentrum-lexikographie/dwdsmor-dwds/"
           "resolve/main/index.csv.lzma")
      (http/get {:oauth-token env/huggingface-token
                 :http-client (http/build-http-client {:redirect-policy :normal})
                 :as          :stream})
      (get :body)
      (XZCompressorInputStream.)
      (io/reader)))

(defn parse-index
  [reader]
  (let [[header & records] (csv/read-csv reader)]
    (map (comp (partial zipmap header) (partial map not-empty)) records)))

(comment
  (with-open [r (index-reader)]
    (->> (parse-index r)
         (take 10)
         (vec))))

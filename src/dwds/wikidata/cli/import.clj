(ns dwds.wikidata.cli.import
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.entity :as entity]
   [dwds.wikidata.lex :as lex]
   [dwds.wikidata.lexeme :as lexeme]
   [dwds.wikidata.wdqs :as wdqs]
   [julesratte.auth :as mw.auth]
   [julesratte.client :as mw.client]
   [lambdaisland.uri :as uri]
   [taoensso.timbre :as log]))

(def cli-options
  [["-d" "--debug"
    :desc "print debugging information"]
   ["-l" "--lexemes CSV_FILE"
    :desc "CSV dump of existing WikiData lexemes"
    :parse-fn io/file]
   ["-b" "--base-url URL"
    :desc "Wikibase Action API URL"
    :parse-fn uri/uri
    :default (uri/uri "http://localhost/w/api.php")]
   ["-u" "--user WIKIBASE_USER"
    :desc "Wikibase Login/Bot user"
    :default "Admin"]
   ["-p" "--password WIKIBASE_PASSWORD"
    :desc "Wikibase Login/Bot password"
    :default "secret1234"]
   ["-s" "--source DWDS_WB_DIR"
    :desc "directory containing DWDS dictionary articles"]
   ["-h" "--help"]])

(defn usage
  [{:keys [summary]}]
  (str/join
   \newline
   ["Import DWDS dictionary data into WikiData."
    ""
    "Usage: clojure -M -m dwds.wikidata.cli.import <options...>"
    ""
    "Options:"
    summary
    ""]))

(defn error-msg
  [errors opts]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline (conj errors "" (usage opts)))))

(defn exit!
  ([status]
   (System/exit status))
  ([status msg]
   (println msg)
   (System/exit status)))

(defn error-exit!
  [opts msgs]
  (exit! 1 (error-msg msgs opts)))

(defn parse-args
  "Parses command line arguments and checks for required parameters."
  [args]
  (let [{:keys [options errors] :as opts} (parse-opts args cli-options)
        {:keys [lexemes source]}          options]
    (cond
      (:help options) (exit! 0 (usage opts))
      (seq errors)    (error-exit! opts errors)
      (nil? lexemes)  (error-exit! opts ["No WikiData lexemes specified."])
      (nil? source)   (error-exit! opts ["No DWDS source dir specified."]))
    opts))

(defn configure-logging!
  "Translates `--debug` CLI option into TRACE-level logging."
  [{{:keys [debug]} :options}]
  (let [stderr-appender (log/println-appender {:stream :std-err})]
    (log/merge-config!
     {:min-level (if debug :trace :debug)
      :appenders {:println stderr-appender}})))

(defn with-wb
  [{:keys [user password] {:keys [scheme host path]} :base-url} f]
  (-> (mw.client/endpoint-url scheme host path)
      (mw.client/config-for-endpoint (mw.client/create-session-client))
      (assoc :warn->error? false)
      (mw.auth/with-login-session user password f)))

(defn get-vocab!
  [{{:keys [host]} :base-url}]
  (if (= "localhost" host) (db/query-vocab!) (wdqs/query-vocab! lex/vocab)))

(defn lexemes-csv->set
  [f]
  (with-open [r (io/reader f :encoding "UTF-8")]
    (into (sorted-set)
          (map (fn [[_ _ lemma]] lemma))
          (csv/read-csv r))))

(defn -main
  [& args]
  (let [args (parse-args args)]
    (configure-logging! args)
    (try
      (let [options  (args :options)
            existing (lexemes-csv->set (options :lexemes))
            vocab    (get-vocab! options)
            lemmata  (lex/lemmata (options :source))
            lemmata  (remove (comp existing :lemma) lemmata)
            lexemes  (lexeme/lex->wb vocab lemmata)]
        (deref (with-wb options #(entity/import! %1 lexemes))))
      (catch Throwable t
        (log/error t "Error while importing lexemes")
        (exit! 2)))))

;; $ clojure -M -m dwds.wikidata.cli.import -d --lexemes lexemes.csv --source ../zdl-wb
;; 2022-10-14T15:15:35.390Z textmaschine TRACE [dwds.wikidata.cli.import:98] - Lemmata to import: 202,008

(ns dwds.wikidata.import
  (:require
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [dwds.wikidata.db :as db]
   [dwds.wikidata.dump :refer [lexeme->csv]]
   [dwds.wikidata.entity :as entity]
   [dwds.wikidata.lex :as lex]
   [dwds.wikidata.lexeme :as lexeme]
   [dwds.wikidata.log]
   [dwds.wikidata.wdqs :as wdqs]
   [julesratte.auth :as mw.auth]
   [julesratte.client :as mw.client]
   [lambdaisland.uri :as uri]
   [taoensso.timbre :as log]
   [manifold.deferred :as d]
   [dwds.wikidata.env :as env]))

(def cli-options
  [["-b" "--base-url URL"
    :desc "Wikibase Action API URL"
    :parse-fn uri/uri
    :default env/api-uri
    :default-desc "$WIKIBASE_API_URL"]
   ["-u" "--user WIKIBASE_USER"
    :desc "Wikibase Login/Bot user"
    :default env/api-user
    :default-desc "$WIKIBASE_API_USER"]
   ["-p" "--password WIKIBASE_PASSWORD"
    :desc "Wikibase Login/Bot password"
    :default env/api-password
    :default-desc "$WIKIBASE_API_PASSWORD"]
   ["-l" "--limit MAX_LEXEMES"
    :desc "Maximum number of lexemes to import"
    :parse-fn parse-long
    :validate-fn pos?]
   ["-e" "--existing CSV_FILE"
    :desc "CSV dump of existing WikiData lexemes (option can be repeated)"
    :default []
    :default-desc ""
    :parse-fn io/file
    :assoc-fn #(update %1 %2 conj %3)]
   ["-s" "--source DWDS_WB_DIR"
    :desc "directory containing DWDS dictionary articles"]
   ["-d" "--debug"
    :desc "print debugging information"]
   ["-n" "--dry-run"
    :desc "do a test/dry run, not importing any data"]
   ["-h" "--help"]])

(defn usage
  [{:keys [summary]}]
  (str/join
   \newline
   [""
    "Import DWDS dictionary data into WikiData."
    ""
    "Usage: clojure -M:import <options...>"
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
        {:keys [existing source]}          options]
    (cond
      (:help options) (exit! 0 (usage opts))
      (seq errors)    (error-exit! opts errors)
      (nil? existing) (error-exit! opts ["No WikiData lexemes specified."])
      (nil? source)   (error-exit! opts ["No DWDS source dir specified."]))
    opts))

(defn with-csrf-token
  [f config]
  (-> (mw.auth/query-csrf-token! config)
      (d/chain (partial f config))))

(defn with-wb
  [{{:keys [user password] {:keys [scheme host path]} :base-url} :options} f]
  (log/infof "Importing into %s@%s" user host)
  (-> (mw.client/endpoint-url scheme host path)
      (mw.client/config-for-endpoint (mw.client/create-session-client))
      (assoc :warn->error? false)
      (mw.auth/with-login-session user password (partial with-csrf-token f))))

(defn read-vocab
  [{{{:keys [host]} :base-url} :options}]
  (let [vocab (if (= "localhost" host) (db/query-vocab!) wdqs/vocab)]
    (log/infof "Gather vocabulary for %s: %,d terms" host (count vocab))
    vocab))

(defn lexemes-csv->set
  [f]
  (with-open [r (io/reader f :encoding "UTF-8")]
    (into #{} (map (fn [[_ _ lemma]] lemma)) (csv/read-csv r))))

(defn exists?
  [exists? {:keys [lemma other]}]
  (or (exists? lemma) (some exists? (map :lemma other))))

(defn read-existing
  [{{:keys [existing]} :options}]
  (let [existing (into (sorted-set) (mapcat lexemes-csv->set) existing)]
    (log/infof "Filtering %,d existing lexemes" (count existing))
    existing))

(defn read-source
  [{{:keys [source]} :options}]
  (log/infof "Reading DWDS lexemes from %s" (str source))
  (lex/lemmata source))

(defn limit-lexemes
  [lexemes {{:keys [limit]} :options}]
  (when limit
    (log/infof "Limiting DWDS lexemes: max. %,d" limit))
  (cond->> lexemes limit (take limit)))

(defn entity->csv-out
  [{:keys [id] {{lemma :value} :de}:lemmas}]
  (csv/write-csv *out* [[id "de" lemma]])
  (flush))

(defn do-import!
  [lexemes wb-config csrf-token]
  (try
    (doseq [lexeme lexemes]
      (let [response (deref (entity/create! wb-config csrf-token lexeme))
            entity   (get-in response [:body :entity])]
        (Thread/sleep (+ 500 (rand-int 1000)))
        (entity->csv-out entity)))
    (catch Throwable t
      (d/error-deferred t))))

(defn -main
  [& args]
  (let [args     (parse-args args)
        trace?   (get-in args [:options :debug])
        dry-run? (get-in args [:options :dry-run])]
    (dwds.wikidata.log/configure! trace?)
    (try
      (let [vocab    (read-vocab args)
            existing (read-existing args)
            exists?  (partial exists? existing)
            lemmata  (read-source args)
            lemmata  (remove exists? lemmata)
            lexemes  (lexeme/lex->wb vocab lemmata)
            lexemes  (limit-lexemes lexemes args)]
        (if dry-run?
          (doseq [lexeme lexemes] (log/info (-> lexeme lexeme->csv vec)))
          (deref (with-wb args (partial do-import! lexemes)))))
      (catch Throwable t
        (log/error t "Error while importing lexemes")
        (exit! 2)))))

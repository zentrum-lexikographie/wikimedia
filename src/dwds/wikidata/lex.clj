(ns dwds.wikidata.lex
  "Extraction of lexeme data from the DWDS dictionary dataset."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.zip :as zip]
   [gremid.data.xml :as dx]
   [gremid.data.xml.zip :as dx.zip]
   [lambdaisland.uri :as uri]
   [lambdaisland.uri.normalize :as uri.normalize]))

(def lang
  "German")

(def source
  "DWDS-Wörterbuch")

(def plurale-tantum
  "plurale tantum")

(def parts-of-speech
  {"Adjektiv"               "adjective"
   "Adverb"                 "adverb"
;;   "Affix"                  "affix"
;;   "bestimmter Artikel"     "article"
;;   "Bruchzahl"              "numeral"
;;   "Demonstrativpronomen"   "demonstrative pronoun"
   "Eigenname"              "proper noun"
;;   "Imperativ"              "verb"
;;   "Indefinitpronomen"      "indefinite pronoun"
   "Interjektion"           "interjection"
;;   "Interrogativpronomen"   "interrogative pronoun"
;;   "Kardinalzahl"           "numeral"
;;   "Komparativ"             "adjective"
   "Konjunktion"            "conjunction"
;;   "Mehrwortausdruck"       "idiom"
;;   "Ordinalzahl"            "numeral"
   "partizipiales Adjektiv" "adjective"
   "partizipiales Adverb"   "adverb"
;;   "Partikel"               "interjection"
;;   "Personalpronomen"       "personal pronoun"
;;   "Possessivpronomen"      "possessive pronoun"
   "Präposition"            "preposition"
   "Präposition + Artikel"  "contraction"
;;   "Pronomen"               "pronoun"
;;   "Pronominaladverb"       "pronominal adverb"
;;   "Reflexivpronomen"       "reflexive pronoun"
;;   "Relativpronomen"        "relative pronoun"
;;   "reziprokes Pronomen"    "pronoun"
   "Substantiv"             "noun"
;;   "Superlativ"             "adjective"
   "Verb"                   "verb"})

(def genera
  {"fem."   "feminine"
   "mask."  "masculine"
   "neutr." "neuter"})

(def vocab
  (->> (mapcat vals [parts-of-speech genera])
       (concat [lang source plurale-tantum])
       (into (sorted-set))))

(defn xml-file?
  [^java.io.File f]
  (and (.isFile f) (.. f (getName) (endsWith ".xml"))))

(defn parse-xml-file
  [f]
  (dx/pull-all (dx/parse f)))

(dx/alias-uri :xdwds "http://www.dwds.de/ns/1.0")

(def text
  (comp not-empty str/trim #(str/replace % #"[\ \s]+" " ") dx.zip/text))

(def valid-repr?
  "Predicate of written representation with valid spelling."
  (complement
   (comp #{"U" "U_U" "U_NR" "R" "U_Falschschreibung"}
         #(dx.zip/attr % :Typ))))

(def genus-xf
  (comp
   (map genera)
   (remove nil?)))

(defn repr->map
  [repr]
  (let [lemma (text repr)
        hidx  (dx.zip/attr repr :hidx)]
    (cond-> {:lemma lemma} hidx (assoc :hidx hidx))))

(defn assoc-uri
  [{:keys [lemma hidx] :as m}]
  (->> (cond-> (uri.normalize/normalize-path lemma) hidx (str "#" hidx))
       (assoc m :uri)))

(defn extract-lemma-forms
  [form]
  (when-let [grammar (dx.zip/xml1-> form ::xdwds/Grammatik)]
    (let [pos      (dx.zip/xml1-> grammar ::xdwds/Wortklasse text)
          pos      (get parts-of-speech pos)
          num-pref (dx.zip/xml1-> grammar ::xdwds/Numeruspraeferenz text)
          plt?     (= "nur im Plural" num-pref)
          genera   (dx.zip/xml-> grammar ::xdwds/Genus text)
          genera   (into (sorted-set) genus-xf genera)]
      (when pos
        (let [reprs (dx.zip/xml-> form ::xdwds/Schreibung
                                  valid-repr? repr->map)
              frepr (first reprs)
              reprs (seq (rest reprs))
              ipa   (dx.zip/xml1-> form ::xdwds/Aussprache (dx.zip/attr :IPA))]
          (list
           (cond-> (-> frepr assoc-uri (assoc :pos pos))
             num-pref     (assoc :plt? plt?)
             ipa          (assoc :ipa ipa)
             reprs        (assoc :reprs (map :lemma reprs))
             (seq genera) (assoc :genera genera))))))))

(defn distinct-lemma-xf
  [rf]
  (let [seen (volatile! {})]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result {:keys [lemma hidx genera] :as m}]
       (let [k [lemma hidx genera]]
         (when-not (get @seen k)
           (vswap! seen assoc k true)
           (rf result m)))))))

(defn valid-wd-lemma?
  [{:keys [lemma]}]
  (and (re-find #"[a-zA-Z]" lemma) (not (str/includes? lemma "’"))))

(def lex-db-parse-xf
  (comp
   (filter xml-file?)
   (map parse-xml-file)
   (map zip/xml-zip)
   (mapcat #(dx.zip/xml-> % ::xdwds/DWDS ::xdwds/Artikel))
   (filter (dx.zip/attr= :Status "Red-f"))
   (mapcat #(dx.zip/xml-> % ::xdwds/Formangabe))
   (mapcat extract-lemma-forms)
   (filter :lemma)
   (filter valid-wd-lemma?)
   distinct-lemma-xf))

(defn lemmata
  [articles-dir]
  (sequence lex-db-parse-xf (file-seq (io/file articles-dir))))

(def base-url
  (uri/uri "https://www.dwds.de/wb/"))

(defn lemma->url
  [{:keys [uri]}]
  (str (uri/join base-url uri)))

(def lemma-re
  #"^[0-9a-zA-ZÄÉÖÜßàáâãäåçèéêîñóôöøùúûüŒœř\₀\₂'…\!\,\-\.\?\ ]+$")

(comment
  (count (distinct (map :lemma (filter :hidx (lemmata "../zdl-wb")))))

  (into (sorted-set) (mapcat (comp seq :lemma) (lemmata "../zdl-wb")))
  (take 10 (filter (comp (partial re-seq lemma-re) :lemma)
                   (lemmata "../zdl-wb")))
  (count (filter (comp #(str/includes? % "'") :lemma) (lemmata "../zdl-wb")))

  (count (filter :ipa (lemmata "../zdl-wb")))
  (time (count (lemmata "../zdl-wb"))))

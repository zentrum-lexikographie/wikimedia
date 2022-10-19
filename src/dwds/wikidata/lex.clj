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

(def parts-of-speech
  {"Adjektiv"               "adjective"
   "Adverb"                 "adverb"
   "Affix"                  "affix"
   "bestimmter Artikel"     "article"
   "Bruchzahl"              "numeral"
   "Demonstrativpronomen"   "demonstrative pronoun"
   "Eigenname"              "proper noun"
   "Imperativ"              "verb"
   "Indefinitpronomen"      "indefinite pronoun"
   "Interjektion"           "interjection"
   "Interrogativpronomen"   "interrogative pronoun"
   "Kardinalzahl"           "numeral"
   "Komparativ"             "adjective"
   "Konjunktion"            "conjunction"
   "Mehrwortausdruck"       "idiom"
   "Ordinalzahl"            "numeral"
   "partizipiales Adjektiv" "adjective"
   "partizipiales Adverb"   "adverb"
   "Partikel"               "interjection"
   "Personalpronomen"       "personal pronoun"
   "Possessivpronomen"      "possessive pronoun"
   "Präposition"            "preposition"
   "Präposition + Artikel"  "contraction"
   "Pronomen"               "pronoun"
   "Pronominaladverb"       "pronominal adverb"
   "Reflexivpronomen"       "reflexive pronoun"
   "Relativpronomen"        "relative pronoun"
   "reziprokes Pronomen"    "pronoun"
   "Substantiv"             "noun"
   "Superlativ"             "adjective"
   "Verb"                   "verb"})

(def genera
  {"fem."   "feminine"
   "mask."  "masculine"
   "neutr." "neuter"})

(def vocab
  (->> (mapcat vals [parts-of-speech genera])
       (concat [lang source])
       (into (sorted-set))))

(defn xml-file?
  [^java.io.File f]
  (and (.isFile f) (.. f (getName) (endsWith ".xml"))))

(defn parse-xml-file
  [f]
  (dx/pull-all (dx/parse f)))

(dx/alias-uri :xdwds "http://www.dwds.de/ns/1.0")

(def text
  (comp not-empty str/trim dx.zip/text))

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
    (let [pos    (dx.zip/xml1-> grammar ::xdwds/Wortklasse text)
          pos    (get parts-of-speech pos)
          genera (dx.zip/xml-> grammar ::xdwds/Genus text)
          genera (into (sorted-set) genus-xf genera)]
      (when pos
        (let [reprs (dx.zip/xml-> form ::xdwds/Schreibung
                                  valid-repr? repr->map)
              frepr (first reprs)
              reprs (seq (rest reprs))]
          (list
           (cond-> (-> frepr assoc-uri (assoc :pos pos))
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

(def lex-db-parse-xf
  (comp
   (filter xml-file?)
   (map parse-xml-file)
   (map zip/xml-zip)
   (mapcat #(dx.zip/xml-> % ::xdwds/DWDS ::xdwds/Artikel))
   (filter (dx.zip/attr= :Status "Red-f"))
   (mapcat #(dx.zip/xml-> % ::xdwds/Formangabe))
   (mapcat extract-lemma-forms)
   distinct-lemma-xf))

(defn lemmata
  [articles-dir]
  (sequence lex-db-parse-xf (file-seq (io/file articles-dir))))

(def base-url
  (uri/uri "https://www.dwds.de/wb/"))

(defn lemma->url
  [{:keys [uri]}]
  (str (uri/join base-url uri)))

(comment
  (count (distinct (map :lemma (filter :hidx (lemmata "../zdl-wb")))))
  (time (count (lemmata "../zdl-wb"))))

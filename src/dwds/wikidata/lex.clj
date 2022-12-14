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

(defn extract-forms
  [article]
  (for [form    (dx.zip/xml-> article ::xdwds/Formangabe)
        :let    [ipa   (dx.zip/xml1-> form ::xdwds/Aussprache
                                      (dx.zip/attr :IPA))
                 main? (dx.zip/xml1-> form (dx.zip/attr :Typ))
                 main? (= "Hauptform" main?)]
        grammar (dx.zip/xml-> form ::xdwds/Grammatik)
        pos     (dx.zip/xml-> grammar ::xdwds/Wortklasse text)
        :let    [pos (get parts-of-speech pos)]
        :when   pos
        :let    [plt?   (dx.zip/xml1-> grammar ::xdwds/Numeruspraeferenz text)
                 plt?   (= "nur im Plural" plt?)
                 genera (dx.zip/xml-> grammar ::xdwds/Genus text)
                 genera (into (sorted-set) genus-xf genera)]
        repr    (dx.zip/xml-> form ::xdwds/Schreibung valid-repr? repr->map)]
    (-> repr
        (assoc :main? main?)
        (assoc :genera genera)
        (assoc :plt? plt?)
        (assoc :pos pos)
        (assoc :ipa ipa))))

(defn group-by-lemma-pos
  [forms]
  (for [[_ forms] (group-by (juxt :lemma :hidx :pos) forms)]
    (reduce
     (fn [m {:keys [genera]}] (update m :genera into genera))
     (first forms)
     (rest forms))))

(defn aggregate-forms
  [forms]
  (for [[_ forms] (group-by (juxt :pos :genera) forms)]
    (let [main-form  (first (filter :main? forms))
          rest-forms (seq  (remove #{main-form} forms))]
      (cond-> main-form rest-forms (assoc :other rest-forms)))))

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

(def article->lemmata-xf
  (comp
   (mapcat (comp aggregate-forms group-by-lemma-pos extract-forms))
   (filter :lemma)
   (filter valid-wd-lemma?)
   distinct-lemma-xf))

(defn parse-article-file
  [f]
  (let [doc (parse-xml-file f)
        doc (zip/xml-zip doc)
        articles (dx.zip/xml-> doc ::xdwds/DWDS ::xdwds/Artikel)
        articles (filter (dx.zip/attr= :Status "Red-f") articles)]
    (sequence
     (comp article->lemmata-xf (map #(assoc % :file f)))
     articles)))

(defn lemmata
  [articles-dir]
  (sequence
   (comp
    (filter xml-file?)
    (mapcat parse-article-file))
   (file-seq (io/file articles-dir))))

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
  (sort-by second (frequencies (map :pos (lemmata "../zdl-wb"))))
  (take 10 (filter :other (lemmata "../../data/zdl/wb")))
  (time (count (lemmata "../zdl-wb"))))

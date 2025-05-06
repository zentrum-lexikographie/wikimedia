# Exporting Lexical Resources to WikiData

_Data Exchange Routines between the ZDL and Wikimedia Projects_

## Setup

Configure credentials via `.env`:

    # Hugging Face personal access token
    HF_TOKEN=…
    # DwdsBot login password
    API_LOGIN_PASSWORD=…

## Build database

    clojure -X dwds.wikidata.db/build!

## Import missing lexemes

    clojure -X dwds.wikidata.lexeme/import!

## Import missing forms

    clojure -X dwds.wikidata.form/import!

## License

Copyright 2022-2025 Gregor Middell.

This project is licensed under the GNU General Public License v3.0.

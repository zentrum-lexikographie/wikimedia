# DWDS Donation to Wikidata

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17431371.svg)](https://doi.org/10.5281/zenodo.17431371)

To increase visibility of its lexical data and allow for linking with
other lexical resources, the DWDS donates data about German lexemes to
Wikidata.

## Setup

Configure credentials via `.env`:

    # Hugging Face personal access token
    HF_TOKEN=…
    # DwdsBot login password
    API_LOGIN_PASSWORD=…

### Build lexeme

    curl -O https://dumps.wikimedia.org/other/wikibase/wikidatawiki/latest-lexemes.json.gz
    clojure -X dwds.wikidata.db/build!

### Import missing lexemes

    clojure -X dwds.wikidata.lexeme/import!

### Import missing forms

    clojure -X dwds.wikidata.form/import!

## License

Copyright 2022-2025 Gregor Middell.

This project is licensed under the GNU General Public License v3.0.

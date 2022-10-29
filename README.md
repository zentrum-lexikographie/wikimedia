# Exporting Lexical Resources to WikiData

## Prerequisites

* [Clojure v1.11](https://clojure.org/guides/getting_started): Export routines
  are written in Clojure.
* [Java (JDK) >= v11](https://openjdk.java.net/): Clojure, being a hosted
  language, requires a current Java runtime.
* [Docker](https://docs.docker.com/get-docker/): a local Wikibase setup for
  testing the import is provided via Docker containers.

## Importing Lexemes

Export a digest of existing WikiData lexemes (based on a [current
dump](https://dumps.wikimedia.org/wikidatawiki/entities/latest-lexemes.json.gz)):

```
$ clojure -M:existing >lexemes.csv
```

```
$ clojure -M:import\
    -l 1\
    -e lexemes.csv\
    -e lexemes.wikidata.csv\
    -s ../zdl-wb\
    >>lexemes.wikidata.csv 
```

## Testing

Start a containerized, local test setup of Wikibase/MySQL:
```
$ docker-compose up
```

Run lexeme import tests:

```
$ clojure -X:test
--- unit (clojure.test) ---------------------------
dwds.wikidata.lexeme-import-test
  conversion
  test-wb-import

2 tests, 2 assertions, 0 failures.
```

## Links

* https://www.wikidata.org/wiki/Wikidata:List_of_properties/all_in_one_table

## License

Copyright 2022 Gregor Middell.

This project is licensed under the GNU General Public License v3.0.

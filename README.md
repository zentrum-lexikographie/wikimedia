# Exporting Lexical Resources to WikiData

## Prerequisites

* [Clojure v1.11](https://clojure.org/guides/getting_started): Export routines
  are written in Clojure.
* [Java (JDK) >= v11](https://openjdk.java.net/): Clojure, being a hosted
  language, requires a current Java runtime.
* [Docker](https://docs.docker.com/get-docker/): a local Wikibase setup for
  testing the import is provided via Docker containers.

## Testing Lexeme Coverage

```
$ clojure -M:test:kaocha --focus-meta :coverage

--- unit (clojure.test) ---------------------------
dwds.wikidata.coverage-test
  increased-coverage FAIL FAIL

Randomized with --seed 396611200

FAIL in dwds.wikidata.coverage-test/increased-coverage (coverage_test.clj:34)
expected: (< 0.8 (:tokens-pct coverage))
  actual: (not (< 0.8 0.68013096))

FAIL in dwds.wikidata.coverage-test/increased-coverage (coverage_test.clj:35)
expected: (< 0.2 (:forms-pct coverage))
  actual: (not (< 0.2 0.1069699))
1 tests, 2 assertions, 2 failures.

[…]
```

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
$ clojure -M:test:kaocha
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

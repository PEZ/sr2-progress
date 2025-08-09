(ns sr2.dev
  "Tiny helpers to run project tests from an interactive REPL.
   Note: make sure the :test alias is on the classpath so test/ is visible."
  (:require [clojure.test :as t]))

(def ^:private test-namespaces
  '[sr2.nvram-extractor-test])

(def ^:private source-namespaces
  '[sr2.nvram-extractor])

(defn load-tests
  "Require all known test namespaces. Extend the vector when new tests are added."
  []
  (doseq [ns-sym test-namespaces]
    (require ns-sym :reload)))

(defn reload-sources
  "Force reload of project source namespaces touched by tests."
  []
  (doseq [ns-sym source-namespaces]
    (require ns-sym :reload)))

(defn run-tests
  "Load tests and run them. Returns clojure.test summary map.
   Usage from REPL after jack-in with :test alias:
   (require 'sr2.dev)
   (sr2.dev/run-tests)
  "
  []
  (reload-sources)
  (load-tests)
  (apply t/run-tests test-namespaces))

(comment
  (run-tests))
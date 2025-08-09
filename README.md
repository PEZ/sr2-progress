# Sega Rally 2 NVRAM extractor

Data-first Clojure tools to parse Sega Rally 2 NVRAM (Supermodel emulator).

## Quick start (REPL)

Use Calva to jack in, then evaluate:

```clojure
(require '[sr2.nvram-extractor :as ne])
(def data (ne/read-nvram-bytes "data/srally2-known.nv"))
(-> (ne/extract-all-track-top3 data) (update-vals #(mapv :time %)))
(take 3 (ne/extract-championship-leaderboard data 0x267))
```

## Running tests (REPL-only)

Always run tests via the REPL:

```clojure
(require 'sr2.dev)
(sr2.dev/run-tests)
```

## Docs

- See `docs/PROJECT_SUMMARY.md` for layout, offsets, and capabilities.

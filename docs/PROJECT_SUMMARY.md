# Sega Rally 2 NVRAM Extractor – Project Summary

This repository is an exploratory, data-first Clojure toolkit to reverse engineer and extract gameplay records from Sega Rally 2’s NVRAM file as produced by the Supermodel (Model 3) emulator. It focuses on making the data layout explicit, providing small, composable extraction functions, and verifying hypotheses interactively at the REPL.

Supermodel repo: https://github.com/trzy/Supermodel


## Repository layout

- `deps.edn` – Clojure CLI setup (Clojure 1.12.1)
- `data/`
  - `srally2-known.nv` – known-good NVRAM sample used for exploration and verification
- `src/sr2/`
  - `nvram_extractor.clj` – the main extraction and helper functions (hex-dumps, scans, decoders)
  - `nvram_known_data.clj` – human-entered known/expected values used as fixtures during RE work


## Problem domain and scope

Sega Rally 2 (Arcade) has four tracks: Desert, Mountain, Snowy, and Riviera. Game modes:
- Championship: 1 lap for Desert/Mountain/Snowy, 2 laps for Riviera
- Practice: 3 laps per track (Riviera 5 laps)

The game saves multiple leaderboards and timing tables in NVRAM. The end goal is to extract, validate, and eventually publish all relevant pieces (for both analysis and tooling).

Planned/known data sets (some implemented, some pending):
- [x] Top 16 best Championship runs (overall)
- [ ] Top 8 Championship runs per car (6+ cars; potential hidden car)
- [x] Top 3 best laps per track ("Championship per-track top 3")
- [ ] Sector times for the best Championship run per track
- [x] Top 8 Practice run times per track
- [ ] Sector times for the best Practice run per track


## What we know about the NVRAM layout (from this repo’s exploration)

- Authoritative main chunk with all relevant data (for our purposes): `[0x0147, 0x38C0)`
- There is an identical duplicate of that chunk at offset `+0x10000` (i.e. `[0x10147, 0x10147 + size)`). Read from the first chunk and ignore later duplicates.
- Landmark offsets (inside the main chunk):
  - `0x0267` – Championship primary block (16 × 32-byte records)
  - Top-3 per-track tables (3 records each, stride `0x20`):
    - `0x0E5D` Mountain, `0x0F5D` Desert, `0x105D` Riviera, `0x115D` Snowy
  - Practice Top-8 (8 records each, stride `0x20`):
    - Mountain `0x1467`, Desert `0x1567`, Riviera `0x1667`, Snowy `0x1767`
- 32-byte record conventions observed so far:
  - Player initials/name bytes: `[1, 0, 5]` (X, Y, Z)
  - Time encodings are 24-bit little-endian counters stored in various positions
  - Championship/practice record time bytes: `[20, 21, 16]` (lsb, mid, msb)
  - Per-track top-3 record time bytes: `[30, 31, 26]`
- Time unit: ticks, with `60 ticks = 1 centisecond`. Conversion: `centiseconds = (le24 bytes) / 60`. Format helper: `MM:SS.cc`.


## Implemented building blocks and extractors (Clojure API)

The code aims for small, pure helpers with data-in/data-out. A quick cheat-sheet:

- File I/O and utilities
  - `read-nvram-bytes <path> -> byte[]` – load NVRAM into memory
  - `hex-dump data start end` – human-friendly line/ASCII hex dump
  - Region scanning: `find-blank-ranges`, `complement-ranges`, `region-summary`, `hex-dump-nonblank`
  - Landmark chopping: `find-next-blank-run`, `landmark-regions`, `hex-dump-landmark-regions`
- Binary/time helpers
  - `le24 lsb mid msb -> int` – compose a 24-bit little-endian number
  - `decode-time lsb mid msb -> "MM:SS.cc"`
  - `parse-mmsscc->cs str -> long`, `cs->mmsscc long -> str`
- Championship (overall top-16)
  - `extract-championship-leaderboard data start-offset -> [{:entry :player-name :championship-time ...}]`
  - `championship-leaderboard data {:offset 0x267}` – pretty printer
  - Duplicate detector: `championship-duplicates` (finds other 0x200-byte blocks equal to the primary)
- Per-track top-3 (championship tables)
  - `track-tables` – discovered bases/offset specs for Mountain/Desert/Riviera/Snowy
  - `extract-track-top3 data spec -> [{:time :initials} ×3]`
  - `extract-all-track-top3 data -> {track [{:time :initials} ...]}`
  - `print-all-track-top3 data {:with-initials? false}`
  - `compare-top3 data expected -> {track {:expected :got}}` (diff only)
- Practice Top-8 per track
  - `track-practice-bases` – bases for Mountain/Desert/Riviera/Snowy
  - `extract-practice-top8-at data base -> 8 entries`
  - `extract-practice-top8 data -> {track [8 entries]}`
  - `print-practice-top8 data` – pretty printer
- Player aggregation and potential time
  - `player-best-per-track data "XYZ" -> {track {:time :initials}}`
  - `potential-time best-map -> {:centiseconds :time}`
  - `player-best-championship-time data "XYZ" -> best-time-or-nil`
  - `print-player-best-and-potential data "XYZ"` – report with delta vs best championship

Data shapes use simple Clojure maps/vectors and well-named keys to facilitate further processing, testing, and export.


## Quick REPL try-it

Using Calva: jack-in, open `src/sr2/nvram_extractor.clj`, and evaluate small expressions step by step.

Example sessions (these are the exact forms the team used while developing):

```clojure
(in-ns 'user)
(require '[sr2.nvram-extractor :as ne])
(def data (ne/read-nvram-bytes "data/srally2-known.nv"))
(alength data) ; => 131397

; Per-track Top-3 (times only)
(-> (ne/extract-all-track-top3 data)
    (update-vals #(mapv :time %)))
; => {:desert ["00:57.03" "00:57.05" "00:57.27"],
;     :mountain ["01:02.96" "01:03.07" "01:03.15"],
;     :riviera ["01:06.61" "01:07.17" "01:07.18"],
;     :snowy ["01:00.08" "01:00.44" "01:00.49"]}

; Practice Top-8 for Desert
(first (ne/extract-practice-top8 data))
; => [:desert [{:off 0x1567, :name "PEZ", :time "02:46.83", :cs ...} ...]]

; Championship Top-16 (structure)
(take 3 (ne/extract-championship-leaderboard data 0x267))
; => ({:entry 0, :player-name "PEZ", :championship-time "04:09.54", ...} ...)

; Player potential vs best
(ne/print-player-best-and-potential data "PEZ")
```

## Known-good fixtures and references

- NVRAM sample: `data/srally2-known.nv` (committed for reproducibility of RE steps)
- External context: Supermodel repo (build/run details): https://github.com/trzy/Supermodel
- Human-entered expectations: `src/sr2/nvram_known_data.clj`


## Engineering notes and conventions

- Keep functions pure where practical. Favor small, composable transformations.
- Prefer evaluating sub-expressions over printing (e.g. decode then format, don’t println during parsing).
- Offsets and sizes use hex literals in code; mirror them in docs where it improves signal.
- When scanning the file globally, ignore the duplicate main chunk at `+0x10000` to avoid false positives.


## Current capabilities (status)

- Championship overall top-16: data present and decodable (times, initials as `[1,0,5]`, time bytes `[20,21,16]`). Pretty-printers included.
- Per-track championship top-3 (all tracks): implemented and verified; initials bytes `[11,10,15]`, time bytes `[30,31,26]`.
- Practice Top-8 per track: implemented and verified from fixed bases.
- Summaries: basic comparison helper (`compare-top3`), player-best aggregation, potential time computation, and nice, succinct reports.


## Roadmap (next investigations)

1) Championship per-car top-8 tables
- Hypothesis: either replicated championship table per car or a separate per-car table region with similar 32-byte records.
- Approach: search for additional 0x200-sized blocks with plausible record patterns; correlate initials/time fields with known car selections.

2) Sector times – best Championship run per track
- Hypothesis: sector arrays close to per-track top-3 or within the championship block, with 24-bit LE timing values and fixed-length padding.
- Approach: landmark-forward chopping from known bases; scan for 24-bit sequences that decode to plausible ranges; cross-validate with known splits.

3) Sector times – best Practice run per track
- Hypothesis: near practice Top-8 bases; similar encoding to lap times but with more entries per lap.
- Approach: use non-blank region summaries around `[0x1467..0x1767]`, look for repeated triplets/quintuplets (Riviera 5), verify by deltas summing to lap times.

4) Robust duplicate handling and chunk boundaries
- Constrain global searches to `[0x0147, 0x38C0)` and ignore `+0x10000` duplicate; parameterize helpers with region bounds.

5) Structured outputs
- Add EDN/JSON exporters for all extractors (one file per table), plus a tiny CLI/`-m` entrypoint for batch extraction.

6) Tests
- Introduce `clojure.test` with fixtures from `data/srally2-known.nv` and constants from `nvram_known_data.clj`.
- Unit tests: `le24`, time decode/format, initials extraction, table offsets.
- Golden tests: known maps for per-track top-3 and practice Top-8 for slot 0.


## Minimal contracts (inputs/outputs)

- All extractors accept a `byte[]` of the whole NVRAM file.
- Returned values are pure Clojure data (maps/vectors/strings/ints). No I/O inside extractors.
- Errors: out-of-bounds reads are prevented by using known offsets and byte-array length checks when applicable. Prefer explicit preconditions for future public APIs.


## Glossary

- Base: start offset of a table of fixed-size records
- Stride: the byte distance between successive records (here, `0x20`)
- Record: a fixed 32-byte structure containing initials, timings, and padding
- Ticks: 24-bit little-endian timing unit; here `60 ticks = 1 centisecond`
- Main chunk: `[0x0147, 0x38C0)` containing all the tables we care about (duplicated at `+0x10000`)


## Contribution pointers

- Keep additions small and verifiable at the REPL. Commit new landmarks and offsets with a short rationale.
- Prefer data-first returns. Add pretty-printers as thin layers.
- If adding new tables: describe offsets/fields inline and reference them here.
- Include a quick REPL snippet for each new capability.


---

This document should be kept in lockstep with discoveries in `nvram_extractor.clj`. When we confirm new landmarks or structures, please update both the code comments and this summary.

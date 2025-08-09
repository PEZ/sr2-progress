# Copilot instructions for Sega Rally 2 NVRAM project

Audience: AI coding agents working in VS Code on this Clojure project.
Goal: Be maximally effective and precise when extending extractors and docs.
Key doc: Always read `docs/PROJECT_SUMMARY.md` first for data layout, offsets, and current capabilities.

## Modules and façade

- Public entrypoint: `sr2.nvram-extractor` (façade). This namespace exposes the stable API used by tests and docs. Keep it stable unless asked to change it.
- Internal modules:
  - `sr2.nv.util` (alias `u`): pure helpers (bytes, time codecs, record fields).
  - `sr2.nv.explore` (alias `xpl`): exploratory tools (hex dumps, region scans, landmarks).
  - `sr2.nv.extract` (alias `ext`): settled extractors (championship, per‑track top‑3, practice top‑8, player summaries).
- Policy: Inside modules, call helpers via alias-qualified symbols (e.g. `u/decode-time`) rather than re‑exporting/wrapping. The façade binds to module vars to present the public API.


## Operating principles

- Be data-oriented and functional. Prefer small, pure functions that return data (maps/vectors/strings/ints).
- Keep IO thin. Load bytes once, pass `byte[]` around. Avoid printing from core extractors; provide separate pretty-printers.
- Respect invariants from the summary doc (chunk ranges, record size `0x20`, time byte offsets).
- Minimize change surface. Preserve namespaces/APIs; add focused helpers; document new offsets inline and in the summary.
- Verify at the REPL before committing. Use the sample NVRAM at `data/srally2-known.nv` for smoke tests.


## Quick start (REPL loop)

- Use the Backseat Driver/Calva REPL connection. Evaluate small expressions as you work.
- Always add the evaluated expression to the chat as a Clojure code block.
- Load helpers and sample data:
  ```clojure
  (in-ns 'sr2.nvram-extractor)
  (def data (read-nvram-bytes "data/srally2-known.nv"))
  (alength data)
  ```
- Validate a feature you touch (choose 1-2 fast checks):
  ```clojure
  (-> (extract-all-track-top3 data) (update-vals #(mapv :time %)))
  (take 3 (extract-championship-leaderboard data 0x267))
  (print-player-best-and-potential data "PEZ")
  ```

- Alternative usage (stay in `user` ns):
  ```clojure
  (require '[sr2.nvram-extractor :as ne])
  (def data (ne/read-nvram-bytes "data/srally2-known.nv"))
  (-> (ne/extract-all-track-top3 data) (update-vals #(mapv :time %)))
  (take 3 (ne/extract-championship-leaderboard data 0x267))
  ```

- Run the test suite from the REPL (ensure `:test` alias is active so `test/` is on classpath):
  ```clojure
  (require 'sr2.dev)
  (sr2.dev/run-tests)
  ```
  Always use the REPL for tests. Do not run tests from the shell; keep the REPL as the source of truth during refactors.


## Clojure style for this repo

- Namespaces: `sr2.*`. Keep helpers close to usage. Use hex literals for offsets and keep a short comment near new constants.
- Contracts:
  - Inputs: `byte[]` and simple scalars/keywords.
  - Outputs: pure data. No side effects in extractors.
  - Errors: prevent out-of-bounds reads; guard with length checks where needed.
- Time encoding: 24-bit little endian ticks, `60 ticks = 1 centisecond`.
  - Championship/practice records: bytes `[20 21 16]` → lsb, mid, msb
  - Track top-3 records: bytes `[30 31 26]`
  - Initials: `[1 0 5]` (champ/practice), `[11 10 15]` (top-3 tables)
- Parsing strategy: decode first (numbers), then format via `MM:SS.cc` helpers (`parse-mmsscc->cs`, `cs->mmsscc`).


## Data layout guardrails

- Main authoritative chunk: `[0x0000, 0x38C0)`. Never read beyond this. There is a mirrored duplicate at `+0x10000`—ignore it; do not cross the `[0x0000, 0x38C0)` boundary in extractors.
- Record size for records with player + time is `0x20`. Stride is `0x20` for tables.
- Known tables (see `docs/PROJECT_SUMMARY.md` for the full list and landmarks):
  - Championship top-16 block at `0x0267`.
  - Track top-3 bases: Mountain `0x0E5D`, Desert `0x0F5D`, Riviera `0x105D`, Snowy `0x115D`.
  - Practice top-8 bases: Mountain `0x1467`, Desert `0x1567`, Riviera `0x1667`, Snowy `0x1767`.


## Workflow when implementing

1) Extract requirements
- Turn the user ask into a short checklist. Keep it updated as you work.

2) Gather context
- Skim `docs/PROJECT_SUMMARY.md` and relevant source. Prefer reading larger chunks to avoid missing context.

3) Design a tiny contract
- Inputs/outputs, error modes, success criteria. Note offsets and expected counts.

4) Apply minimal edits
- Add constants near existing ones. Reuse helpers (`le24`, `decode-time`, `rec-name`, `rec-cs`, etc.).
- Keep public shapes consistent: vectors of maps, sorted maps for track keys.
- Maintain the façade API: add new capabilities in `sr2.nv.extract` and bind them in the façade with direct var aliases. Avoid wrapper functions.
- Inside modules, prefer alias-qualified calls over re-exporting symbols.

5) REPL-validate
- Run 1-3 fast checks on `data/srally2-known.nv` targeting what you changed.
- Prefer value assertions over printing. If printing, keep it succinct.

6) Add tests (if adding behavior)
- Add `test/` with `clojure.test` skeletons when feasible.
- Golden tests: use `nvram_known_data.clj` expectations and `data/srally2-known.nv`; verify decoded times and initials for a few slots.

7) Docs
- Update `docs/PROJECT_SUMMARY.md` when you discover new landmarks, or confirm hypotheses, or update the code in any significant way.
- Include a short REPL snippet demonstrating the new capability.

8) Tests
- Run tests to guard against regressions. From REPL:
  ```clojure
  (require 'sr2.dev)
  (sr2.dev/run-tests)
  ```
 - Policy: Always run tests via the Calva REPL, not from the terminal. This ensures consistent classpath and fast feedback while editing.

9) Code file updates
- Discuss with the user if the code files should be updated.

At any time when you want to think together with the user, use the human intelligence tool.

## Response and editing patterns

- Keep messages concise. Start with a one-line task receipt and a short plan.
- Use a lightweight checklist. After 3–5 actions or >3 file edits, provide a compact checkpoint.
- Reference files and symbols in backticks. Avoid large unrequested code dumps.
- Prefer minimal diffs; don’t touch unrelated code.
- When changing behavior, mention the contract and edge cases you considered.


## Common recipes (building blocks)

- Per-track top-3:
  ```clojure
  (require '[sr2.nvram-extractor :as ne])
  (ne/extract-all-track-top3 data) ; => {track [{:time :initials} ...]}
  ```
- Practice top-8 per track:
  ```clojure
  (require '[sr2.nvram-extractor :as ne])
  (ne/extract-practice-top8 data) ; => {track [8 entries]}
  ```
- Championship leaderboard:
  ```clojure
  (require '[sr2.nvram-extractor :as ne])
  (ne/extract-championship-leaderboard data 0x267)
  ```

- Hex dump a region:
  ```clojure
  (require '[sr2.nvram-extractor :as ne])
  (ne/hex-dump data 0x0267 0x04A0)
  ```


## Quality gates before you’re done

- Load/compile: files evaluate without errors in the REPL.
- Smoke tests: results for known tables match the sample’s expected values.
- Style: functions are pure; outputs are plain data; no accidental printing.
- Docs: any new offsets/landmarks are captured in `docs/PROJECT_SUMMARY.md`.


## When exploring new tables (per-car top-8, sector splits)

- Constrain search to `[0x00000, 0x38C0)`
- Look for repeated 0x20-sized records with plausible initials/time fields.
- Use landmark-forward chopping to bound regions, then test `le24`-decoded ranges for plausibility.
- Validate by summing splits to lap/run times and by correlating with known choices (car, track, mode).


## Pointers

- Project specifics (offsets, layouts, verified facts): `docs/PROJECT_SUMMARY.md`.
- Expectations/fixtures: `src/sr2/nvram_known_data.clj`.
- Sample NVRAM for reproducible checks: `data/srally2-known.nv`.

This guide should be kept in sync with the summary and source as discoveries are made.

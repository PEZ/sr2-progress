(ns sr2.nvram-extractor
  "Sega Rally 2 NVRAM Championship Times Extractor"
  (:require [sr2.nv.util :as u]
            [sr2.nv.explore :as xpl]
            [sr2.nv.extract :as ext]))

;; See docs/PROJECT_SUMMARY.md for nvram data layout information

(def read-nvram-bytes u/read-nvram-bytes)

(def safe-char u/safe-char)

(def hex-to-dec u/hex-to-dec)

;; Simple hex dump utility
(def hex-dump xpl/hex-dump)

;; ==========================================
;; REGION SCANNING (blank/non-blank + tagging)
;; ==========================================

(def find-blank-ranges xpl/find-blank-ranges)

(def complement-ranges xpl/complement-ranges)

(def landmarks xpl/landmarks)


(def tags-for-region xpl/tags-for-region)

(def region-summary xpl/region-summary)

(def hex-dump-nonblank xpl/hex-dump-nonblank)

;; ==========================================
;; LANDMARK-FORWARD CHOPPING (stop at blank run)
;; ==========================================

(def find-next-blank-run xpl/find-next-blank-run)

(def landmark-regions xpl/landmark-regions)

(def hex-dump-landmark-regions xpl/hex-dump-landmark-regions)

(comment
  (hex-dump-nonblank (read-nvram-bytes "srally2-data.nv") {:blank-byte 0x00 :min-blank-len 10})
  :rcf)

(def bytes-slice u/bytes-slice)

(def le24 u/le24)

;; 60 ticks = 1 centisecond
(def decode-time u/decode-time)

(def extract-championship-leaderboard ext/extract-championship-leaderboard)


(def championship-leaderboard ext/championship-leaderboard)

;; ==========================================
;; TRACK TOP-3 EXTRACTION (delegated)
;; ==========================================

(def decode-at-offsets ext/decode-at-offsets)

(def extract-track-top3 ext/extract-track-top3)

(def track-tables ext/track-tables)

(def extract-all-track-top3 ext/extract-all-track-top3)

(def print-all-track-top3 ext/print-all-track-top3)

(def compare-top3 ext/compare-top3)

;; ==========================================
;; POTENTIAL TIME (best-per-track per player)
;; ==========================================

(def parse-mmsscc->cs u/parse-mmsscc->cs)

(def cs->mmsscc u/cs->mmsscc)

(def player-best-per-track ext/player-best-per-track)

(def potential-time ext/potential-time)

(def player-best-championship-time ext/player-best-championship-time)

(def signed-diff-mmsscc ext/signed-diff-mmsscc)

(def print-player-best-and-potential ext/print-player-best-and-potential)

;; ==========================================
;; PRACTICE/RECORD HELPERS (championship regions)
;; ==========================================

(def rec-name u/rec-name)

(def rec-cs u/rec-cs)

(def rec-time u/rec-time)

;; ------------------------------------------
;; PRACTICE TOP-8 PER TRACK (delegated)
;; ------------------------------------------

(def extract-practice-top8-at ext/extract-practice-top8-at)

(def track-practice-bases ext/track-practice-bases)
(def track-order          ext/track-order)
(def extract-practice-top8 ext/extract-practice-top8)

(def print-practice-top8 ext/print-practice-top8)

(comment
  ;; REPL helpers and examples
  (def data (read-nvram-bytes "data/srally2-known.nv"))
  (def data (read-nvram-bytes "../Supermodel/NVRAM/srally2.nv"))

  (hex-dump data 0x267 0x4a0)
  (championship-leaderboard data 0x267)

  ;; Print top-3 per track (times only)
  (print-all-track-top3 data)

  ;; Print top-3 per track (with player initials)
  (print-all-track-top3 data true)

  ;; Inspect the returned structure {track [{:time :initials} ...]}
  (extract-all-track-top3 data)

  ;; Compare extracted times (not initials) with expected note values
  (compare-top3 data {:desert   ["00:57.03" "00:57.23" "00:57.27"]
                      :mountain ["01:02.96" "01:03.07" "01:03.15"]
                      :snowy    ["00:59.74" "01:00.08" "01:00.44"]
                      :riviera  ["01:06.61" "01:07.17" "01:07.18"]})

  ;; Potential time examples
  (player-best-per-track data "PEZ")
  (potential-time (player-best-per-track data "PEZ"))
  (print-player-best-and-potential data "PEZ")

  (player-best-per-track data "XYZ")
  (potential-time (player-best-per-track data "XYZ"))
  (print-player-best-and-potential data "XYZ")

  ;; Practice totals, high-level usage examples
  ;; -----------------------------------------
  (print-practice-top8 data))


(ns sr2.nvram-extractor
  "Sega Rally 2 NVRAM — REPL examples without façade re-exports.
  Use alias-qualified calls: u/*, ext/*, xpl/*. See docs/PROJECT_SUMMARY.md for layout."
  (:require [sr2.dev :as dev]
            [sr2.nv.util :as u]
            [sr2.nv.explore :as xpl]
            [sr2.nv.extract :as ext]))

(comment
  (dev/run-tests)
  :rcf)

;; ------------------------------------------
;; Extractors quickstart (established capabilities)
;; ------------------------------------------
(comment
  ;; Load sample data
  (def data (u/read-nvram-bytes "data/srally2-known.nv"))
  ;; (def data (u/read-nvram-bytes "../Supermodel/NVRAM/srally2.nv"))

  ;; Championship Top-16
  (take 3 (ext/extract-championship-leaderboard data))
  (ext/championship-leaderboard data)

  ;; Per-track Top-3
  (ext/decode-at-offsets data 0x0E5D 0x20 [30 31 26])
  (ext/extract-track-top3 data (get ext/track-tables :mountain))
  (ext/extract-all-track-top3 data)
  (ext/print-all-track-top3 data)
  (ext/print-all-track-top3 data {:with-initials? true})
  (ext/compare-top3 data {:desert   ["00:57.03" "00:57.23" "00:57.27"]
                          :mountain ["01:02.96" "01:03.07" "01:03.15"]
                          :snowy    ["00:59.74" "01:00.08" "01:00.44"]
                          :riviera  ["01:06.61" "01:07.17" "01:07.18"]})

  ;; Player aggregation and potential
  (ext/player-best-per-track data "PEZ")
  (ext/potential-time (ext/player-best-per-track data "PEZ"))
  (ext/player-best-championship-time data "PEZ")
  (ext/print-player-best-and-potential data "PEZ")

  ;; Time codec helpers
  (u/parse-mmsscc->cs "01:02.96")
  (u/cs->mmsscc 6296)

  ;; Practice Top-8
  (ext/extract-practice-top8-at data (get ext/track-practice-bases :mountain))
  (ext/extract-practice-top8 data)
  (ext/print-practice-top8 data)
  (ext/print-practice-top8 data {:order ext/track-order})

  ;; Single-record helpers
  (u/rec-name data 0x0267)
  (u/rec-cs data 0x0267)
  (u/rec-time data 0x0267)
  :rcf)

;; ------------------------------------------
;; Exploratory tools (xpl/*)
;; ------------------------------------------
(comment
  (def data (u/read-nvram-bytes "data/srally2-known.nv"))

  ;; Hex dumps
  (xpl/hex-dump data 0x0267 0x04A0)
  (xpl/hex-dump-nonblank data)
  (xpl/hex-dump-nonblank data {:blank-byte 0x00 :min-blank-len 32})

  (xpl/hex-dump-with-times data 0x0267 0x02C7)


  ;; Region scanning and summaries
  (def blanks (xpl/find-blank-ranges data))
  (take 3 blanks)
  (def nonblanks (xpl/complement-ranges (alength data) blanks))
  (take 3 nonblanks)
  (xpl/tags-for-region {:start 0x0267 :end 0x04A0})
  (take 3 (xpl/region-summary data))
  (take 3 (xpl/region-summary data {:blank-byte 0x00 :min-blank-len 32}))

  ;; Landmarks and landmark-bounded regions
  xpl/landmarks
  (xpl/find-next-blank-run data 0x0267)
  (take 4 (xpl/landmark-regions data))
  (xpl/hex-dump-landmark-regions data {:blank-byte 0x00 :min-blank-len 32})

  :rcf)

(comment
  (dev/run-tests)
  :rcf)
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
  (def data (u/read-nvram-bytes "../Supermodel/NVRAM/srally2.nv"))

  ;; Championship Top-16
  (ext/championship-leaderboard data)

  ;; Per-track Top-3
  (ext/decode-at-offsets data 0x0E5D 0x20 [30 31 26])
  (ext/extract-track-top3 data (get ext/championship-top-3-track-tables :mountain))
  (ext/extract-all-championship-track-top3 data)
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

  ;; Sector times (best runs)
  ;; Championship sectors per track (Riviera includes a duplicate at the lap boundary)
  (ext/extract-championship-best-sector-times data)
  ;; Quick glance: counts and last per track
  (-> (ext/extract-championship-best-sector-times data)
      (update-vals (fn [v] {:count (count v) :last (last v)})))
  (ext/print-championship-sectors data)
  (ext/print-championship-sectors data {:track :riviera})
  (ext/extract-championship-sector-splits data)
  (ext/print-championship-sectors data {:mode :splits})


  ;; Practice sectors per track
  (ext/extract-practice-best-sector-times data)
  (ext/print-practice-sectors data)
  (ext/print-practice-sectors data {:track "Snowy"})
  (ext/extract-practice-sector-splits data)
  (ext/print-practice-sectors data {:mode :splits})
  (ext/print-practice-sectors data {:mode :splits :track :riviera})

  ;; Single-record helpers
  (u/rec-name data 0x0267)
  (u/rec-cs data 0x0267)
  (u/rec-time data 0x0267)
  :rcf)

;; ------------------------------------------
;; Exploratory tools (xpl/*)
;; ------------------------------------------
(comment
  (def data (java.util.Arrays/copyOf (u/read-nvram-bytes "data/srally2-known.nv") 20000))

  ;; Hex dumps
  (xpl/hex-dump data 0x0267 0x04A0)
  (xpl/hex-dump-nonblank data)
  (xpl/hex-dump-nonblank data {:blank-byte 0x00 :min-blank-len 32})

  ;; Hex dumps with time overlays
  (xpl/hex-dump-nonblank-with-times data)
  (xpl/hex-dump-nonblank-with-times data {:blank-byte 0x00 :min-blank-len 32} {:min-cs 600 :max-cs 60000})

  (xpl/hex-dump-with-times data 0x0267 0x02C7)
  (xpl/hex-dump-with-times data 0x1860 0x1A40)


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
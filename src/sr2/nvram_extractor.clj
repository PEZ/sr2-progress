(ns sr2.nvram-extractor
  "Sega Rally 2 NVRAM Championship Times Extractor"
  (:require [sr2.nv.util :as u]
            [sr2.nv.explore :as xpl]
            [sr2.nv.extract :as ext]))

;; See docs/PROJECT_SUMMARY.md for nvram data layout information

(defn read-nvram-bytes
  "Read NVRAM file as byte array"
  [file-path]
  (u/read-nvram-bytes file-path))

(defn safe-char
  "Convert byte to ASCII char, returning . for non-printable"
  [byte-val]
  (u/safe-char byte-val))

(defn hex-to-dec [byte]
  (u/hex-to-dec byte))

;; Simple hex dump utility
(defn hex-dump
  "Print a hex dump of data bytes in [start,end). 16 bytes per line with ASCII gutter."
  [^bytes data start end]
  (xpl/hex-dump data start end))

;; ==========================================
;; REGION SCANNING (blank/non-blank + tagging)
;; ==========================================

(defn find-blank-ranges
  "Return a vector of {:start :end} half-open ranges of contiguous bytes equal to blank-byte.
   min-len filters out short runs. opts: {:blank-byte 0x00 :min-len 1}"
  ([^bytes data] (xpl/find-blank-ranges data))
  ([^bytes data opts] (xpl/find-blank-ranges data opts)))

(defn complement-ranges
  "Given total length and sorted blank ranges [{:start :end} ...], return non-blank ranges."
  [total-len blank-ranges]
  (xpl/complement-ranges total-len blank-ranges))

(def landmarks xpl/landmarks)


(defn tags-for-region [r] (xpl/tags-for-region r))

(defn region-summary
  "Summarize non-blank regions with optional tags. opts: {:blank-byte 0x00 :min-blank-len 1}"
  ([^bytes data] (xpl/region-summary data))
  ([^bytes data opts] (xpl/region-summary data opts)))

(defn hex-dump-nonblank
  "Hex dump all non-blank regions with headers and tags. opts: {:blank-byte 0x00 :min-blank-len 1}"
  ([^bytes data] (xpl/hex-dump-nonblank data))
  ([^bytes data opts] (xpl/hex-dump-nonblank data opts)))

;; ==========================================
;; LANDMARK-FORWARD CHOPPING (stop at blank run)
;; ==========================================

(defn find-next-blank-run
  "Return the start index of the first run of blank-byte of length >= min-len at or after `from`."
  ([^bytes data from] (xpl/find-next-blank-run data from))
  ([^bytes data from opts] (xpl/find-next-blank-run data from opts)))

(defn landmark-regions
  "For each known landmark, return {:label :start :end :size}."
  ([^bytes data] (xpl/landmark-regions data))
  ([^bytes data opts] (xpl/landmark-regions data opts)))

(defn hex-dump-landmark-regions
  "Hex dump regions starting at each landmark, stopping at the next blank run of length >= min-blank-len."
  ([^bytes data] (xpl/hex-dump-landmark-regions data))
  ([^bytes data opts] (xpl/hex-dump-landmark-regions data opts)))

(comment
  (hex-dump-nonblank (read-nvram-bytes "srally2-data.nv") {:blank-byte 0x00 :min-blank-len 10})
  :rcf)

(defn bytes-slice
  "Copy a slice of a byte array [start, start+len)."
  [^bytes data start len]
  (u/bytes-slice data start len))

(defn le24
  "Compose a 24-bit little-endian integer from bytes [lsb mid msb]."
  [lsb-20 mid-21 msb-16]
  (u/le24 lsb-20 mid-21 msb-16))

;; 60 ticks = 1 centisecond
(defn decode-time
  "Decode MM:SS.cc from [lsb-20 mid-21 msb-16], where 60 ticks = 1 centisecond."
  [lsb mid msb]
  (u/decode-time lsb mid msb))

(defn extract-championship-leaderboard
  "- Each entry is 32 bytes (0x20)
   - time encoded in positions 16, 20, 21"
  [data start-offset]
  (ext/extract-championship-leaderboard data start-offset))


(defn championship-leaderboard
  "Print championship leaderboard.
   opts: {:offset 0x267}. If called as [data], defaults to {:offset 0x267}."
  ([data] (ext/championship-leaderboard data))
  ([data opts] (ext/championship-leaderboard data opts)))

;; ==========================================
;; TRACK TOP-3 EXTRACTION (delegated)
;; ==========================================

(defn decode-at-offsets
  [^bytes data base stride offs]
  (ext/decode-at-offsets data base stride offs))

(defn extract-track-top3 [^bytes data spec]
  (ext/extract-track-top3 data spec))

(def track-tables ext/track-tables)

(defn extract-all-track-top3 [^bytes data]
  (ext/extract-all-track-top3 data))

(defn print-all-track-top3
  ([^bytes data] (ext/print-all-track-top3 data))
  ([^bytes data opts] (ext/print-all-track-top3 data opts)))

(defn compare-top3 [^bytes data expected]
  (ext/compare-top3 data expected))

;; ==========================================
;; POTENTIAL TIME (best-per-track per player)
;; ==========================================

(defn parse-mmsscc->cs [s] (u/parse-mmsscc->cs s))

(defn cs->mmsscc [cs] (u/cs->mmsscc cs))

(defn player-best-per-track [^bytes data player]
  (ext/player-best-per-track data player))

(defn potential-time [best-map]
  (ext/potential-time best-map))

(defn player-best-championship-time [^bytes data player]
  (ext/player-best-championship-time data player))

(defn signed-diff-mmsscc [potential-cs best-cs]
  (ext/signed-diff-mmsscc potential-cs best-cs))

(defn print-player-best-and-potential [^bytes data player]
  (ext/print-player-best-and-potential data player))

;; ==========================================
;; PRACTICE/RECORD HELPERS (championship regions)
;; ==========================================

(defn rec-name
  "Player initials/name as stored in a 32-byte record at offset `off`.
   Uses the same positions as championship entries (1, 0, 5)."
  [^bytes data off]
  (u/rec-name data off))

(defn rec-cs
  "Centiseconds decoded from [20,21,16] as little-endian 24-bit ticks (60 ticks = 1 cs)."
  [^bytes data off]
  (u/rec-cs data off))

(defn rec-time
  "MM:SS.cc string for the record at offset `off`."
  [^bytes data off]
  (u/rec-time data off))

;; ------------------------------------------
;; PRACTICE TOP-8 PER TRACK (delegated)
;; ------------------------------------------

(defn extract-practice-top8-at [^bytes data base]
  (ext/extract-practice-top8-at data base))

(def track-practice-bases ext/track-practice-bases)
(def track-order          ext/track-order)
(defn extract-practice-top8
  ([^bytes data] (ext/extract-practice-top8 data))
  ([^bytes data bases] (ext/extract-practice-top8 data bases)))

(defn print-practice-top8
  ([^bytes data] (ext/print-practice-top8 data))
  ([^bytes data opts] (ext/print-practice-top8 data opts)))

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


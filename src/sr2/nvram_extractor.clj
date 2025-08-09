(ns sr2.nvram-extractor
  "Sega Rally 2 NVRAM Championship Times Extractor"
  (:require [clojure.java.io :as io]
            [clojure.string :as string]))

;; See docs/PROJECT_SUMMARY.md for nvram data layout information

(defn read-nvram-bytes
  "Read NVRAM file as byte array"
  [file-path]
  (with-open [stream (io/input-stream file-path)]
    (.readAllBytes stream)))

(defn safe-char
  "Convert byte to ASCII char, returning . for non-printable"
  [byte-val]
  (let [b (bit-and byte-val 0xFF)]
    (if (and (>= b 32) (<= b 126))
      (char b)
      \.)))

(defn hex-to-dec [byte]
  (if (nil? byte) 0 (bit-and byte 0xff)))

;; Simple hex dump utility
(defn hex-dump
  "Print a hex dump of data bytes in [start,end). 16 bytes per line with ASCII gutter."
  [^bytes data start end]
  (let [n (alength data)
        start (max 0 (min (long start) n))
        end (max start (min (long end) n))]
    (loop [i start]
      (when (< i end)
        (let [line-end (min end (+ i 32))
              idxs (range i line-end)
              bs   (map #(bit-and (aget data %) 0xFF) idxs)
              hexs (->> bs (map #(format "%02X" %))
                        (partition-all 8)
                        (map #(string/join " " %))
                        (string/join "  "))
              ascii (apply str (map safe-char bs))]
          (println (format "%04X: %-47s  |%s|" i hexs ascii))
          (recur line-end))))))

;; ==========================================
;; REGION SCANNING (blank/non-blank + tagging)
;; ==========================================

(defn find-blank-ranges
  "Return a vector of {:start :end} half-open ranges of contiguous bytes equal to blank-byte.
   min-len filters out short runs. opts: {:blank-byte 0x00 :min-len 1}"
  ([^bytes data] (find-blank-ranges data {:blank-byte 0 :min-len 1}))
  ([^bytes data {:keys [blank-byte min-len] :or {blank-byte 0 min-len 1}}]
   (let [n (alength data)
         bb (bit-and blank-byte 0xFF)]
     (loop [i 0, cur-start nil, acc []]
       (if (>= i n)
         (if (some? cur-start)
           (let [r {:start cur-start :end i}]
             (if (>= (- (:end r) (:start r)) min-len)
               (conj acc r)
               acc))
           acc)
         (let [b (bit-and (aget data i) 0xFF)]
           (cond
             (= b bb) (recur (inc i) (or cur-start i) acc)
             (some? cur-start) (let [r {:start cur-start :end i}
                                     acc' (if (>= (- (:end r) (:start r)) min-len)
                                            (conj acc r)
                                            acc)]
                                 (recur (inc i) nil acc'))
             :else (recur (inc i) nil acc))))))))

(defn complement-ranges
  "Given total length and sorted blank ranges [{:start :end} ...], return non-blank ranges."
  [total-len blank-ranges]
  (let [br (sort-by :start blank-ranges)]
    (loop [pos 0, rs br, acc []]
      (if (empty? rs)
        (cond-> acc
          (< pos total-len) (conj {:start pos :end total-len}))
        (let [{:keys [start end]} (first rs)
              acc' (if (< pos start) (conj acc {:start pos :end start}) acc)]
          (recur end (rest rs) acc'))))))

(def landmarks
  "Known interesting offsets to tag regions."
  [{:offset 0x0267 :label "Championship (primary)"}
   {:offset 0x0E5D :label "Top-3 Mountain"}
   {:offset 0x0F5D :label "Top-3 Desert"}
   {:offset 0x105D :label "Top-3 Riviera"}
  {:offset 0x115D :label "Top-3 Snowy"}])


(defn tags-for-region
  "Return labels for landmarks lying within [start,end)."
  [{:keys [start end]}]
  (->> landmarks
       (filter (fn [{:keys [offset]}] (and (<= start offset) (< offset end))))
       (map :label)
       vec))

(defn region-summary
  "Summarize non-blank regions with optional tags. opts: {:blank-byte 0x00 :min-blank-len 1}"
  ([^bytes data] (region-summary data {:blank-byte 0 :min-blank-len 1}))
  ([^bytes data {:keys [blank-byte min-blank-len] :or {blank-byte 0 min-blank-len 1}}]
   (let [n (alength data)
         blanks (find-blank-ranges data {:blank-byte blank-byte :min-len min-blank-len})
         regions (complement-ranges n blanks)]
     (mapv (fn [{:keys [start end] :as r}]
             (assoc r :size (- end start) :tags (tags-for-region r)))
           regions))))

(defn hex-dump-nonblank
  "Hex dump all non-blank regions with headers and tags. opts: {:blank-byte 0x00 :min-blank-len 1}"
  ([^bytes data] (hex-dump-nonblank data {:blank-byte 0 :min-blank-len 1}))
  ([^bytes data {:keys [blank-byte min-blank-len]}]
   (doseq [{:keys [start end tags size]} (region-summary data {:blank-byte blank-byte :min-blank-len min-blank-len})]
     (println)
     (println (format "Region %04x->%04x (size %d) %s"
                      start end size (if (seq tags) (str tags) "")))
     (hex-dump data start end))))

;; ==========================================
;; LANDMARK-FORWARD CHOPPING (stop at blank run)
;; ==========================================

(defn find-next-blank-run
  "Return the start index of the first run of blank-byte of length >= min-len at or after `from`.
   Returns nil if no such run is found. opts: {:blank-byte 0x00 :min-len 32}"
  ([^bytes data from] (find-next-blank-run data from {:blank-byte 0 :min-len 32}))
  ([^bytes data from {:keys [blank-byte min-len] :or {blank-byte 0 min-len 32}}]
   (let [n  (alength data)
         bb (bit-and blank-byte 0xFF)
         from (max 0 (min from n))]
     (loop [i from, run-start nil, run-len 0]
       (if (>= i n)
         nil
         (let [b (bit-and (aget data i) 0xFF)]
           (if (= b bb)
             (let [rs (or run-start i)
                   rl (inc run-len)]
               (if (>= rl min-len)
                 rs
                 (recur (inc i) rs rl)))
             (recur (inc i) nil 0))))))))

(defn landmark-regions
  "For each known landmark, return {:label :start :end :size}, where :end is the index
   of the next blank-byte run of length >= min-blank-len (or n if none). opts: {:blank-byte 0x00 :min-blank-len 32}"
  ([^bytes data] (landmark-regions data {:blank-byte 0 :min-blank-len 32}))
  ([^bytes data {:keys [blank-byte min-blank-len] :or {blank-byte 0 min-blank-len 32}}]
   (let [n (alength data)]
     (->> landmarks
          (keep (fn [{:keys [offset label]}]
                  (when (< offset n)
                    (let [end (or (find-next-blank-run data offset {:blank-byte blank-byte :min-len min-blank-len})
                                  n)]
                      {:label label :start offset :end end :size (- end offset)}))))
          vec))))

(defn hex-dump-landmark-regions
  "Hex dump regions starting at each landmark, stopping at the next blank run of length >= min-blank-len. opts: {:blank-byte 0x00 :min-blank-len 32}"
  ([^bytes data] (hex-dump-landmark-regions data {:blank-byte 0 :min-blank-len 32}))
  ([^bytes data {:keys [blank-byte min-blank-len]}]
   (doseq [{:keys [label start end size]} (landmark-regions data {:blank-byte blank-byte :min-blank-len min-blank-len})]
     (println)
     (println (format "%s: %04x->%04x (size %d)" label start end size))
     (hex-dump data start end))))

(comment
  (hex-dump-nonblank (read-nvram-bytes "srally2-data.nv") {:blank-byte 0x00 :min-blank-len 10})
  :rcf)

(defn bytes-slice
  "Copy a slice of a byte array [start, start+len)."
  [^bytes data start len]
  (let [out (byte-array len)]
    (System/arraycopy data start out 0 len)
    out))

(defn le24
  "Compose a 24-bit little-endian integer from bytes [lsb mid msb]."
  [lsb-20 mid-21 msb-16]
  (bit-or lsb-20 (bit-shift-left mid-21 8) (bit-shift-left msb-16 16)))

;; 60 ticks = 1 centisecond
(defn decode-time
  "Decode MM:SS.cc from [lsb-20 mid-21 msb-16], where 60 ticks = 1 centisecond."
  [lsb mid msb]
  (let [ticks (le24 lsb mid msb)
        cs    (quot ticks 60)
        m     (quot cs 6000)
        rem   (mod cs 6000)
        s     (quot rem 100)
        cc    (mod rem 100)]
    (format "%02d:%02d.%02d" m s cc)))

(defn extract-championship-leaderboard
  "- Each entry is 32 bytes (0x20)
   - time encoded in positions 16, 20, 21"
  [data start-offset]
  ; Extract all 16 championship entries
  (filterv some?
           (for [i (range 16)]
             (let [offset (+ start-offset (* i 0x20))
                   player-name (str (safe-char (aget data (+ offset 1)))
                                    (safe-char (aget data (+ offset 0)))
                                    (safe-char (aget data (+ offset 5))))
                   ; Extract time components from key positions
                   time-component-16 (hex-to-dec (aget data (+ offset 16)))
                   time-component-20 (hex-to-dec (aget data (+ offset 20)))
                   time-component-21 (hex-to-dec (aget data (+ offset 21)))

                   extracted-time (decode-time time-component-20 time-component-21 time-component-16)]

               {:entry i
                :time-component-16 time-component-16
                :time-component-20 time-component-20
                :time-component-21 time-component-21
                :championship-time extracted-time
                :structure-notes "32-byte record, time encoded in positions 12 16, 20, 21"
                :player-name player-name
                :raw-bytes (mapv #(bit-and (aget data (+ offset %)) 0xFF) (range 8))}))))  ; First 8 bytes for reference


(defn championship-leaderboard
  "Print championship leaderboard.
   opts: {:offset 0x267}. If called as [data], defaults to {:offset 0x267}."
  ([data] (championship-leaderboard data {:offset 0x267}))
  ([data {:keys [offset] :or {offset 0x267}}]
   (println "\nCHAMPIONSHIP LEADERBOARD STRUCTURE:")
   (println "======================================")
   (let [championship-data (extract-championship-leaderboard data offset)]
     (doseq [entry championship-data]
       (printf "Entry %2d: %s [%3d, %3d, %3d] → %s\n"
               (:entry entry)
               (:player-name entry)
               (:time-component-16 entry)
               (:time-component-20 entry)
               (:time-component-21 entry)
               (:championship-time entry))))))

;; ==========================================
;; TRACK TOP-3 EXTRACTION (reverse engineered)
;; ==========================================

(def track-tables
  "Discovered top-3 per-track table locations in NVRAM.
   Each table has 3 records at base, base+0x20, base+0x40.
   Time bytes live at offsets [30 31 26] within each 32-byte record."
  {:mountain {:base 0x0E5D :stride 0x20 :offsets [30 31 26]}
   :desert   {:base 0x0F5D :stride 0x20 :offsets [30 31 26]}
   :riviera  {:base 0x105D :stride 0x20 :offsets [30 31 26]}
   :snowy    {:base 0x115D :stride 0x20 :offsets [30 31 26]}})

(defn decode-at-offsets
  "Decode 3 times from a table at base with given stride and [lsb mid msb] offsets."
  [^bytes data base stride [o0 o1 o2]]
  (vec (for [k (range 3)
             :let [s (+ base (* k stride))
                   lsb (bit-and (aget data (+ s o0)) 0xFF)
                   mid (bit-and (aget data (+ s o1)) 0xFF)
                   msb (bit-and (aget data (+ s o2)) 0xFF)]]
         (decode-time lsb mid msb))))

(defn rec-initials
  "Extract player initials from a 32-byte record at base+k*stride.
   Initials are stored at offsets [11,10,15] (X,Y,Z)."
  [^bytes data base stride k]
  (let [s (+ base (* k stride))
        ch (fn [off]
             (let [b (bit-and (aget data (+ s off)) 0xFF)]
               (if (and (>= b 32) (<= b 126)) (char b) \.)))]
    (apply str (map ch [11 10 15]))))

(defn extract-track-top3
  "Return the vector of three entries {:time :initials} for one track using the table spec."
  [^bytes data {:keys [base stride offsets]}]
  (let [[o0 o1 o2] offsets]
    (vec
     (for [k (range 3)
           :let [s   (+ base (* k stride))
                 lsb (bit-and (aget data (+ s o0)) 0xFF)
                 mid (bit-and (aget data (+ s o1)) 0xFF)
                 msb (bit-and (aget data (+ s o2)) 0xFF)
                 t   (decode-time lsb mid msb)
                 ini (rec-initials data base stride k)]]
       {:time t :initials ini}))))

(defn extract-all-track-top3
  "Read all discovered per-track top-3 tables and return {track [{:time :initials} ...]}"
  [^bytes data]
  (into (sorted-map)
        (for [[trk spec] track-tables]
          [trk (extract-track-top3 data spec)])))

(defn print-all-track-top3
  "Pretty-print per-track top-3 from data. opts: {:with-initials? false}"
  ([^bytes data] (print-all-track-top3 data {:with-initials? false}))
  ([^bytes data {:keys [with-initials?] :or {with-initials? false}}]
   (println "\nTRACK TOP-3 TIMES:")
   (println "==================")
   (doseq [[trk entries] (extract-all-track-top3 data)]
     (let [fmt (fn [{:keys [time initials]}]
                 (if with-initials?
                   (str time " (" initials ")")
                   time))]
       (printf "%9s: %s\n" (name trk)
               (string/join ", " (map fmt entries)))))))

(defn compare-top3
  "Compare extracted top-3 with an expected map {track [t1 t2 t3]}.
   Returns only tracks where there is a difference."
  [^bytes data expected]
  (let [got (update-vals (extract-all-track-top3 data) #(mapv :time %))]
    (into (sorted-map)
          (for [[trk exp] expected
                :let [g (get got trk)]
                :when (not= (vec exp) (vec g))]
            [trk {:expected (vec exp) :got (vec g)}]))))

;; ==========================================
;; POTENTIAL TIME (best-per-track per player)
;; ==========================================

(defn parse-mmsscc->cs
  "Parse MM:SS.cc string into centiseconds."
  [s]
  (let [[_ mm ss cc] (re-matches #"(\d\d):(\d\d)\.(\d\d)" s)
        mm (Long/parseLong mm)
        ss (Long/parseLong ss)
        cc (Long/parseLong cc)]
    (+ (* mm 6000) (* ss 100) cc)))

(defn cs->mmsscc
  "Format centiseconds as MM:SS.cc"
  [cs]
  (let [mm (quot cs 6000)
        rem (mod cs 6000)
        ss (quot rem 100)
        cc (mod rem 100)]
    (format "%02d:%02d.%02d" mm ss cc)))

(defn player-best-per-track
  "Return {track {:time :initials}} for tracks where player's initials appear in top-3."
  [^bytes data player]
  (let [by-track (extract-all-track-top3 data)]
    (into (sorted-map)
          (keep (fn [[trk entries]]
                  (let [mine (filter #(= (:initials %) player) entries)]
                    (when (seq mine)
                      (let [best (apply min-key #(parse-mmsscc->cs (:time %)) mine)]
                        [trk best]))))
                by-track))))

(defn potential-time
  "Sum the track times in {track {:time ...}} and return {:centiseconds :time}."
  [best-map]
  (let [total-cs (reduce + 0 (map (comp parse-mmsscc->cs :time) (vals best-map)))]
    {:centiseconds total-cs
     :time (cs->mmsscc total-cs)}))

(defn player-best-championship-time
  "Return the best (lowest) championship time string for player initials, or nil if none."
  [^bytes data player]
  (let [entries (extract-championship-leaderboard data 0x267)
        times   (for [{:keys [player-name championship-time]} entries
                      :when (= player-name player)]
                  championship-time)]
    (when (seq times)
      (->> times (apply min-key parse-mmsscc->cs)))))

(defn signed-diff-mmsscc
  "Format signed difference (potential - best) in MM:SS.cc with sign."
  [potential-cs best-cs]
  (let [diff (- potential-cs best-cs)
        sign (if (neg? diff) "-" (if (pos? diff) "+" ""))
        mmss (cs->mmsscc (Math/abs diff))]
    (str sign mmss)))

(defn print-player-best-and-potential
  "Print per-track bests for player initials and the combined potential time."
  [^bytes data player]
  (let [best (player-best-per-track data player)
        tracks [:desert :mountain :snowy :riviera]
        have (set (keys best))]
    (println)
    (println (format "Best per track for %s:" player))
    (println "==============================")
    (doseq [trk tracks]
      (if-let [{:keys [time initials]} (get best trk)]
        (printf "%9s: %s (%s)\n" (name trk) time initials)
        (printf "%9s: -\n" (name trk))))
    (println "------------------------------")
    (if (= (count have) (count tracks))
      (let [pot (potential-time best)
            best-champ (player-best-championship-time data player)]
        (printf "Potential: %s\n" (:time pot))
        (printf "Championship best: %s\n" (or best-champ "-"))
        (when best-champ
          (let [diff (signed-diff-mmsscc (:centiseconds pot)
                                         (parse-mmsscc->cs best-champ))]
            (printf "Diff (potential - best): %s\n" diff)))
        pot)
      (do
        (printf "Incomplete: %d/%d tracks\n" (count have) (count tracks))
        nil))))

;; ==========================================
;; PRACTICE/RECORD HELPERS (championship regions)
;; ==========================================

(def ^:private rec-size 0x20)

(defn rec-name
  "Player initials/name as stored in a 32-byte record at offset `off`.
   Uses the same positions as championship entries (1, 0, 5)."
  [^bytes data off]
  (str (safe-char (aget data (+ off 1)))
       (safe-char (aget data (+ off 0)))
       (safe-char (aget data (+ off 5)))))

(defn rec-cs
  "Centiseconds decoded from [20,21,16] as little-endian 24-bit ticks (60 ticks = 1 cs)."
  [^bytes data off]
  (let [lsb (bit-and (aget data (+ off 20)) 0xFF)
        mid (bit-and (aget data (+ off 21)) 0xFF)
        msb (bit-and (aget data (+ off 16)) 0xFF)
        ticks (le24 lsb mid msb)]
    (quot ticks 60)))

(defn rec-time
  "MM:SS.cc string for the record at offset `off`."
  [^bytes data off]
  (cs->mmsscc (rec-cs data off)))

;; ------------------------------------------
;; PRACTICE TOP-8 PER TRACK
;; ------------------------------------------

(def track-practice-bases
  "Discovered base offsets (first entry) for practice Top-8 per track.
   Each track occupies 8 consecutive 32-byte records starting at the base.
   Verified in this NVRAM: Mountain 0x1467, Desert 0x1567, Riviera 0x1667, Snowy 0x1767."
  (sorted-map
   :desert   0x1567
   :mountain 0x1467
   :riviera  0x1667
   :snowy    0x1767))

(def track-order
  "Default order for practice report: championship track sequence."
  [:desert :mountain :snowy :riviera])

(defn extract-practice-top8-at
  "Return the 8 practice entries starting at base offset as
   [{:off :name :time :cs} ...]."
  [^bytes data base]
  (vec (for [k (range 8)
             :let [off (+ base (* k rec-size))]]
         {:off off
          :name (rec-name data off)
          :time (rec-time data off)
          :cs   (rec-cs   data off)})))

(defn extract-practice-top8
  "Using fixed bases, return {track [{:off :name :time :cs} ...]}."
  ([^bytes data]
   (extract-practice-top8 data track-practice-bases))
  ([^bytes data bases]
   (into (sorted-map)
         (for [[trk base] bases]
           [trk (extract-practice-top8-at data base)]))))

(defn print-practice-top8
  "Pretty-print Top-8 practice per track from fixed bases. opts: {:order track-order}"
  ([^bytes data] (print-practice-top8 data {:order track-order}))
  ([^bytes data {:keys [order] :or {order track-order}}]
   (println) (println "PRACTICE TOP-8 (fixed bases)")
   (println "==============================")
   (let [by (extract-practice-top8 data)]
     (doseq [trk order]
       (println (name trk))
       (doseq [{:keys [off name time]} (get by trk)]
         (println " " (format "0x%04x" off) name time)))
     by)))

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


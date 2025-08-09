(ns sr2.nv.extract
  "Settled extractors: championship, per-track top-3, practice top-8, and player summaries."
  (:require [clojure.string :as string]
            [sr2.nv.util :as u]))

;; Re-export util helpers as local fns for clarity inside this ns
(def read-nvram-bytes u/read-nvram-bytes)
(def safe-char        u/safe-char)
(def hex-to-dec       u/hex-to-dec)
(def bytes-slice      u/bytes-slice)
(def le24             u/le24)
(def decode-time      u/decode-time)
(def parse-mmsscc->cs u/parse-mmsscc->cs)
(def cs->mmsscc       u/cs->mmsscc)
(def rec-name         u/rec-name)
(def rec-cs           u/rec-cs)
(def rec-time         u/rec-time)

;; ==========================================
;; CHAMPIONSHIP LEADERBOARD (top-16)
;; ==========================================

(defn extract-championship-leaderboard
  "- Each entry is 32 bytes (0x20)
   - time encoded in positions 16, 20, 21"
  [data start-offset]
  (filterv some?
           (for [i (range 16)]
             (let [offset (+ start-offset (* i 0x20))
                   player-name (str (safe-char (aget data (+ offset 1)))
                                    (safe-char (aget data (+ offset 0)))
                                    (safe-char (aget data (+ offset 5))))
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
                :raw-bytes (mapv #(bit-and (aget data (+ offset %)) 0xFF) (range 8))}))))

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

(defn rec-initials
  "Extract player initials from a 32-byte record at base+k*stride.
   Initials are stored at offsets [11,10,15] (X,Y,Z)."
  [^bytes data base stride k]
  (let [s (+ base (* k stride))
        ch (fn [off]
             (let [b (bit-and (aget data (+ s off)) 0xFF)]
               (if (and (>= b 32) (<= b 126)) (char b) \.)))]
    (apply str (map ch [11 10 15]))))

(defn decode-at-offsets
  "Decode 3 times from a table at base with given stride and [lsb mid msb] offsets."
  [^bytes data base stride [o0 o1 o2]]
  (vec (for [k (range 3)
             :let [s (+ base (* k stride))
                   lsb (bit-and (aget data (+ s o0)) 0xFF)
                   mid (bit-and (aget data (+ s o1)) 0xFF)
                   msb (bit-and (aget data (+ s o2)) 0xFF)]]
         (decode-time lsb mid msb))))

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
               (clojure.string/join ", " (map fmt entries)))))))

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

;; ------------------------------------------
;; PRACTICE TOP-8 PER TRACK
;; ------------------------------------------

(def ^:private rec-size 0x20)

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

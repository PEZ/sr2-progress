
(ns sr2.nv.extract
  "Settled extractors: championship, per-track top-3, practice top-8, sector times, and player summaries."
  (:require [clojure.string :as string]
            [sr2.nv.util :as u]
            [sr2.nv.explore :as xpl]))

;; ==========================================
;; CHAMPIONSHIP LEADERBOARD (top-16)
;; ==========================================

(def ^:private champ-leaderboard-base 0x267)
(def ^:private champ-leaderboard-count 16)
(def ^:private champ-leaderboard-rec-size 0x20)

(defn extract-championship-leaderboard
  "Extract the top-16 championship leaderboard from NVRAM data at the given offset (default 0x267).
  Returns a vector of maps with keys: :entry :player-name :time-component-16 :time-component-20 :time-component-21 :championship-time."
  ([^bytes data] (extract-championship-leaderboard data champ-leaderboard-base))
  ([^bytes data offset]
   (vec
    (for [k (range champ-leaderboard-count)
          :let [off (+ offset (* k champ-leaderboard-rec-size))
                name (u/rec-name data off)
                lsb (u/hex->dec (aget data (+ off 20)))
                mid (u/hex->dec (aget data (+ off 21)))
                msb (u/hex->dec (aget data (+ off 16)))
                time (u/decode-time lsb mid msb)]]
      {:entry k
       :player-name name
       :time-component-16 msb
       :time-component-20 lsb
       :time-component-21 mid
       :championship-time time}))))

;; Use helpers directly from the aliased util ns (u/..)

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

(def championship-top-3-track-tables
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
             (let [b (u/hex->dec (aget data (+ s off)))]
               (if (and (>= b 32) (<= b 126)) (char b) \.)))]
    (apply str (map ch [11 10 15]))))

(defn decode-at-offsets
  "Decode 3 times from a table at base with given stride and [lsb mid msb] offsets."
  [^bytes data base stride [o0 o1 o2]]
  (vec (for [k (range 3)
             :let [s (+ base (* k stride))
                   lsb (u/hex->dec (aget data (+ s o0)))
                   mid (u/hex->dec (aget data (+ s o1)))
                   msb (u/hex->dec (aget data (+ s o2)))]]
         (u/decode-time lsb mid msb))))

(defn extract-track-top3
  "Return the vector of three entries {:time :initials} for one track using the table spec."
  [^bytes data {:keys [base stride offsets]}]
  (let [[o0 o1 o2] offsets]
    (vec
     (for [k (range 3)
           :let [s   (+ base (* k stride))
                 lsb (u/hex->dec (aget data (+ s o0)))
                 mid (u/hex->dec (aget data (+ s o1)))
                 msb (u/hex->dec (aget data (+ s o2)))
                   t   (u/decode-time lsb mid msb)
                 ini (rec-initials data base stride k)]]
       {:time t :initials ini}))))

(defn extract-all-championship-track-top3
  "Read all discovered per-track top-3 tables and return {track [{:time :initials} ...]}"
  [^bytes data]
  (into (sorted-map)
        (for [[trk spec] championship-top-3-track-tables]
          [trk (extract-track-top3 data spec)])))

(defn print-all-track-top3
  "Pretty-print per-track top-3 from data. opts: {:with-initials? false}"
  ([^bytes data] (print-all-track-top3 data {:with-initials? false}))
  ([^bytes data {:keys [with-initials?] :or {with-initials? false}}]
   (println "\nTRACK TOP-3 TIMES:")
   (println "==================")
   (doseq [[trk entries] (extract-all-championship-track-top3 data)]
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
  (let [got (update-vals (extract-all-championship-track-top3 data) #(mapv :time %))]
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
  (let [by-track (extract-all-championship-track-top3 data)]
    (into (sorted-map)
          (keep (fn [[trk entries]]
                  (let [mine (filter #(= (:initials %) player) entries)]
                    (when (seq mine)
                      (let [best (apply min-key #(u/parse-mmsscc->cs (:time %)) mine)]
                        [trk best]))))
                by-track))))

(defn potential-time
  "Sum the track times in {track {:time ...}} and return {:centiseconds :time}."
  [best-map]
  (let [total-cs (reduce + 0 (map (comp u/parse-mmsscc->cs :time) (vals best-map)))]
    {:centiseconds total-cs
     :time (u/cs->mmsscc total-cs)}))

(defn player-best-championship-time
  "Return the best (lowest) championship time string for player initials, or nil if none."
  [^bytes data player]
  (let [entries (extract-championship-leaderboard data 0x267)
        times   (for [{:keys [player-name championship-time]} entries
                      :when (= player-name player)]
                  championship-time)]
    (when (seq times)
      (->> times (apply min-key u/parse-mmsscc->cs)))))

(defn signed-diff-mmsscc
  "Format signed difference (potential - best) in MM:SS.cc with sign."
  [potential-cs best-cs]
  (let [diff (- potential-cs best-cs)
        sign (if (neg? diff) "-" (if (pos? diff) "+" ""))
        mmss (u/cs->mmsscc (Math/abs diff))]
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
                                         (u/parse-mmsscc->cs best-champ))]
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
          :name (u/rec-name data off)
          :time (u/rec-time data off)
          :cs   (u/rec-cs   data off)})))

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
   (println)
   (println "PRACTICE TOP-8 (fixed bases)")
   (println "==============================")
   (let [by (extract-practice-top8 data)]
     (doseq [trk order]
       (println (name trk))
       (doseq [{:keys [off name time]} (get by trk)]
         (println " " (format "0x%04x" off) name time)))
     by)))

;; ------------------------------------------
;; SECTOR TIMES (8-byte stride, 32 zero-bytes terminator)
;; ------------------------------------------

(def championship-sector-bases
  "Start offsets for championship best sector times per track. Values verified in sample NVRAM."
  (sorted-map
   :desert   0x21AF
   :mountain 0x1F2F
   :snowy    0x26AF
   :riviera  0x242F))

(def practice-sector-bases
  "Start offsets for practice best sector times per track."
  (sorted-map
   :desert   0x30AF
   :mountain 0x2E37
   :snowy    0x35B7
   :riviera  0x3337))

(def ^:private sector-stride 8)

(defn- region-end-at-zero-terminator
  "Return region end index: first run of >=32 zero bytes at or after `start`, or array length."
  [^bytes data start]
  (or (xpl/find-next-blank-run data start {:blank-byte 0 :min-len 32})
      (alength data)))

(defn- decode-sector-times-at
  "Decode sector times from `start` stepping 8 bytes until a 32x00 terminator is reached.
   Uses record layout [msb,0,0,0,lsb,mid,?,0] → u/decode-time lsb mid msb. Returns a vector of MM:SS.cc strings."
  [^bytes data start]
  (let [end (region-end-at-zero-terminator data start)
    last-inclusive (long (- end 6))
    idxs (range (long start) (inc last-inclusive) sector-stride)]
  (mapv (fn [i]
      (let [msb (u/hex->dec (aget data i))
          lsb (u/hex->dec (aget data (+ i 4)))
          mid (u/hex->dec (aget data (+ i 5)))]
        (u/decode-time lsb mid msb)))
      idxs)))

(defn extract-championship-best-sector-times
  "Return {track [MM:SS.cc ...]} of championship best sector times per track.
   Values are decoded directly from the sector tables; no padding is applied."
  [^bytes data]
  (into (sorted-map)
        (for [[trk base] championship-sector-bases]
          [trk (decode-sector-times-at data base)])))

(defn extract-practice-best-sector-times
  "Return {track [MM:SS.cc ...]} of practice best sector times per track."
  [^bytes data]
  (into (sorted-map)
        (for [[trk base] practice-sector-bases]
          [trk (decode-sector-times-at data base)])))

;; ------------------------------------------
;; Pretty printers for sector tables
;; ------------------------------------------

(defn- cumulative->splits
  "From a vector of cumulative MM:SS.cc times, compute per-sector split durations (same format).
   Assumes non-decreasing cumulative values."
  [cum]
  (when (seq cum)
    (let [cs (mapv u/parse-mmsscc->cs cum)]
      (->> (cons 0 cs)
           (partition 2 1)
           (keep (fn [[a b]]
                   (let [d (- b a)]
                     (when (pos? d)
                       (u/cs->mmsscc d)))))
           vec))))

(defn extract-championship-sector-splits
  "Return {track [MM:SS.cc ...]} per-sector split durations (derived from cumulative)."
  [^bytes data]
  (-> (extract-championship-best-sector-times data)
      (update-vals cumulative->splits)))

(defn extract-practice-sector-splits
  "Return {track [MM:SS.cc ...]} per-sector split durations (derived from cumulative)."
  [^bytes data]
  (-> (extract-practice-best-sector-times data)
      (update-vals cumulative->splits)))

(defn- normalize-track
  "Coerce user-provided track selector to a canonical keyword. Returns nil if unknown."
  [t]
  (cond
    (keyword? t) (#{:desert :mountain :snowy :riviera} t)
    (string? t) (let [k (keyword (clojure.string/lower-case t))]
                  (#{:desert :mountain :snowy :riviera} k))
    :else nil))

(defn- print-track-sectors
  [trk times]
  (println (format "%9s (%d): %s" (name trk) (count times) (clojure.string/join ", " times))))

(defn print-championship-sectors
  "Pretty-print championship best sector times. Options:
   {:track <keyword|string>  ; print only this track
    :order [:desert :mountain :snowy :riviera]
    :mode  :cumulative | :splits}"
  ([^bytes data]
   (print-championship-sectors data {:order track-order}))
  ([^bytes data {:keys [track order mode] :or {order track-order mode :cumulative}}]
   (let [by (case mode
              :splits (extract-championship-sector-splits data)
              :cumulative (extract-championship-best-sector-times data)
              (extract-championship-best-sector-times data))
         sel (if-let [t (normalize-track track)]
               (filter some? [t])
               (filter #(contains? by %) order))]
     (println)
     (println (if (= mode :splits) "CHAMPIONSHIP SECTORS (splits)" "CHAMPIONSHIP SECTORS"))
     (println "====================")
     (doseq [trk sel]
       (print-track-sectors trk (get by trk)))
     by)))

(defn print-practice-sectors
  "Pretty-print practice best sector times. Options are the same as print-championship-sectors."
  ([^bytes data]
   (print-practice-sectors data {:order track-order}))
  ([^bytes data {:keys [track order mode] :or {order track-order mode :cumulative}}]
   (let [by (case mode
              :splits (extract-practice-sector-splits data)
              :cumulative (extract-practice-best-sector-times data)
              (extract-practice-best-sector-times data))
         sel (if-let [t (normalize-track track)]
               (filter some? [t])
               (filter #(contains? by %) order))]
     (println)
     (println (if (= mode :splits) "PRACTICE SECTORS (splits)" "PRACTICE SECTORS"))
     (println "=================")
     (doseq [trk sel]
       (print-track-sectors trk (get by trk)))
     by)))

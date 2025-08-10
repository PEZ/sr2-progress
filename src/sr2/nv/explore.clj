(ns sr2.nv.explore
  "Exploratory tools: hex dumps, blank/non-blank scanning, and landmark-bounded regions."
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [sr2.nv.util :as u]))

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
              ascii (apply str (map u/safe-char bs))]
          (println (format "%04X: %-47s  |%s|" i hexs ascii))
          (recur line-end))))))

;; Hex dump variant that overlays plausible time patterns (6 bytes = two equal 24-bit values)
(defn- plausible-time-cs?
  "Heuristic: centiseconds in [min-cs,max-cs] inclusive. Defaults 3s..10:00.00."
  ([cs] (plausible-time-cs? cs 300 60000))
  ([cs min-cs max-cs]
   (and (<= (long min-cs) cs) (<= cs (long max-cs)))))


;; ------------------------------------------
;; Row-wise sliding-window time overlays
;; ------------------------------------------

(defn- ->digits
  "MMSSCC digits string from centiseconds."
  [cs]
  (-> (u/cs->mmsscc cs)
      (string/replace ":" "")
      (string/replace "." "")))

(defn- row-candidates
  "Within [row-start,row-end), return non-overlapping candidate windows of length 6
  that plausibly encode a time. Each candidate is {:start i :digits s :score n}.
  Two detection modes per 6-byte window i..i+5:
  - duplicate-triplet: [i..i+2] and [i+3..i+5] decode to the same plausible cs (score 1)
  - record-layout: [msb at i, zeros i+1..i+3, lsb at i+4, mid at i+5] (score 2)
  opts: {:min-cs 300 :max-cs 60000}"
  [^bytes data row-start row-end {:keys [min-cs max-cs] :or {min-cs 300 max-cs 60000}}]
  (let [b (fn [j] (bit-and (aget data j) 0xFF))
        last-start (max row-start (- row-end 6))]
    (loop [i row-start, acc []]
      (if (> i last-start)
        acc
        (let [lsb1 (b i) mid1 (b (inc i)) msb1 (b (+ i 2))
              lsb2 (b (+ i 3)) mid2 (b (+ i 4)) msb2 (b (+ i 5))
              cs1 (u/decode-cs lsb1 mid1 msb1)
              cs2 (u/decode-cs lsb2 mid2 msb2)
              r-msb (b i) r-lsb (b (+ i 4)) r-mid (b (+ i 5))
              r-cs  (u/decode-cs r-lsb r-mid r-msb)
              rec-zero-gap? (and (zero? (b (inc i))) (zero? (b (+ i 2))) (zero? (b (+ i 3))))
      dup? (and (= cs1 cs2) (plausible-time-cs? cs1 min-cs max-cs))
      rec? (and rec-zero-gap? (plausible-time-cs? r-cs min-cs max-cs))
              acc' (cond-> acc
        dup? (conj {:start i :digits (->digits cs1) :score 1 :kind :dup})
        rec? (conj {:start (inc i) :digits (->digits r-cs) :score 2 :kind :record}))]
          (recur (inc i) acc'))))))

(defn- choose-row-overlays
  "Greedy selection of non-overlapping candidates in a row. Prefer higher :score, then earlier :start."
  [cands]
  (let [cands (sort-by (juxt (comp - :score) (comp - :start)) cands)]
    (loop [chosen []
           occ #{}
           [c & more] cands]
      (if (nil? c)
        chosen
        (let [rng (set (range (:start c) (+ (:start c) 6)))]
          (if (empty? (set/intersection occ rng))
            (recur (conj chosen c) (into occ rng) more)
            (recur chosen occ more)))))))

(defn- scan-range-candidates
  "Scan [start,end) and return all time-window candidates (same shape as row-candidates)."
  [^bytes data start end opts]
  (row-candidates data start end opts))

(defn- choose-overlays
  "Choose a global set of non-overlapping overlays from a candidate list."
  [cands]
  (choose-row-overlays cands))

(defn- render-row-ascii-with-overlays
  "Render ASCII gutter for one row [row-start,row-end), overlaying selected time candidates."
  [^bytes data row-start row-end overlays]
  (let [idxs (range row-start row-end)
        bs   (map #(bit-and (aget data %) 0xFF) idxs)
        base (char-array (map u/safe-char bs))
        cands overlays]
    (doseq [{:keys [start digits]} cands]
      (doseq [[k ch] (map-indexed vector digits)]
        (let [j (- (+ start k) row-start)]
          (when (<= 0 j (dec (alength base)))
            (aset base j ch)))))
    (apply str base)))

(defn hex-dump-with-times
  "Hex dump like hex-dump, but overlays any detected 6-byte time windows with MMSSCC digits
   in the ASCII gutter. opts: {:min-cs 300 :max-cs 60000}."
  ([^bytes data start end]
   (hex-dump-with-times data start end {}))
  ([^bytes data start end opts]
   (let [n (alength data)
         start (max 0 (min (long start) n))
    end (max start (min (long end) n))
    overlays (choose-overlays (scan-range-candidates data start end opts))]
     (loop [i start]
       (when (< i end)
         (let [line-end (min end (+ i 32))
               idxs (range i line-end)
               bs   (map #(bit-and (aget data %) 0xFF) idxs)
               hexs (->> bs (map #(format "%02X" %))
                         (partition-all 8)
                         (map #(string/join " " %))
                         (string/join "  "))
      ascii (render-row-ascii-with-overlays data i line-end overlays)]
           (println (format "%04X: %-47s  |%s|" i hexs ascii))
           (recur line-end)))))))

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

(defn hex-dump-nonblank-with-times
  "Hex dump all non-blank regions with headers and tags, overlaying plausible time windows.
   scan-opts: {:blank-byte 0x00 :min-blank-len 1}
   time-opts: {:min-cs 300 :max-cs 60000}"
  ([^bytes data]
   (hex-dump-nonblank-with-times data {:blank-byte 0 :min-blank-len 1} {}))
  ([^bytes data scan-opts]
   (hex-dump-nonblank-with-times data scan-opts {}))
  ([^bytes data {:keys [blank-byte min-blank-len]} time-opts]
   (doseq [{:keys [start end tags size]} (region-summary data {:blank-byte (or blank-byte 0)
                                                              :min-blank-len (or min-blank-len 1)})]
     (println)
     (println (format "Region %04x->%04x (size %d) %s"
                      start end size (if (seq tags) (str tags) "")))
     (hex-dump-with-times data start end time-opts))))

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

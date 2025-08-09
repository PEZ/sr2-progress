(ns sr2.nv.explore
  "Exploratory tools: hex dumps, blank/non-blank scanning, and landmark-bounded regions."
  (:require [clojure.string :as string]
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

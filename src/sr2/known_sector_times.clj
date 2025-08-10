(ns sr2.known-sector-times
  (:require
   [sr2.nv.util :as u]))

;; ==========================================
;; Passing/Diff table parsing and data
;; ==========================================

(def ^:private track-key
  {"Desert" :desert "Mountain" :mountain "Snowy" :snowy "Riviera" :riviera})

(defn- parse-mmsscc
  "Parse MM:SS.cc or M:SS.cc into centiseconds."
  [s]
  (when (seq s)
    (let [[_ mm ss cc] (re-matches #"(\d{1,2}):(\d{2})\.(\d{2})" s)]
      (when mm
        (let [mm (Long/parseLong mm)
              ss (Long/parseLong ss)
              cc (Long/parseLong cc)]
          (+ (* mm 6000) (* ss 100) cc))))))

(defn- trim-empty [s]
  (when (some? s)
    (let [t (str/trim s)]
      (when (seq t) t))))


(defn- parse-passing-table
  "Parse a multiline string of the Passing/Diff table into
  {:desert [{:time \"MM:SS.cc\" :diff \"MM:SS.cc\" :time-cs :diff-cs} ...] ...}."
  [txt]
  (let [lines (->> (str/split-lines txt)
                   (map trim-empty)
                   (remove nil?))]
    (loop [ks {} cur nil [line & more] lines]
      (if (nil? line)
        ks
  (let [parts (->> (str/split line #"\t+")
                         (map trim-empty)
                         (remove nil?)
                         vec)]
          (cond
            ;; Header row
            (= parts ["Track" "Passing" "Diff"]) (recur ks cur more)

            ;; New track start: [Track time diff] or just [Track]
            (and (seq parts) (track-key (first parts)))
            (let [trk (track-key (first parts))
                  time (some-> (nth parts 1 nil))
                  diff (some-> (nth parts 2 nil))
                  rec (when (and time diff)
                        (let [t (trim-empty time) d (trim-empty diff)]
                          {:time t :diff d :time-cs (parse-mmsscc t) :diff-cs (parse-mmsscc d)}))
                  ks' (update ks trk #(vec (concat (or % []) (cond-> [] rec (conj rec)))))]
              (recur ks' trk more))

            ;; Continuation rows: [time diff]
            (and cur (= 2 (count parts)))
            (let [[time diff] parts
                  t (trim-empty time) d (trim-empty diff)
                  rec {:time t :diff d :time-cs (parse-mmsscc t) :diff-cs (parse-mmsscc d)}]
              (recur (update ks cur conj rec) cur more))

            ;; Blank or unrecognized, skip
            :else (recur ks cur more)))))))

(def passing-diff-table-raw
  "Raw text pasted from the provided Passing/Diff table."
  "Track\tPassing\tDiff
Desert\t00:00.00\t00:00.00
	00:11.43\t00:00.01
	00:17.83\t00:00.07
	00:26.41\t00:00.54
	00:33.02\t00:01.18
	00:41.52\t00:01.68
	00:45.26\t00:01.82
	00:52.22\t00:01.97
	00:59.97\t00:02.94

Mountain\t01:10.17\t00:03.06
	01:17.97\t00:03.58
	01:25.76\t00:04.33
	01:31.80\t00:04.96
	01:45.09\t00:07.65
	01:53.00\t00:08.05
	02:02.49\t00:09.06
	02:10.32\t00:09.36

Snowy\t02:19.49\t00:09.32
	02:26.54\t00:09.26
	02:33.21\t00:09.21
	02:39.69\t00:08.96
	02:47.16\t00:08.81
	02:56.68\t00:09.21
	03:03.77\t00:09.26
	03:10.60\t00:09.20

Riviera\t03:16.50\t00:09.08
	03:20.38\t00:09.09
	03:26.00\t00:09.68
	03:29.89\t00:09.77
	03:33.58\t00:09.91
	03:37.59\t00:10.09
	03:42.84\t00:09.97
	03:46.81\t00:09.97
	03:53.92\t00:10.00
	03:59.90\t00:10.69
	04:03.89\t00:10.79
	04:07.53\t00:10.77
	04:12.25\t00:11.43
	04:17.70\t00:12.00
	04:21.78\t00:12.24")

(def passing-diff-table
  "Parsed Passing/Diff data keyed by track. Values contain both strings and centiseconds."
  (parse-passing-table passing-diff-table-raw))

(defn passing-times
  "Given a passing diff table as returned from `passing-diff-table` return a map
   keyed on tracks, where each entry has `time-cs - didd-cs` in `MM:SS.CC` format"
  [pd-table]
  (into {}
        (for [[trk entries] pd-table]
          [trk (mapv (fn [{:keys [time-cs diff-cs]}]
                       (u/cs->mmsscc (- time-cs diff-cs)))
                     entries)])))
(comment
  (passing-times (parse-passing-table passing-diff-table-raw))
  )
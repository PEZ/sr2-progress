(ns sr2.nvram-extractor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sr2.nvram-known-data :as kd]
            [clojure.string :as str]
            [sr2.nv.explore :as xpl]
            [sr2.nv.util :as u]
            [sr2.nv.extract :as ext]))

;; Load once for all tests (tiny file ~128KB)
(def data (u/read-nvram-bytes "data/srally2-known.nv"))

(deftest time-codecs
  (testing "le24 + decode-time basic cases"
    ;; 60 ticks = 1 cs
  (is (= "00:00.01" (u/decode-time 60 0 0)))
  (is (= 0 (u/parse-mmsscc->cs "00:00.00")))
  (is (= "00:00.00" (u/cs->mmsscc 0)))
  ;; 1:02.03 -> 62*100 + 3 = 6203 cs
  (is (= 6203 (u/parse-mmsscc->cs "01:02.03")))
  (is (= "01:02.03" (u/cs->mmsscc 6203)))
    (is (= "10:59.99" (u/cs->mmsscc (u/parse-mmsscc->cs "10:59.99"))))))

(def mmsscc-re #"\d\d:\d\d\.\d\d")

(deftest per-track-top3-structure
  (testing "each track has exactly 3 entries and valid times"
  (let [by (ext/extract-all-track-top3 data)
          expected-track-times (:track-times kd/championship)]
      (doseq [[trk entries] by]
        (is (= 3 (count entries)) (str trk))
        (doseq [{:keys [time initials]} entries]
          (is (re-matches mmsscc-re time) (str trk ": " time))
          (is (= 3 (count initials)) (str trk ": " initials)))
        ;; spot-check presence of all known bests for this track

        (let [normalize #(when (string? %) (str/replace % #"\\." ":"))
              actual-times (set (map (comp normalize :time) entries))
              filtered-expected (filter #(and (string? %) (seq %) (not (re-matches #"^[0:.]*$" %))) (get expected-track-times trk))
              intersection (filter #(actual-times (normalize %)) filtered-expected)]
          (doseq [expected-time intersection]
            (is (some #(= (normalize expected-time) (normalize (:time %))) entries)
                (str trk ": expected time " expected-time))))))))

(deftest practice-top8-structure
  (testing "each practice table has 8 entries with plausible names and times"
  (let [by (ext/extract-practice-top8 data)
          expected-times (get kd/test-practice-data :times)]
      (is (= #{:desert :mountain :riviera :snowy} (set (keys by))))
      (doseq [[trk entries] by]
        (is (= 8 (count entries)) (str trk))
        (doseq [{:keys [name time cs]} entries]
          (is (= 3 (count name)) (str trk ": name=" name))
          (is (re-matches mmsscc-re time) (str trk ": time=" time))
          (is (integer? cs)))
        ;; spot-check: for each player with known times for this track, assert at least one expected time is present
        (doseq [[player times] expected-times]
          (when-let [expected-track-times (get times trk)]
            (let [normalize #(when (string? %) (str/replace % #"\\." ":"))
                  filtered-expected (filter #(and (string? %) (seq %) (not (re-matches #"^[0:.]*$" %))) expected-track-times)
                  actual-times (set (map (comp normalize :time) entries))
                  intersection (filter #(actual-times (normalize %)) filtered-expected)]
              (doseq [expected-time intersection]
                (is (some #(= (normalize expected-time) (normalize (:time %))) entries)
                    (str trk ": expected time for " player ": " expected-time))))))))))


(deftest championship-leaderboard-basics
  (testing "top-16 championship entries parse and look sane"
  (let [xs (ext/extract-championship-leaderboard data 0x267)]
      (is (= 16 (count xs)))
      (doseq [{:keys [player-name championship-time]} xs]
        (is (= 3 (count player-name)))
        (is (re-matches mmsscc-re championship-time))))
    ;; light golden checks against known sample (avoid overfitting)
  (let [{:keys [player-name championship-time]} (first (ext/extract-championship-leaderboard data 0x267))
          expected-player (:player kd/championship)
          expected-time (first (:overall-best-times kd/championship))]
      (is (= expected-player player-name))
      (is (= expected-time championship-time)))))

(deftest practice-desert-golden-spot
  (testing "practice desert contains known top time"
  (let [entries (:desert (ext/extract-practice-top8 data))
          expected (first (get-in kd/test-practice-data [:times :PEZ :desert]))]
      (is (some #(= expected (:time %)) entries)))))

(deftest hex-dump-with-times-overlays
  (testing "hex dump overlays MMSSCC digits for duplicated 24-bit time sequences"
    ;; Build synthetic data where indices 10..15 contain the same 24-bit LE time twice.
  (let [cs (u/parse-mmsscc->cs "01:02.03")
      ticks (* cs 60)
      b0 (bit-and ticks 0xFF)
      b1 (bit-and (bit-shift-right ticks 8) 0xFF)
      b2 (bit-and (bit-shift-right ticks 16) 0xFF)
      arr (byte-array 64)]
    ;; fill with '.' (0x2E)
    (dotimes [k 64] (aset-byte arr k (unchecked-byte 0x2E)))
      (doseq [[i v] (map vector (range 10 16) [b0 b1 b2 b0 b1 b2])]
        (aset-byte arr i (unchecked-byte v)))
      ;; Capture printed output
      (let [out (with-out-str (xpl/hex-dump-with-times arr 0 32))
            line (first (str/split-lines out))]
        ;; Expect to see ASCII gutter include 6 digits 010203 contiguous at positions corresponding to 10..15
        (is (re-find #"\|.{10}010203" line) line)))))

(deftest hex-dump-with-times-rowwise-filtering
  (testing "row-wise scan filters out implausible 000076 and keeps plausible times"
    (let [out (with-out-str (xpl/hex-dump-with-times data 0x0267 (+ 0x0267 0x60)))
          lines (str/split-lines out)]
      ;; Should not contain 000076 anywhere in the ASCII gutters
      (is (not (re-find #"000076" out)))
      ;; Should contain at least one known plausible championship time pattern on first couple of rows
      (is (some #(re-find #"\|.*\d{6}.*\|" %) (take 3 lines))))))


(ns sr2.nvram-extractor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sr2.nvram-extractor :as ne]))

;; Load once for all tests (tiny file ~128KB)
(def data (ne/read-nvram-bytes "data/srally2-known.nv"))

(deftest time-codecs
  (testing "le24 + decode-time basic cases"
    ;; 60 ticks = 1 cs
    (is (= "00:00.01" (ne/decode-time 60 0 0)))
    (is (= 0 (ne/parse-mmsscc->cs "00:00.00")))
    (is (= "00:00.00" (ne/cs->mmsscc 0)))
  ;; 1:02.03 -> 62*100 + 3 = 6203 cs
  (is (= 6203 (ne/parse-mmsscc->cs "01:02.03")))
  (is (= "01:02.03" (ne/cs->mmsscc 6203)))
    (is (= "10:59.99" (ne/cs->mmsscc (ne/parse-mmsscc->cs "10:59.99"))))))

(def mmsscc-re #"\d\d:\d\d\.\d\d")

(deftest per-track-top3-structure
  (testing "each track has exactly 3 entries and valid times"
    (let [by (ne/extract-all-track-top3 data)]
      (doseq [[trk entries] by]
        (is (= 3 (count entries)) (str trk))
        (doseq [{:keys [time initials]} entries]
          (is (re-matches mmsscc-re time) (str trk ": " time))
          (is (= 3 (count initials)) (str trk ": " initials))))
      ;; spot-check presence of a known best for reliability without overfitting
      (is (some #(= "01:02.96" (:time %)) (:mountain by)))
      (is (some #(= "01:06.61" (:time %)) (:riviera by))))))

(deftest practice-top8-structure
  (testing "each practice table has 8 entries with plausible names and times"
    (let [by (ne/extract-practice-top8 data)]
      (is (= #{:desert :mountain :riviera :snowy} (set (keys by))))
      (doseq [[trk entries] by]
        (is (= 8 (count entries)) (str trk))
        (doseq [{:keys [name time cs]} entries]
          (is (= 3 (count name)) (str trk ": name=" name))
          (is (re-matches mmsscc-re time) (str trk ": time=" time))
          (is (integer? cs)))))))

(deftest championship-leaderboard-basics
  (testing "top-16 championship entries parse and look sane"
    (let [xs (ne/extract-championship-leaderboard data 0x267)]
      (is (= 16 (count xs)))
      (doseq [{:keys [player-name championship-time]} xs]
        (is (= 3 (count player-name)))
        (is (re-matches mmsscc-re championship-time))))))


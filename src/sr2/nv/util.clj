(ns sr2.nv.util
  "Low-level utilities for Sega Rally 2 NVRAM parsing: bytes, time codecs, and record helpers."
  (:require [clojure.java.io :as io]))

;; Authoritative chunk bounds used by higher-level code to avoid the duplicate copy at +0x10000.
(def ^:const main-chunk-start 0x0147)
(def ^:const main-chunk-end   0x38C0)

(defn read-nvram-bytes
  "Read NVRAM file as byte array."
  [file-path]
  (with-open [stream (io/input-stream file-path)]
    (.readAllBytes stream)))

(defn safe-char
  "Convert byte to ASCII char"
  [non-printable-char byte-val]
  (let [b (bit-and byte-val 0xFF)]
    (if (and (>= b 32) (<= b 126))
      (char b)
      non-printable-char)))

(defn hex->dec [byte]
  (if (nil? byte) 0 (bit-and byte 0xff)))

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
(defn decode-cs
  "Decode centiseconds from [lsb mid msb], where 60 ticks = 1 centisecond."
  [lsb mid msb]
  (let [ticks (le24 lsb mid msb)]
    (quot ticks 60)))

(defn cs->mmsscc
  "Format centiseconds as MM:SS.cc"
  [cs]
  (let [mm (quot cs 6000)
        rem (mod cs 6000)
        ss (quot rem 100)
        cc (mod rem 100)]
    (format "%02d:%02d.%02d" mm ss cc)))

(defn decode-time
  "Decode MM:SS.cc from [lsb mid msb], where 60 ticks = 1 centisecond."
  [lsb mid msb]
  (cs->mmsscc (decode-cs lsb mid msb)))

(defn parse-mmsscc->cs
  "Parse MM:SS.cc string into centiseconds."
  [s]
  (let [[_ mm ss cc] (re-matches #"(\d\d):(\d\d)\.(\d\d)" s)
        mm (Long/parseLong mm)
        ss (Long/parseLong ss)
        cc (Long/parseLong cc)]
    (+ (* mm 6000) (* ss 100) cc)))

;; ------------------------------------------
;; 32-byte record helpers (championship/practice style)
;; ------------------------------------------

(def ^:private rec-size 0x20)

(defn rec-name
  "Player initials/name as stored in a 32-byte record at offset `off`.
   Uses the same positions as championship entries (1, 0, 5)."
  [^bytes data off]
  (str ((partial safe-char \.) (aget data (+ off 1)))
       ((partial safe-char \.) (aget data (+ off 0)))
       ((partial safe-char \.) (aget data (+ off 5)))))


(defn rec-cs
  "Centiseconds decoded from [20,21,16] as little-endian 24-bit ticks (60 ticks = 1 cs)."
  [^bytes data off]
  (let [lsb (hex->dec (aget data (+ off 20)))
        mid (hex->dec (aget data (+ off 21)))
        msb (hex->dec (aget data (+ off 16)))]
    (decode-cs lsb mid msb)))

(defn rec-time
  "MM:SS.cc string for the record at offset `off`."
  [^bytes data off]
  (cs->mmsscc (rec-cs data off)))

(defn record-size [] rec-size)

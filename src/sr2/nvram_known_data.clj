(ns sr2.nvram-known-data)

;; ==========================================
;; REVERSE ENGINEERING TEST DATA
;; ==========================================
;; Known championship and practice times from actual gameplay
;; Used to reverse engineer the NVRAM data format

(def championship
  "Known championship times from PEZ's actual gameplay for reverse engineering"
  {:player "PEZ"
   :car "Peugeot 306 Maxi"
   :mode :championship
   :overall-best-times ["04:09.54" "04:10.20" "04:10.32" "04:10.49" "04:10.56"]
   :player-xyz {:championship "04:08.20"}
   :track-times
   {:desert ["00:57.03" "00:57.23" "00:57.27"]
    :mountain ["01:02.96" "01:03.07" "01:03.15"]
    :snowy ["00:59.74" "01:00.08" "01:00.44"]
    :riviera ["01:06.61" "01:07.17" "01:07.18"]}
   :additional-notes {:snowy-fourth "01:00.49"}})

(def test-practice-data
  "Known practice times for reverse engineering"
  {:mode :practice
   :times
   {:PEZ {:riviera ["02.47.43" "02:53.66"]
          :desert ["02:46.83"]
          :mountain ["03:08.02"]
          :snowy ["02.57.64"]}
    :XYZ {:desert ["02:44.52"]
          :mountain ["03:09.54"]
          :snowy ["02:55.68"]}
    :VGO {:riviera ["02:59.13"]}
    :SAS {:desert "10:00.00"
          :mountain "10:00.00"
          :snowy "10:00.00"}}})

(def championship-sector-times
  "Sector times for the `04:09.54` championship run"
  {:desert ["00:11.42" "00:17.76" "00:25.87" "00:31.84" "00:39.84" "00:43.44" "00:50.25" "00:57.03"],
   :mountain ["01:07.11" "01:14.39" "01:21.43" "01:26.84" "01:37.44" "01:44.95" "01:53.43" "02:00.96"],
   :riviera ["03:07.42" "03:11.29" "03:16.32" "03:20.12" "03:23.67" "03:27.50" "03:32.87" "03:36.84"
             "03:43.92" "03:49.21" "03:53.10" "03:56.76" "04:00.82" "04:05.70" "04:09.54"],
   :snowy ["02:10.17" "02:17.28" "02:24.00" "02:30.73" "02:38.35" "02:47.47" "02:54.51" "03:01.40"]})

(def expected-players
  "Known player names to search for"
  ["PEZ" "VGO" "VGG" "SAS"])

(def expected-cars
  "Known car names/codes to search for"
  ["Peugeot 306 Maxi" "306" "PEUGEOT" "MAXI"])

(def track-names
  "Sega Rally 2 track names to search for"
  ["Desert" "Mountain" "Snowy" "Riviera" "DESERT" "MOUNTAIN" "SNOWY" "RIVIERA"])

;; * The game displays the best three times for each track when playing the track in Championship mode.

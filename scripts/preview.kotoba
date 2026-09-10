(ns preview
  "Render the 予約 pages to files so they can be looked at and scored.

  Fixed inputs, no clock: the page must be byte-identical between runs or the
  design-quality gate scores a different document each time and its number
  means nothing."
  (:require [clojure.java.io :as io]
            [yotei.availability :as av]
            [yotei.render :as render]
            [yotei.time :as t]
            [yotei.view :as view]))

(def cal
  (av/calendar "did:web:app.itonami.cloud:yotei:calendar:jun"
               {:yotei/tz-offset-min 540
                :yotei/slot-min 30
                :yotei/windows [(av/window :monday "10:00" "12:00")
                                (av/window :monday "14:00" "16:00")
                                (av/window :tuesday "10:00" "12:00")
                                (av/window :wednesday "14:00" "17:00")]}))

(def now (t/parse-instant "2026-03-06T00:00:00Z"))

(def taken
  [{"status" "confirmed" "calendarDid" (:yotei/calendar-did cal)
    "startEpochMin" (t/parse-instant "2026-03-09T01:30:00Z") "durationMin" 30}])

(defn -main [& _]
  (let [openings (av/openings cal now (+ now (* 9 1440)) taken now)
        ctx {:owner-label "川崎"
             :purpose "30分の打ち合わせです。空いている時間を選んでください。"
             :calendar cal
             :openings openings}
        slot (t/parse-instant "2026-03-09T01:00:00Z")
        pages {"yoyaku-openings.html" (render/booking-document ctx)
               "yoyaku-confirm.html"
               (render/document {:title "この時間で申し込む — yotei"}
                                (view/confirm-form (assoc ctx :start-epoch-min slot
                                                          :duration-min 30)))
               "yoyaku-proposed.html"
               (render/document {:title "申し込みを受け付けました — yotei"}
                                (view/proposed-page (assoc ctx :start-epoch-min slot
                                                           :duration-min 30)))
               "yoyaku-refused.html"
               (render/document {:title "この時間は取れませんでした — yotei"}
                                (view/refused-page
                                 {:reason "その時間は先に確定した予約と重なりました。"}))}]
    (.mkdirs (io/file "target/preview"))
    (doseq [[name html] pages]
      (spit (io/file "target/preview" name) html)
      (println name (count html) "bytes"))
    (println "openings:" (count openings))))

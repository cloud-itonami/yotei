(ns yotei.store-test
  "The store's job is to make two people taking the same slot impossible, not
  unlikely. These tests drive the races directly rather than hoping."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.availability :as av]
            [yotei.store :as store]
            [yotei.time :as t]))

(def DID "did:web:app.itonami.cloud:yotei:calendar:alice")

(def cal
  (av/calendar DID {:yotei/tz-offset-min 540
                    :yotei/slot-min 30
                    :yotei/windows [(av/window :monday "10:00" "12:00")]}))

(def now (t/parse-instant "2026-03-08T00:00:00Z"))
(def slot (t/parse-instant "2026-03-09T01:00:00Z"))     ; Monday 10:00 JST

(defn- fresh [] (store/memory-store {DID cal}))

(defn- req [id start & {:as over}]
  (merge {"yoyakuId" id "requesterDid" "did:plc:bob" "responderDid" "did:plc:alice"
          "startEpochMin" start "durationMin" 30 "consentRef" "consent-1"}
         over))

(defn- member [ref] {"origin" "member" "ref" ref})

;; ── the happy path, end to end through the log ──
(deftest propose-then-confirm-lands-in-the-log
  (let [s (fresh)
        p (store/propose! s DID (req "y1" slot) now)]
    (is (= "proposed" (get p "state")))
    (testing "a proposal does not yet block the slot"
      (is (= [] (store/confirmed s DID))))
    (let [c (store/confirm! s DID "y1" (member "sig-1"))]
      (is (= "confirmed" (get c "status")))
      (is (= 1 (count (store/confirmed s DID))))
      (testing "and the log kept both facts (G3 append-only)"
        (is (= ["proposed" "confirmed"] (mapv #(get % "status") (store/history s DID))))))))

(deftest confirming-removes-the-slot-from-what-is-offered
  (let [s (fresh)]
    (store/propose! s DID (req "y1" slot) now)
    (store/confirm! s DID "y1" (member "sig-1"))
    (let [offered (store/openings s DID slot (+ slot 1440) now)]
      (is (not (some #(= slot (:yotei/start-epoch-min %)) offered))))))

;; ── G4 under contention ──
(deftest a-second-proposal-for-a-confirmed-slot-is-refused
  (let [s (fresh)]
    (store/propose! s DID (req "y1" slot) now)
    (store/confirm! s DID "y1" (member "sig-1"))
    (let [p2 (store/propose! s DID (req "y2" slot) now)]
      (is (= "refused" (get p2 "state"))))))

(deftest two-proposals-can-coexist-until-one-is-confirmed
  ;; Honest consequence of G5: yotei holds no key, so nothing is held until the
  ;; owner signs. The page says so; this pins that it is the actual behaviour.
  (let [s (fresh)]
    (is (= "proposed" (get (store/propose! s DID (req "y1" slot) now) "state")))
    (is (= "proposed" (get (store/propose! s DID (req "y2" slot) now) "state")))
    (is (= "confirmed" (get (store/confirm! s DID "y1" (member "s1")) "status")))
    (testing "and the loser is refused at confirm, not silently double-booked"
      (let [c2 (store/confirm! s DID "y2" (member "s2"))]
        (is (get c2 "refused"))
        (is (= 1 (count (store/confirmed s DID))))))))

;; ── the availability gate the lifecycle alone does not have ──
(deftest a-slot-outside-every-window-is-refused-even-though-nothing-took-it
  (let [s (fresh)
        sunday-3am (t/parse-instant "2026-03-08T03:00:00Z")]
    (is (= "refused" (get (store/propose! s DID (req "y1" sunday-3am) now) "state")))))

(deftest a-slot-off-the-grid-is-refused
  (let [s (fresh)]
    (is (= "refused" (get (store/propose! s DID (req "y1" (+ slot 15)) now) "state")))))

(deftest an-unknown-calendar-is-refused
  (let [s (fresh)]
    (is (= "refused" (get (store/propose! s "did:web:nope" (req "y1" slot) now) "state")))))

;; ── G8 / G5 still hold through the store ──
(deftest consent-is-required
  (let [s (fresh)]
    (is (= "refused" (get (store/propose! s DID (req "y1" slot "consentRef" "") now) "state")))))

(deftest a-server-signature-cannot-confirm
  (let [s (fresh)]
    (store/propose! s DID (req "y1" slot) now)
    (let [c (store/confirm! s DID "y1" {"origin" "server" "ref" "x"})]
      (is (get c "refused"))
      (is (= [] (store/confirmed s DID))))))

;; ── cancel releases ──
(deftest cancelling-frees-the-slot-again
  (let [s (fresh)]
    (store/propose! s DID (req "y1" slot) now)
    (store/confirm! s DID "y1" (member "s1"))
    (store/cancel! s DID "y1")
    (is (= [] (store/confirmed s DID)))
    (testing "and the slot is offered again"
      (is (some #(= slot (:yotei/start-epoch-min %))
                (store/openings s DID slot (+ slot 1440) now))))
    (testing "while the log still remembers it was confirmed"
      (is (= ["proposed" "confirmed" "cancelled"]
             (mapv #(get % "status") (store/history s DID)))))))

;; ── compare-and-set ──
(deftest append-refuses-a-stale-version
  (let [s (fresh)
        [_ v0] (store/-log s DID)]
    (is (some? (store/-append! s DID {"yoyakuId" "a" "status" "proposed"} v0)))
    (testing "the same version cannot be used twice"
      (is (nil? (store/-append! s DID {"yoyakuId" "b" "status" "proposed"} v0))))))

(deftest propose-survives-one-lost-race
  ;; An unrelated 予約 lands between our read and our write. The retry re-reads
  ;; and succeeds, because the slot we want is still free.
  (let [s (fresh)
        other-slot (+ slot 30)]
    (store/-append! s DID {"yoyakuId" "x" "status" "confirmed"
                           "calendarDid" DID "startEpochMin" other-slot
                           "durationMin" 30}
                    (second (store/-log s DID)))
    (is (= "proposed" (get (store/propose! s DID (req "y1" slot) now) "state")))))

(deftest the-fold-keeps-only-the-latest-fact-per-yoyaku
  (let [s (fresh)]
    (store/propose! s DID (req "y1" slot) now)
    (store/confirm! s DID "y1" (member "s1"))
    (store/cancel! s DID "y1")
    (is (= 3 (count (store/history s DID))))
    (is (= "cancelled" (get (store/by-id s DID "y1") "status")))))

(ns yotei.seat-test
  "席 — the capacity layer. The property under test is the one a person's
  calendar does not have: nineteen o'clock can be taken and available at the
  same time, because there is more than one table.

  Also under test is what this layer must NOT reveal — `open-times` returns
  instants and never how many tables are behind them (G6)."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.availability :as availability]
            [yotei.seat :as seat]
            [yotei.time :as t]))

(def REST "did:web:app.itonami.cloud:calendar:torikai")

;; 2026-08-20 is a Thursday. 19:00 JST = 10:00 UTC.
(def ^:private day-2026-08-20 (t/days-from-civil 2026 8 20))
(def ^:private t19-jst (+ (* day-2026-08-20 1440) (- (* 19 60) 540)))

(defn- flr []
  (seat/floor REST
              [(seat/table REST "t2a" 2)
               (seat/table REST "t2b" 2)
               (seat/table REST "t4" 4)
               (seat/table REST "t6" 6)]
              {:yotei/slot-min 90
               :yotei/tz-offset-min 540
               :yotei/notice-min 60
               :yotei/horizon-days 30
               ;; 17:30–22:00 with a 90-minute seating puts the seatings at
               ;; 17:30 / 19:00 / 20:30 — the last one ends exactly at closing.
               :yotei/windows [(availability/window :thursday "17:30" "22:00")]}))

(defn- confirmed-on [table-id start dur]
  {"status" "confirmed" "calendarDid" (seat/table-did REST table-id)
   "startEpochMin" start "durationMin" dur})

;; ── the floor refuses what it cannot represent ───────────────────────────────

(deftest test-table-refuses-zero-seats
  (is (thrown? #?(:clj Exception :cljs js/Error) (seat/table REST "t0" 0))))

(deftest test-floor-refuses-duplicate-table-ids
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (seat/floor REST [(seat/table REST "t4" 4) (seat/table REST "t4" 2)]))))

(deftest test-floor-refuses-empty
  (is (thrown? #?(:clj Exception :cljs js/Error) (seat/floor REST []))))

;; ── capacity: the same instant is both taken and free ────────────────────────

(deftest test-one-table-taken-leaves-the-others
  (let [confirmed [(confirmed-on "t4" t19-jst 90)]]
    (testing "the taken table is gone"
      (is (not-any? #(= "t4" (:yotei/table-id %))
                    (seat/free-tables (flr) confirmed t19-jst 90 4))))
    (testing "but 19:00 still seats a party of four"
      (is (seat/seatable? (flr) confirmed t19-jst 90 4)))))

(deftest test-all-fitting-tables-taken-refuses
  (let [confirmed [(confirmed-on "t4" t19-jst 90) (confirmed-on "t6" t19-jst 90)]]
    (is (= :all-tables-taken
           (:yotei/refused (seat/assign (flr) confirmed t19-jst 90 4))))))

(deftest test-party-too-large-is-a-different-refusal
  (is (= :no-table-large-enough
         (:yotei/refused (seat/assign (flr) [] t19-jst 90 8))))
  (testing "and it is not confused with a busy night"
    (is (not= :all-tables-taken
              (:yotei/refused (seat/assign (flr) [] t19-jst 90 8))))))

(deftest test-bad-party-size-refused
  (is (= :bad-party-size (:yotei/refused (seat/assign (flr) [] t19-jst 90 0))))
  (is (= :bad-party-size (:yotei/refused (seat/assign (flr) [] t19-jst 90 nil)))))

;; ── which table, deterministically ───────────────────────────────────────────

(deftest test-smallest-sufficient-table-first
  (is (= "t4" (get-in (seat/assign (flr) [] t19-jst 90 3) [:yotei/table :yotei/table-id])))
  (testing "a pair does not get the six-top while a two-top is free"
    (is (= "t2a" (get-in (seat/assign (flr) [] t19-jst 90 2) [:yotei/table :yotei/table-id])))))

(deftest test-assignment-is-a-total-order
  (testing "equal-seat tables break the tie by id, so a governor recomputes the same one"
    (is (= "t2a" (get-in (seat/assign (flr) [] t19-jst 90 2) [:yotei/table :yotei/table-id])))
    (is (= "t2b" (get-in (seat/assign (flr) [(confirmed-on "t2a" t19-jst 90)] t19-jst 90 2)
                         [:yotei/table :yotei/table-id])))))

;; ── the overlap answer is yoyaku's, not a second one ─────────────────────────

(deftest test-touching-seatings-do-not-overlap
  (let [confirmed [(confirmed-on "t4" t19-jst 90)]]
    (is (seat/seatable? (flr) confirmed (+ t19-jst 90) 90 4))))

(deftest test-overlapping-seating-blocks-that-table
  (let [confirmed [(confirmed-on "t4" t19-jst 90)]]
    (is (not-any? #(= "t4" (:yotei/table-id %))
                  (seat/free-tables (flr) confirmed (+ t19-jst 30) 90 4)))))

;; ── holds-table?: the confirm-time re-derivation ─────────────────────────────

(deftest test-holds-table-rejects-a-foreign-did
  (is (not (seat/holds-table? (flr) "did:web:somewhere:else#table:1" 2)))
  (is (seat/holds-table? (flr) (seat/table-did REST "t4") 4)))

(deftest test-holds-table-rejects-a-party-too-big-for-that-table
  (is (not (seat/holds-table? (flr) (seat/table-did REST "t2a") 4))))

;; ── G6: instants, never counts ───────────────────────────────────────────────

(deftest test-open-times-are-deduplicated-across-tables
  (let [now (- t19-jst (* 3 1440))
        times (seat/open-times (flr) t19-jst (+ t19-jst 90) [] now 2)]
    (is (= [t19-jst] times)
        "four tables seat a pair at 19:00; the caller learns one instant, not four")))

(deftest test-open-times-shrink-with-party-size-not-with-load
  (let [now (- t19-jst (* 3 1440))
        for-two (seat/open-times (flr) t19-jst (+ t19-jst 90) [] now 2)
        for-six (seat/open-times (flr) t19-jst (+ t19-jst 90) [] now 6)
        ;; every table that seats six is taken; the instant disappears entirely
        six-taken (seat/open-times (flr) t19-jst (+ t19-jst 90)
                                   [(confirmed-on "t6" t19-jst 90)] now 6)]
    (is (= [t19-jst] for-two))
    (is (= [t19-jst] for-six))
    (is (= [] six-taken))))

(deftest test-open-times-respect-published-hours
  (let [now (- t19-jst (* 3 1440))
        ;; 23:00 JST — the room closes at 22:00
        t23 (+ t19-jst (* 4 60))]
    (is (= [] (seat/open-times (flr) t23 (+ t23 90) [] now 2)))))

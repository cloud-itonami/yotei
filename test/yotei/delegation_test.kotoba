(ns yotei.delegation-test
  "受付委任 — the envelope that lets a telephone receptionist confirm a 予約.

  This suite is mostly refusals, and deliberately so. The envelope is a hole cut
  in G5, and a hole is only as good as the wall around it: every field that
  bounds it gets a test that violates exactly that field and nothing else, so a
  bound that stops being enforced fails here rather than being discovered by a
  party of nine arriving at a four-top.

  The two tests at the end are about the *shape* of the hole rather than its
  size — that a caller cannot reach a confirmation by handing the delegated door
  something truthy, and that the member door has not quietly widened."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.delegation :as delegation]
            [yotei.seat :as seat]
            [yotei.time :as t]
            [yotei.yoyaku :as yoyaku]))

(def REST "did:web:app.itonami.cloud:calendar:torikai")
(def DELEGATE "did:web:denwaban.etzhayyim.com#uketsuke-2026-08")

(def ^:private t19 (+ (* (t/days-from-civil 2026 8 20) 1440) (- (* 19 60) 540)))
(def ^:private now (- t19 (* 3 1440)))

(defn- auth* [& kvs]
  (delegation/authorization
   (merge {:yotei/delegate-did DELEGATE
           :yotei/restaurant-did REST
           :yotei/not-after-epoch-min (+ now (* 90 1440))
           :yotei/max-party-size 6
           :yotei/seating-min 90
           :yotei/notice-min 60
           :yotei/horizon-days 30
           :yotei/tz "Asia/Tokyo"
           :yotei/tables [[(seat/table-did REST "t2a") 2]
                          [(seat/table-did REST "t4") 4]
                          [(seat/table-did REST "t6") 6]]
           :yotei/windows [{:yotei/day :thursday :yotei/from "17:30" :yotei/to "22:00"}]}
          (apply hash-map kvs))))

(defn- yoyaku* [& kvs]
  (merge {"state" "proposed" "status" "proposed" "yoyakuId" "y-1"
          "calendarDid" (seat/table-did REST "t4")
          "requesterDid" "" "responderDid" REST
          "startEpochMin" t19 "durationMin" 90
          "consentRef" "consent:call-1" "contactRef" "env:1"}
         (apply hash-map kvs)))

(defn- ctx* [& kvs]
  (merge {:yotei/now-epoch-min now
          :yotei/confirmed []
          :yotei/party-size 4
          :yotei/offset-at (constantly 540)
          :yotei/owner-signature-verified? true
          :yotei/delegate-signature-verified? true}
         (apply hash-map kvs)))

(defn- reasons [auth y ctx]
  (set (:yotei/reasons (delegation/admit auth y ctx))))

;; ── the envelope must be complete to exist at all ────────────────────────────

(deftest test-authorization-requires-its-bounds
  (doseq [absent [:yotei/not-after-epoch-min :yotei/max-party-size :yotei/seating-min
                  :yotei/horizon-days :yotei/tables :yotei/windows :yotei/tz]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (auth* absent nil))
        (str "an envelope without " (name absent) " bounds nothing"))))

(deftest test-authorization-refuses-an-empty-table-list
  (is (thrown? #?(:clj Exception :cljs js/Error) (auth* :yotei/tables []))))

;; ── the signed text is the same text everywhere ──────────────────────────────

(deftest test-statement-is-order-independent
  (let [a (auth*)
        b (auth* :yotei/tables [[(seat/table-did REST "t6") 6]
                                [(seat/table-did REST "t2a") 2]
                                [(seat/table-did REST "t4") 4]])]
    (is (= (:yotei/statement a) (:yotei/statement b)))))

(deftest test-statement-changes-when-a-bound-changes
  (testing "an owner who signed for six cannot be said to have signed for eight"
    (is (not= (:yotei/statement (auth*))
              (:yotei/statement (auth* :yotei/max-party-size 8)))))
  (testing "nor for a table they do not have"
    (is (not= (:yotei/statement (auth*))
              (:yotei/statement (auth* :yotei/tables [[(seat/table-did REST "t8") 8]]))))))

;; ── happy path ───────────────────────────────────────────────────────────────

(deftest test-admits-a-yoyaku-inside-the-envelope
  (let [{:yotei/keys [admitted reasons table]} (delegation/admit (auth*) (yoyaku*) (ctx*))]
    (is admitted)
    (is (= [] reasons))
    (is (= (seat/table-did REST "t4") (:yotei/calendar-did table)))))

;; ── one test per bound, violating exactly that bound ─────────────────────────

(deftest test-refuses-an-unverified-owner-signature
  (is (contains? (reasons (auth*) (yoyaku*) (ctx* :yotei/owner-signature-verified? false))
                 :owner-signature-unverified)))

(deftest test-an-unevaluated-signature-is-not-a-verified-one
  (testing "nil means 'we could not check', which must not read as 'it was fine'"
    (is (contains? (reasons (auth*) (yoyaku*) (ctx* :yotei/owner-signature-verified? nil))
                   :owner-signature-unverified))
    (is (contains? (reasons (auth*) (yoyaku*) (ctx* :yotei/delegate-signature-verified? nil))
                   :delegate-signature-unverified))
    (testing "and neither does a truthy value that is not true"
      (is (contains? (reasons (auth*) (yoyaku*) (ctx* :yotei/owner-signature-verified? "ok"))
                     :owner-signature-unverified)))))

(deftest test-refuses-an-expired-envelope
  (is (contains? (reasons (auth* :yotei/not-after-epoch-min (- now 1)) (yoyaku*) (ctx*))
                 :authorization-expired)))

(deftest test-refuses-a-party-larger-than-authorized
  (is (contains? (reasons (auth*) (yoyaku* "calendarDid" (seat/table-did REST "t6")) (ctx* :yotei/party-size 7))
                 :party-exceeds-authorization)))

(deftest test-refuses-a-table-the-owner-did-not-sign-for
  (testing "server state cannot add a table: the room is the signed text"
    (is (contains? (reasons (auth*) (yoyaku* "calendarDid" (seat/table-did REST "t12")) (ctx*))
                   :table-not-in-authorization))))

(deftest test-refuses-a-party-too-big-for-the-table-it-asked-for
  (is (contains? (reasons (auth*) (yoyaku* "calendarDid" (seat/table-did REST "t2a")) (ctx* :yotei/party-size 4))
                 :table-cannot-seat-party)))

(deftest test-refuses-a-duration-that-is-not-the-seating-time
  (is (contains? (reasons (auth*) (yoyaku* "durationMin" 240) (ctx*))
                 :duration-not-authorized-seating-time)))

(deftest test-refuses-the-past
  (is (contains? (reasons (auth*) (yoyaku* "startEpochMin" (- now 60)) (ctx*))
                 :in-the-past)))

(deftest test-refuses-beyond-the-horizon
  (is (contains? (reasons (auth*) (yoyaku* "startEpochMin" (+ now (* 400 1440))) (ctx*))
                 :beyond-horizon)))

(deftest test-refuses-outside-published-hours
  (let [t23 (+ t19 (* 4 60))]                       ; 23:00 JST, the room closes at 22:00
    (is (contains? (reasons (auth*) (yoyaku* "startEpochMin" t23) (ctx*))
                   :outside-published-hours))))

(deftest test-refuses-a-taken-table
  (let [taken [{"status" "confirmed" "calendarDid" (seat/table-did REST "t4")
                "startEpochMin" t19 "durationMin" 90}]
        rs (reasons (auth*) (yoyaku*) (ctx* :yotei/confirmed taken))]
    (is (contains? rs :slot-taken))
    (testing "and does not also claim the shop is closed, which would send the caller away"
      (is (not (contains? rs :outside-published-hours))))))

(deftest test-refuses-without-consent
  (is (contains? (reasons (auth*) (yoyaku* "consentRef" "") (ctx*)) :missing-consent)))

(deftest test-refuses-a-yoyaku-that-is-not-proposed
  (is (contains? (reasons (auth*) (yoyaku* "state" "confirmed") (ctx*)) :not-proposed)))

(deftest test-reports-every-reason-not-just-the-first
  (let [rs (reasons (auth*) (yoyaku* "durationMin" 240 "startEpochMin" (- now 60)) (ctx*))]
    (is (contains? rs :duration-not-authorized-seating-time))
    (is (contains? rs :in-the-past))))

;; ── the zone, and why an offset could not have been signed ───────────────────
;;
;; Japan has no DST, so a signed `tz=540` was correct forever and the bug was
;; invisible. These run the same restaurant in Paris, where the same wall clock
;; is two different instants depending on the month.

(def ^:private paris-summer-day (t/days-from-civil 2026 8 20))     ; Thursday, UTC+2
(def ^:private paris-winter-day (t/days-from-civil 2026 12 17))    ; Thursday, UTC+1
(def ^:private dst-ends (* (t/days-from-civil 2026 10 25) 1440))

(defn- paris-offset-at [_zone epoch-min] (if (< epoch-min dst-ends) 120 60))

(defn- paris-19h [day offset] (- (+ (* day 1440) (* 19 60)) offset))

(defn- paris-auth []
  (auth* :yotei/tz "Europe/Paris"
         :yotei/not-after-epoch-min (+ (* paris-winter-day 1440) (* 60 1440))))

(deftest test-the-same-wall-clock-is-admitted-in-both-halves-of-the-year
  (doseq [[label day offset] [["summer" paris-summer-day 120]
                              ["winter" paris-winter-day 60]]]
    (let [start (paris-19h day offset)
          out (delegation/admit (paris-auth)
                                (yoyaku* "startEpochMin" start)
                                (ctx* :yotei/now-epoch-min (- start (* 2 1440))
                                      :yotei/offset-at paris-offset-at))]
      (is (:yotei/admitted out) (str label ": 19:00 local is inside 17:30–22:00"))
      (is (= offset (:yotei/resolved-offset-min out))
          (str label ": the offset actually used is recorded for audit")))))

(deftest test-a-fixed-offset-is-wrong-in-both-directions
  ;; What a signed `tz=120` would have meant once October arrived. An hour's
  ;; error does not make bookings "roughly right": every instant lands off the
  ;; seating grid (17:30 / 19:00 / 20:30), so the real seating is refused and the
  ;; instant an hour away from it is confirmed in its place.
  (let [wrong (constantly 120)
        admit-at (fn [start resolver]
                   (delegation/admit (paris-auth)
                                     (yoyaku* "startEpochMin" start)
                                     (ctx* :yotei/now-epoch-min (- start (* 2 1440))
                                           :yotei/offset-at resolver)))
        real-1900 (paris-19h paris-winter-day 60)
        real-1800 (- real-1900 60)]
    (testing "the seating the caller actually wants is refused"
      (let [out (admit-at real-1900 wrong)]
        (is (not (:yotei/admitted out)))
        (is (some #{:outside-published-hours} (:yotei/reasons out)))))
    (testing "and an instant that is NOT a seating is confirmed instead"
      (is (:yotei/admitted (admit-at real-1800 wrong)))
      (testing "which the correct resolver refuses, because 18:00 is not a seating"
        (is (not (:yotei/admitted (admit-at real-1800 paris-offset-at))))))))

(deftest test-an-unresolvable-zone-refuses-rather-than-assuming-utc
  (doseq [[label resolver] [["no resolver injected" nil]
                            ["resolver cannot answer" (constantly nil)]
                            ["resolver answers with nonsense" (constantly "+09:00")]]]
    (let [out (delegation/admit (auth*) (yoyaku*) (ctx* :yotei/offset-at resolver))]
      (is (not (:yotei/admitted out)) label)
      (is (some #{:timezone-unresolved} (:yotei/reasons out)) label)
      (testing "and does not ALSO claim the shop is closed — that would be a UTC room"
        (is (not (some #{:outside-published-hours} (:yotei/reasons out))) label)))))

;; ── confirm ──────────────────────────────────────────────────────────────────

(def ^:private delegate-sig {"origin" "delegate" "ref" "sig:delegate:1"})

(deftest test-confirm-inside-the-envelope
  (let [out (delegation/confirm (auth*) (yoyaku*) delegate-sig (ctx*))]
    (is (= "confirmed" (get out "state")))
    (is (= "delegate" (get out "confirmedVia")))
    (is (= (:yotei/statement (auth*)) (get out "authorizedBy"))
        "the 予約 carries the sentence that let it through")))

(deftest test-confirm-outside-the-envelope-refuses-and-says-why
  (let [out (delegation/confirm (auth*) (yoyaku* "durationMin" 240) delegate-sig (ctx*))]
    (is (get out "refused"))
    (is (not= "confirmed" (get out "state")))
    (is (some #{"duration-not-authorized-seating-time"} (get out "reasons")))))

;; ── the shape of the hole ────────────────────────────────────────────────────

(deftest test-delegated-door-refuses-anything-but-a-literal-true
  (testing "a caller that could not evaluate the envelope must not get in by passing what it has"
    (doseq [truthy ["true" 1 :yes {} [:admitted]]]
      (is (get (yoyaku/confirm-yoyaku-delegated (yoyaku*) delegate-sig [] truthy "stmt") "refused")
          (str "admitted? = " (pr-str truthy) " must refuse")))))

(deftest test-delegated-door-refuses-a-member-signature
  (is (get (yoyaku/confirm-yoyaku-delegated (yoyaku*) {"origin" "member" "ref" "s"} [] true "stmt")
           "refused")))

(deftest test-delegated-door-refuses-to-confirm-without-recording-the-envelope
  (testing "an unattended 予約 with no signed sentence behind it is untraceable, so it does not happen"
    (is (get (yoyaku/confirm-yoyaku-delegated (yoyaku*) delegate-sig [] true "") "refused"))))

(deftest test-member-door-is-unchanged
  (testing "G5's original door still refuses a delegate signature"
    (is (get (yoyaku/confirm-yoyaku (yoyaku*) delegate-sig []) "refused")))
  (testing "and still admits a member one"
    (is (= "confirmed" (get (yoyaku/confirm-yoyaku (yoyaku*) {"origin" "member" "ref" "s"} [])
                            "state")))))

(deftest test-g4-is-rechecked-at-the-delegated-door
  (testing "the envelope says what may be sold, never that this table is still free"
    (let [taken [{"status" "confirmed" "calendarDid" (seat/table-did REST "t4")
                  "startEpochMin" t19 "durationMin" 90}]]
      (is (get (yoyaku/confirm-yoyaku-delegated (yoyaku*) delegate-sig taken true "stmt")
               "refused")))))

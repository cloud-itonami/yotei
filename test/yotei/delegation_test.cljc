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
           :yotei/tz-offset-min 540
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
          :yotei/owner-signature-verified? true
          :yotei/delegate-signature-verified? true}
         (apply hash-map kvs)))

(defn- reasons [auth y ctx]
  (set (:yotei/reasons (delegation/admit auth y ctx))))

;; ── the envelope must be complete to exist at all ────────────────────────────

(deftest test-authorization-requires-its-bounds
  (doseq [absent [:yotei/not-after-epoch-min :yotei/max-party-size :yotei/seating-min
                  :yotei/horizon-days :yotei/tables :yotei/windows]]
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

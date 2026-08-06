(ns yotei.yoyaku-test
  "yotei — scheduling commons tests. Verifies the structural
  invariants of ADR-2606072200: G4 no-double-book (overlap refused at propose AND re-checked at
  confirm), G5 no-server-key (only a member signature confirms), G8 consent-bound, G2 no-harvest
  (booker contact only as an encrypted ref), and honest slot generation (booked slots absent)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [yotei.yoyaku :as yoyaku]))

(def CAL "did:web:yotei.etzhayyim.com:calendar:alice")

(defn- confirmed* [start dur]
  {"status" "confirmed" "calendarDid" CAL "startEpochMin" start "durationMin" dur})

(defn- req* [start & kvs]
  (merge {"yoyakuId" "bk1" "calendarDid" CAL "requesterDid" "did:plc:bob"
          "responderDid" "did:plc:alice" "startEpochMin" start "durationMin" 30
          "consentRef" "consent-1"}
         (apply hash-map kvs)))

;; ── overlap ──
(deftest test-overlap-true
  (is (#'yoyaku/overlaps? 100 30 110 30)))

(deftest test-touching-not-overlap
  (is (not (#'yoyaku/overlaps? 100 30 130 30))))   ; [100,130) and [130,160)

(deftest test-is-free-respects-only-confirmed
  (let [proposed {"status" "proposed" "calendarDid" CAL "startEpochMin" 100 "durationMin" 30}]
    (is (yoyaku/is-free? CAL 100 30 [proposed]))     ; proposed doesn't block
    (is (not (yoyaku/is-free? CAL 100 30 [(confirmed* 100 30)])))))

;; ── propose ──
(deftest test-consent-required
  (let [out (yoyaku/propose-yoyaku (req* 600 "consentRef" "") [])]
    (is (= "refused" (get out "state")))
    (is (str/includes? (get out "reason") "G8"))))

(deftest test-free-slot-proposed
  (let [out (yoyaku/propose-yoyaku (req* 600) [])]
    (is (= "proposed" (get out "state")))
    (is (nil? (get out "confirmedSig")))))

(deftest test-double-book-refused
  (let [out (yoyaku/propose-yoyaku (req* 600) [(confirmed* 600 30)])]
    (is (= "refused" (get out "state")))
    (is (str/includes? (get out "reason") "G4"))))

(deftest test-contact-is-ref-only
  (let [out (yoyaku/propose-yoyaku (req* 600 "contactRef" "com.etzhayyim.encrypted:abcd") [])]
    ;; no plaintext profile/email/phone field exists
    (is (every? (fn [k] (let [kl (str/lower-case k)]
                          (and (not (str/includes? kl "email"))
                               (not (str/includes? kl "phone"))
                               (not (str/includes? kl "profile")))))
                (keys out)))
    (is (str/starts-with? (get out "contactRef") "com.etzhayyim.encrypted:"))))

;; ── confirm ──
(deftest test-member-signature-confirms
  (let [proposed (yoyaku/propose-yoyaku (req* 600) [])
        out (yoyaku/confirm-yoyaku proposed {"origin" "member" "ref" "sig-1"} [])]
    (is (= "confirmed" (get out "status")))
    (is (= "sig-1" (get out "confirmedSig")))))

(deftest test-server-signature-refused
  (let [proposed (yoyaku/propose-yoyaku (req* 600) [])
        out (yoyaku/confirm-yoyaku proposed {"origin" "server" "ref" "x"} [])]
    (is (get out "refused"))
    (is (str/includes? (get out "reason") "G5"))))

(deftest test-race-lost-refused
  (let [proposed (yoyaku/propose-yoyaku (req* 600) [])
        out (yoyaku/confirm-yoyaku proposed {"origin" "member" "ref" "s"} [(confirmed* 600 30)])]
    (is (get out "refused"))
    (is (str/includes? (get out "reason") "G4"))))

;; Slot generation moved to `yotei.availability` (see availability_test) — it
;; needs the timezone, notice and horizon this namespace deliberately does not
;; know about. `is-free?` above stayed, so both still share one overlap rule.

;; ── cancel / reschedule ──
(defn- a-confirmed []
  (yoyaku/confirm-yoyaku (yoyaku/propose-yoyaku (req* 600) []) {"origin" "member" "ref" "s1"} []))

(deftest test-cancel-frees-slot
  (let [cancelled (yoyaku/cancel-yoyaku (a-confirmed))]
    (is (= "cancelled" (get cancelled "status")))
    (is (yoyaku/is-free? CAL 600 30 [cancelled]))))

(deftest test-reschedule-to-free-slot
  (let [c (a-confirmed)
        out (yoyaku/reschedule-yoyaku c 720 30 [c] {"origin" "member" "ref" "s2"})]
    (is (get out "rescheduled"))
    (is (= 720 (get out "startEpochMin")))))

(deftest test-reschedule-excludes-own-slot
  (let [c (a-confirmed)
        out (yoyaku/reschedule-yoyaku c 605 30 [c] {"origin" "member" "ref" "s2"})]
    (is (get out "rescheduled"))))

(deftest test-reschedule-into-conflict-refused
  (let [c (a-confirmed)
        other {"yoyakuId" "bk2" "status" "confirmed" "calendarDid" CAL
               "startEpochMin" 720 "durationMin" 30}
        out (yoyaku/reschedule-yoyaku c 720 30 [c other] {"origin" "member" "ref" "s2"})]
    (is (get out "refused"))
    (is (str/includes? (get out "reason") "G4"))))

(deftest test-reschedule-server-sig-refused
  (let [c (a-confirmed)
        out (yoyaku/reschedule-yoyaku c 720 30 [c] {"origin" "server" "ref" "x"})]
    (is (get out "refused"))
    (is (str/includes? (get out "reason") "G5"))))

(deftest test-reschedule-nonconfirmed-refused
  (let [proposed (yoyaku/propose-yoyaku (req* 900) [])
        out (yoyaku/reschedule-yoyaku proposed 960 30 [] {"origin" "member" "ref" "s"})]
    (is (get out "refused"))))

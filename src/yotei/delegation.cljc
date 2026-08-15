(ns yotei.delegation
  "受付委任 — the envelope an owner signs once, inside which a receptionist may
  confirm a 予約 without waking them up.

  ## The problem this exists for

  `yotei.yoyaku/confirm-yoyaku` refuses anything but a member-origin signature,
  and that is the reason a compromised yotei cannot manufacture a 予約. It also
  means somebody has to be holding a private key at the moment a 予約 is
  confirmed. For a Calendly-inverse calendar that is exactly right: the owner
  reads the request and signs it.

  A restaurant taking a telephone call cannot work that way. The caller is on
  the line now, and the answer 'we will let you know' is not an answer — it is
  the same 'no' the shop was trying to stop giving. Every plausible way to close
  that gap by *weakening* G5 ends somewhere worse: a server key that can confirm
  anything, or a 予約 that is confirmed by nobody.

  ## What is signed instead

  The owner signs, in advance, a complete description of what may be sold: the
  tables and their seats, the opening hours, the seating time, the largest
  party, how far ahead, and when the permission expires. That text is the
  envelope. A receptionist key may confirm a 予約 **only if every field of that
  予約 is re-derived here to fall inside it**.

  Three properties follow, and they are the reason this is not just a weaker G5:

  - **The envelope is the whole room.** Tables and hours come out of the
    owner-signed text, never out of server state. A compromised yotei cannot
    add a table, widen the hours or extend the seating time, because changing
    any of them changes the text the owner signed.
  - **Re-derivation, not attestation.** The receptionist's claim that a party of
    four fits is never read. `yotei.seat` recomputes it from the envelope's own
    table list, and `yotei.yoyaku/is-free?` recomputes the overlap.
  - **It expires, and it is bounded.** `notAfter` is mandatory. An envelope with
    no end is a server key with extra steps.

  What an owner gives up is real and should be said plainly: inside that
  envelope, 予約 are confirmed without them. That is the trade they are making
  when they let a machine answer the phone, and the envelope is where they say
  how far it goes.

  ## Signature verification is injected

  Nothing here verifies a signature. This namespace is `.cljc` and pure so a
  governor can recompute it anywhere; the ECDSA lives in `yotei.envelope`
  (WebCrypto) at the edge. Verification arrives as two booleans, and **anything
  other than `true` is a refusal** — a nil verification result is 'we could not
  check', which must never read the same as 'we checked and it was fine'."
  (:require [clojure.string :as str]
            [yotei.availability :as availability]
            [yotei.seat :as seat]
            [yotei.yoyaku :as yoyaku]))

(def statement-prefix "yotei/uketsuke/v1")

(defn- table-line [[table-did seats]]
  (str "table=" table-did ":" seats))

(defn- window-line [w]
  (str "window=" (name (:yotei/day w)) ":" (:yotei/from w) "-" (:yotei/to w)))

(defn statement
  "The exact text the owner signs.

  Human-readable on purpose. An owner is consenting to unattended confirmation;
  a consent they cannot read is a consent in name only. Field order is fixed and
  the repeated lines are sorted, so the same envelope canonicalises identically
  on every runtime that recomputes it."
  [{:yotei/keys [delegate-did restaurant-did not-after-epoch-min max-party-size
                 seating-min notice-min horizon-days tz-offset-min tables windows
                 closed-dates]}]
  (str/join
   "\n"
   (concat [statement-prefix
            (str "delegate=" delegate-did)
            (str "restaurant=" restaurant-did)
            (str "notAfter=" not-after-epoch-min)
            (str "maxParty=" max-party-size)
            (str "seatingMin=" seating-min)
            (str "noticeMin=" notice-min)
            (str "horizonDays=" horizon-days)
            (str "tz=" tz-offset-min)]
           (->> tables (map table-line) sort)
           (->> windows (map window-line) sort)
           (->> closed-dates (map #(str "closed=" %)) sort))))

(def ^:private required-fields
  [:yotei/delegate-did :yotei/restaurant-did :yotei/not-after-epoch-min
   :yotei/max-party-size :yotei/seating-min :yotei/horizon-days :yotei/tables
   :yotei/windows])

(defn authorization
  "Build the envelope. Refuses an incomplete one rather than defaulting it.

  Defaulting a missing `notAfter` to 'never', or a missing `maxParty` to 'any',
  is how an envelope stops bounding anything; the fields that bound it are
  exactly the fields with no safe default, so they are required."
  [attrs]
  (let [auth (merge {:yotei/notice-min 60 :yotei/tz-offset-min 0 :yotei/closed-dates #{}}
                    attrs)
        missing (remove #(some? (get auth %)) required-fields)]
    (when (seq missing)
      (throw (ex-info (str "受付委任に必須の項目がありません: " (str/join ", " (map name missing)))
                      {:type :yotei/incomplete-authorization :missing (vec missing)})))
    (when (empty? (:yotei/tables auth))
      (throw (ex-info "卓が 1 つも書かれていない受付委任は、何も認可しません。"
                      {:type :yotei/incomplete-authorization :missing [:yotei/tables]})))
    (assoc auth :yotei/statement (statement auth))))

(defn authorized-floor
  "The room, built from the owner-signed text alone.

  Deliberately not a lookup: if this read a floor from storage, a compromised
  yotei could add a table the owner never signed for and confirm a 予約 on it."
  [auth]
  (seat/floor (:yotei/restaurant-did auth)
              (mapv (fn [[table-did seats]]
                      {:yotei/table-id table-did
                       :yotei/calendar-did table-did
                       :yotei/seats seats})
                    (:yotei/tables auth))
              {:yotei/slot-min (:yotei/seating-min auth)
               :yotei/tz-offset-min (:yotei/tz-offset-min auth)
               :yotei/notice-min (:yotei/notice-min auth)
               :yotei/horizon-days (:yotei/horizon-days auth)
               :yotei/windows (vec (:yotei/windows auth))
               :yotei/closed-dates (set (:yotei/closed-dates auth))}))

(defn admit
  "Decide whether this 予約 falls inside this envelope. Reports **every** reason
  it does not.

  One reason would be enough to refuse, but not enough to answer the person on
  the telephone: 'we are closed then' and 'that table is taken' send them to
  different next questions, and a caller told only the first will keep offering
  times that were never the problem.

  `ctx`:
    :yotei/now-epoch-min                  — no clock is read here
    :yotei/confirmed                      — the wire-shaped confirmed 予約
    :yotei/party-size                     — how many people
    :yotei/owner-signature-verified?      — envelope signature checked out
    :yotei/delegate-signature-verified?   — this 予約's signature checked out"
  [auth yoyaku {:yotei/keys [now-epoch-min confirmed party-size
                             owner-signature-verified? delegate-signature-verified?]}]
  (let [flr (authorized-floor auth)
        table-cal-did (get yoyaku "calendarDid")
        start (long (get yoyaku "startEpochMin" 0))
        duration (long (get yoyaku "durationMin" 0))
        tbl (first (filter #(= table-cal-did (:yotei/calendar-did %)) (:yotei/tables flr)))
        reasons
        (cond-> []
          (not (true? owner-signature-verified?))
          (conj :owner-signature-unverified)

          (not (true? delegate-signature-verified?))
          (conj :delegate-signature-unverified)

          (not= "proposed" (get yoyaku "state"))
          (conj :not-proposed)

          (not (seq (get yoyaku "consentRef")))
          (conj :missing-consent)

          (not (and (integer? party-size) (pos? party-size)))
          (conj :party-size-unknown)

          (and (integer? party-size) (pos? party-size)
               (> party-size (:yotei/max-party-size auth)))
          (conj :party-exceeds-authorization)

          (nil? tbl)
          (conj :table-not-in-authorization)

          (and (some? tbl) (integer? party-size) (pos? party-size)
               (not (seat/fits? tbl party-size)))
          (conj :table-cannot-seat-party)

          (not= duration (:yotei/seating-min auth))
          (conj :duration-not-authorized-seating-time)

          (< start (long now-epoch-min))
          (conj :in-the-past)

          (> start (+ (long now-epoch-min) (* 1440 (long (:yotei/horizon-days auth)))))
          (conj :beyond-horizon)

          (> (long now-epoch-min) (long (:yotei/not-after-epoch-min auth)))
          (conj :authorization-expired)

          ;; Hours, notice and horizon only -- `confirmed` is deliberately empty
          ;; here so a taken table reports `:slot-taken` and not also 'we are
          ;; closed then', which would be false and would send the caller away.
          (and (some? tbl)
               (not (availability/open? (seat/table-calendar flr tbl)
                                        start duration [] now-epoch-min)))
          (conj :outside-published-hours)

          (and (some? tbl)
               (not (yoyaku/is-free? table-cal-did start duration confirmed)))
          (conj :slot-taken))]
    {:yotei/admitted (empty? reasons)
     :yotei/reasons reasons
     :yotei/table (when (empty? reasons) tbl)
     :yotei/statement (:yotei/statement auth)}))

(defn confirm
  "Confirm a 予約 under an admitted envelope.

  Thin on purpose: the decision is `admit`'s and the state transition is
  `yotei.yoyaku`'s. This exists so that no caller can reach
  `confirm-yoyaku-delegated` with a hand-built `true`."
  [auth yoyaku signature ctx]
  (let [{:yotei/keys [admitted reasons]} (admit auth yoyaku ctx)]
    (if admitted
      (yoyaku/confirm-yoyaku-delegated yoyaku signature (:yotei/confirmed ctx)
                                       true (:yotei/statement auth))
      {"state" (get yoyaku "state")
       "yoyakuId" (get yoyaku "yoyakuId")
       "refused" true
       "reason" "受付委任の範囲外です (yotei.delegation/admit)"
       "reasons" (mapv name reasons)})))

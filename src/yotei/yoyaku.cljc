(ns yotei.yoyaku
  "予約 — the lifecycle of one reserved slot, and the invariant that keeps two
  people from reserving the same one.

  Append-only over a kotoba EAVT graph, with the manifest's structural gates
  carried as code rather than as documentation:

  - **G4 no-double-book** — an overlapping slot is refused at propose *and*
    re-checked at confirm, so a racing confirm cannot open a hole between the
    two.
  - **G5 no-server-key** — only a member-origin signature confirms. yotei holds
    no key, so a compromised server cannot manufacture a 予約.
  - **G2 no-harvest** — the person reserving is carried as an encrypted
    envelope ref, never as a profile.
  - **G3 append-only** — a status transition appends; nothing is overwritten.

  Maps are string-keyed because this is the EAVT wire shape, not a Clojure
  value. `yotei.availability` speaks keywords and converts at its own boundary.

  Slot *generation* deliberately does not live here. It used to
  (`generate-slots`, single-day, tz-naive), and `yotei.availability/openings`
  now does it with the timezone, notice and horizon a real 予約 page needs.
  Two functions that both decide which slots exist is exactly how an opening
  gets offered that `propose` then refuses, so there is one — and it calls
  `is-free?` below, so the offer and the acceptance share this invariant.")

;; ── interval overlap (the core of no-double-book, G4) ─────────────────────────
(defn- overlaps?
  "Half-open interval overlap [start, start+dur). Touching ends do NOT overlap."
  [a-start a-dur b-start b-dur]
  (and (< a-start (+ b-start b-dur)) (< b-start (+ a-start a-dur))))

(defn is-free?
  "True iff the proposed slot overlaps no CONFIRMED 予約 on this calendar (G4).

  Only `confirmed` counts. A proposed 予約 does not hold the slot — otherwise
  anyone could freeze a calendar by proposing and never confirming — and a
  cancelled one releases it immediately."
  [calendar-did start-epoch-min duration-min confirmed]
  (not (some (fn [b]
               (and (= (get b "status") "confirmed")
                    (= (get b "calendarDid") calendar-did)
                    (overlaps? start-epoch-min duration-min
                               (long (get b "startEpochMin" 0)) (long (get b "durationMin" 0)))))
             confirmed)))

;; ── propose / confirm ────────────────────────────────────────────────────────
(defn propose-yoyaku
  "Propose a 予約. Requires consent (G8); refuses if the slot overlaps a
  confirmed one (G4). Contact is carried only as an encrypted envelope ref
  (G2). Returns a `\"proposed\"` 予約 (unsigned) or a refusal."
  [req confirmed]
  (cond
    (not (seq (get req "consentRef")))
    {"state" "refused" "reason" "missing DID-signed consent (G8)"}
    (not (is-free? (get req "calendarDid") (long (get req "startEpochMin"))
                   (long (get req "durationMin")) confirmed))
    {"state" "refused" "reason" "slot overlaps a confirmed 予約 (G4 no-double-book)"}
    :else
    {"state" "proposed"
     "yoyakuId" (get req "yoyakuId")
     "calendarDid" (get req "calendarDid")
     "requesterDid" (get req "requesterDid")
     "responderDid" (get req "responderDid" "")
     "startEpochMin" (long (get req "startEpochMin"))
     "durationMin" (long (get req "durationMin"))
     "consentRef" (get req "consentRef")
     "contactRef" (get req "contactRef" "")           ; encrypted envelope only (G2)
     "status" "proposed"
     "confirmedSig" nil
     "appendOnly" true}))

(defn confirm-yoyaku
  "Confirm a proposed 予約. Re-checks no-double-book at confirm time so a racing
  confirm cannot create an overlap (G4). ONLY a member-origin signature
  confirms (G5 no-server-key); a server signature is refused. Append-only (G3)."
  [yoyaku signature confirmed]
  (cond
    (not= (get yoyaku "state") "proposed")
    (merge yoyaku {"refused" true "reason" "予約 is not in :proposed state"})
    (not= (get signature "origin") "member")
    (merge yoyaku {"refused" true
                   "reason" "only a member passkey/wallet signature confirms (G5 no-server-key)"})
    (not (is-free? (get yoyaku "calendarDid") (long (get yoyaku "startEpochMin"))
                   (long (get yoyaku "durationMin")) confirmed))
    (merge yoyaku {"refused" true "reason" "slot was taken before confirm — overlap refused (G4)"})
    :else
    (merge yoyaku {"state" "confirmed" "status" "confirmed" "confirmedSig" (get signature "ref")})))

;; ── cancel / reschedule ──────────────────────────────────────────────────────
(defn cancel-yoyaku
  "Cancel a 予約. A cancelled 予約 no longer blocks availability (`is-free?`
  counts only confirmed), so the slot is immediately re-takeable. Append-only
  state transition (G3)."
  [yoyaku]
  (merge yoyaku {"state" "cancelled" "status" "cancelled"}))

(defn reschedule-yoyaku
  "Move a confirmed 予約 to a new slot. Member-signed (G5). The new slot is
  re-checked for no-double-book (G4), EXCLUDING this 予約's own current slot —
  without that exclusion a 予約 could never be moved by less than its own
  duration, because it would collide with itself. Refuses a non-confirmed 予約
  or an occupied slot."
  [yoyaku new-start-epoch-min new-duration-min confirmed signature]
  (cond
    (not= (get yoyaku "status") "confirmed")
    (merge yoyaku {"refused" true "reason" "only a confirmed 予約 can be rescheduled"})
    (not= (get signature "origin") "member")
    (merge yoyaku {"refused" true
                   "reason" "only a member passkey/wallet signature reschedules (G5 no-server-key)"})
    :else
    (let [others (filterv #(not= (get % "yoyakuId") (get yoyaku "yoyakuId")) confirmed)]
      (if (not (is-free? (get yoyaku "calendarDid") (long new-start-epoch-min)
                         (long new-duration-min) others))
        (merge yoyaku {"refused" true
                       "reason" "target slot overlaps another confirmed 予約 (G4 no-double-book)"})
        (merge yoyaku {"startEpochMin" (long new-start-epoch-min)
                       "durationMin" (long new-duration-min)
                       "status" "confirmed" "rescheduled" true
                       "confirmedSig" (get signature "ref")})))))

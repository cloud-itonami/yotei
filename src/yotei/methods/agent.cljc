(ns yotei.methods.agent
  "yotei — kotoba-native scheduling commons. Append-only bookings over a
  kotoba EAVT graph with structural invariants: no-double-book (G4 — an overlapping slot is refused
  at propose AND re-checked at confirm), no-server-key (G5 — only a member signature confirms),
  no-harvest (G2 — booker contact is only an encrypted envelope ref), append-only (G3 — status
  transitions append, never overwrite). The optional `from kotoba import datalog, llm` host binding
  is unused by these pure functions and is the omitted leg.")

;; ── interval overlap (the core of no-double-book, G4) ─────────────────────────
(defn- overlaps?
  "Half-open interval overlap [start, start+dur). Touching ends do NOT overlap."
  [a-start a-dur b-start b-dur]
  (and (< a-start (+ b-start b-dur)) (< b-start (+ a-start a-dur))))

(defn is-free?
  "True iff the proposed slot overlaps no CONFIRMED booking on this calendar (G4)."
  [calendar-did start-epoch-min duration-min confirmed-bookings]
  (not (some (fn [b]
               (and (= (get b "status") "confirmed")
                    (= (get b "calendarDid") calendar-did)
                    (overlaps? start-epoch-min duration-min
                               (long (get b "startEpochMin" 0)) (long (get b "durationMin" 0)))))
             confirmed-bookings)))

;; ── slot generation ──────────────────────────────────────────────────────────
(defn generate-slots
  "Enumerate free slots within an availability window for a given day (the day's midnight as epoch
  minutes). Honest availability only (G6): booked slots are simply absent — no count, no 'almost
  gone'. Returns a list of {startEpochMin, durationMin}."
  [availability day-start-epoch-min confirmed-bookings]
  (let [start (+ day-start-epoch-min (long (get availability "startMin")))
        end (+ day-start-epoch-min (long (get availability "endMin")))
        step (long (get availability "slotMin" 30))
        cal (get availability "calendarDid")]
    (loop [t start acc []]
      (if (<= (+ t step) end)
        (recur (+ t step)
               (if (is-free? cal t step confirmed-bookings)
                 (conj acc {"startEpochMin" t "durationMin" step})
                 acc))
        acc))))

;; ── propose / confirm ────────────────────────────────────────────────────────
(defn propose-booking
  "Propose a booking. Requires consent (G8); refuses if the slot overlaps a confirmed booking (G4).
  Booker contact is carried only as an encrypted envelope ref (G2). Returns a :proposed booking
  (unsigned) or a refusal."
  [req confirmed-bookings]
  (cond
    (not (seq (get req "consentRef")))
    {"state" "refused" "reason" "missing DID-signed consent (G8)"}
    (not (is-free? (get req "calendarDid") (long (get req "startEpochMin"))
                   (long (get req "durationMin")) confirmed-bookings))
    {"state" "refused" "reason" "slot overlaps a confirmed booking (G4 no-double-book)"}
    :else
    {"state" "proposed"
     "bookingId" (get req "bookingId")
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

(defn confirm-booking
  "Confirm a proposed booking. Re-checks no-double-book at confirm time so a racing confirm cannot
  create an overlap (G4). ONLY a member-origin signature confirms (G5 no-server-key); a server
  signature is refused. Append-only (G3)."
  [booking signature confirmed-bookings]
  (cond
    (not= (get booking "state") "proposed")
    (merge booking {"refused" true "reason" "booking is not in :proposed state"})
    (not= (get signature "origin") "member")
    (merge booking {"refused" true
                    "reason" "only a member passkey/wallet signature confirms (G5 no-server-key)"})
    (not (is-free? (get booking "calendarDid") (long (get booking "startEpochMin"))
                   (long (get booking "durationMin")) confirmed-bookings))
    (merge booking {"refused" true "reason" "slot was taken before confirm — overlap refused (G4)"})
    :else
    (merge booking {"state" "confirmed" "status" "confirmed" "confirmedSig" (get signature "ref")})))

;; ── cancel / reschedule ──────────────────────────────────────────────────────
(defn cancel-booking
  "Cancel a booking. A cancelled booking no longer blocks availability (is-free? counts only
  :confirmed), so the slot is immediately re-bookable. Append-only state transition (G3)."
  [booking]
  (merge booking {"state" "cancelled" "status" "cancelled"}))

(defn reschedule-booking
  "Move a confirmed booking to a new slot. Member-signed (G5). The new slot is re-checked for
  no-double-book (G4), EXCLUDING this booking's own current slot. Refuses a non-confirmed booking
  or an occupied slot."
  [booking new-start-epoch-min new-duration-min confirmed-bookings signature]
  (cond
    (not= (get booking "status") "confirmed")
    (merge booking {"refused" true "reason" "only a confirmed booking can be rescheduled"})
    (not= (get signature "origin") "member")
    (merge booking {"refused" true
                    "reason" "only a member passkey/wallet signature reschedules (G5 no-server-key)"})
    :else
    (let [others (filterv #(not= (get % "bookingId") (get booking "bookingId")) confirmed-bookings)]
      (if (not (is-free? (get booking "calendarDid") (long new-start-epoch-min)
                         (long new-duration-min) others))
        (merge booking {"refused" true
                        "reason" "target slot overlaps another confirmed booking (G4 no-double-book)"})
        (merge booking {"startEpochMin" (long new-start-epoch-min)
                        "durationMin" (long new-duration-min)
                        "status" "confirmed" "rescheduled" true
                        "confirmedSig" (get signature "ref")})))))

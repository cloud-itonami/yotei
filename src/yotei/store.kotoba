(ns yotei.store
  "Where 予約 are kept, and the one shape every backend has to have.

  G3 says 予約 are append-only: a status transition appends a fact, it does not
  overwrite one. So the store is a **log per calendar**, not a mutable row —
  `append!` is the only writer, and current state is a fold over the log. That
  makes the interesting failure impossible rather than unlikely: nothing here
  can express 'set this 予約 to cancelled', so nothing can lose the fact that
  it was confirmed first.

  ## Reading is a fold, and that is a real cost

  `confirmed` replays the log every time it is asked. At a calendar's scale
  that is right — a busy calendar is hundreds of entries, not millions, and a
  fold cannot go stale the way a cached projection can. When it stops being
  right the fix is a snapshot keyed on the log's length, not a mutable column.

  ## Why the protocol has four methods and not a query language

  Everything above this namespace needs exactly: the calendar's settings, the
  confirmed 予約 that block slots, one 予約 by id, and a way to append. A
  general query interface would let a caller ask for something a KV backend
  cannot answer, and the answer would be 'load the whole log and filter' —
  which is the fold, written somewhere it cannot be seen.

  ## Compare-and-set is the backend's job, not the caller's

  `append!` takes the log length the caller read at, and returns nil if the log
  has grown since. Two strangers confirming the same slot is exactly the race
  G4 exists to prevent, and re-checking `is-free?` before writing is not enough
  on its own — both would check, both would pass, both would write. The version
  makes the second write fail so it can re-read and be refused properly."
  (:require [yotei.availability :as av]
            [yotei.yoyaku :as yoyaku]))

(defprotocol YoteiStore
  (-calendar [this calendar-did]
    "The calendar's settings map, or nil if there is no such calendar.")
  (-log [this calendar-did]
    "`[entries version]` — the append-only 予約 log and the version to pass
    back to `-append!`.")
  (-append! [this calendar-did entry version]
    "Append `entry` iff the log is still at `version`. Returns the new version,
    or nil if it moved (the caller must re-read and re-decide).")
  (-put-calendar! [this calendar-did cal]
    "Create or replace a calendar's settings. Not append-only: settings are
    configuration, and their history is the owner's git, not this log."))

(defn calendar [store did] (-calendar store did))

(defn history
  "Every 予約 fact for `did`, oldest first."
  [store did]
  (first (-log store did)))

(defn- fold-current
  "The latest fact per yoyakuId.

  Later facts win *per 予約*, which is what 'append-only' means here — the log
  keeps every transition, and the current view is the last one for each id."
  [entries]
  (->> entries
       (reduce (fn [acc e] (assoc acc (get e "yoyakuId") e)) {})
       vals
       vec))

(defn confirmed
  "The confirmed 予約 for `did` — the exact value `is-free?` and `openings`
  expect."
  [store did]
  (filterv #(= "confirmed" (get % "status")) (fold-current (history store did))))

(defn by-id [store did yoyaku-id]
  (first (filter #(= yoyaku-id (get % "yoyakuId")) (fold-current (history store did)))))

(defn openings
  "The times `did` is offering between two instants."
  [store did from-epoch-min to-epoch-min now-epoch-min]
  (when-let [cal (calendar store did)]
    (av/openings cal from-epoch-min to-epoch-min (confirmed store did) now-epoch-min)))

(defn current-confirmed
  "The confirmed 予約 in a log that has already been read.

  Split out because the Worker reads its log over a promise and cannot call
  `confirmed` above, and a second fold written there would be a second answer
  to 'which 予約 block a slot'."
  [entries]
  (filterv #(= "confirmed" (get % "status")) (fold-current entries)))

(defn decide-propose
  "Whether this proposal may be appended, given a log already read.

  **Pure**, and that is the point. The JVM store below and the Cloudflare
  Worker have different I/O — one is synchronous over an atom, the other is
  promises over KV — but they must not have different *rules*. Before this
  existed the Worker re-implemented the three checks inline, which put the
  decision in the one file with no tests over it.

  Three checks, and each catches something the others do not:

  1. `av/open?` — the slot is inside a published window, past the notice and
     inside the horizon. `propose-yoyaku` alone would accept 3am on a Sunday,
     because nothing had taken 3am on a Sunday.
  2. `propose-yoyaku` — consent (G8) and no-double-book (G4).
  3. the caller then appends at the version it read at, which catches a
     proposal that landed in between.

  Returns `{:action :append :entry e}` or `{:action :refuse :result r}`."
  [cal did entries req now-epoch-min]
  (let [current (current-confirmed entries)
        start (long (get req "startEpochMin"))
        dur (long (get req "durationMin"))]
    (if-not (av/open? cal start dur current now-epoch-min)
      {:action :refuse
       :result {"state" "refused" "reason" "その時間は空いていません。"}}
      (let [out (yoyaku/propose-yoyaku (assoc req "calendarDid" did) current)]
        (if (= "refused" (get out "state"))
          {:action :refuse :result out}
          {:action :append :entry out})))))

(defn decide-confirm
  "Whether this confirmation may be appended, given a log already read.

  Re-checks no-double-book at this moment rather than trusting the proposal:
  between proposing and confirming, somebody else's 予約 may have landed."
  [entries yoyaku-id signature]
  (let [folded (fold-current entries)
        target (first (filter #(= yoyaku-id (get % "yoyakuId")) folded))]
    (if (nil? target)
      {:action :refuse :result {"refused" true "reason" "その申し込みはありません。"}}
      (let [out (yoyaku/confirm-yoyaku target signature (current-confirmed entries))]
        (if (get out "refused")
          {:action :refuse :result out}
          {:action :append :entry out})))))

(defn propose!
  "Take a slot, if it is genuinely on offer and still free.

  The rules are `decide-propose`'s; this adds the read, the append and one
  retry. Retried once because the common case is a lost race against an
  unrelated 予約 on the same calendar; a second failure is returned rather
  than looped, since a caller stuck behind a busy calendar should be told,
  not silently delayed."
  [store did req now-epoch-min]
  (loop [attempt 0]
    (if-let [cal (calendar store did)]
      (let [[entries version] (-log store did)
            {:keys [action entry result]} (decide-propose cal did entries req now-epoch-min)]
        (if (= :refuse action)
          result
          (if (-append! store did entry version)
            entry
            (if (< attempt 1)
              (recur (inc attempt))
              {"state" "refused"
               "reason" "同時に別の申し込みがありました。もう一度お試しください。"}))))
      {"state" "refused" "reason" "そのカレンダーはありません。"})))

(defn confirm!
  "Turn a proposal into a 予約. Member-signed only (G5). Rules are
  `decide-confirm`'s; this adds the read, the append and one retry."
  [store did yoyaku-id signature]
  (loop [attempt 0]
    (let [[entries version] (-log store did)
          {:keys [action entry result]} (decide-confirm entries yoyaku-id signature)]
      (if (= :refuse action)
        result
        (if (-append! store did entry version)
          entry
          (if (< attempt 1)
            (recur (inc attempt))
            {"refused" true "reason" "書き込みが競合しました。もう一度お試しください。"}))))))

(defn cancel!
  "Cancel, releasing the slot immediately (`is-free?` counts only confirmed)."
  [store did yoyaku-id]
  (loop [attempt 0]
    (let [[entries version] (-log store did)
          target (first (filter #(= yoyaku-id (get % "yoyakuId")) (fold-current entries)))]
      (if (nil? target)
        {"refused" true "reason" "その予約はありません。"}
        (let [out (yoyaku/cancel-yoyaku target)]
          (if (-append! store did out version)
            out
            (if (< attempt 1)
              (recur (inc attempt))
              {"refused" true "reason" "書き込みが競合しました。"})))))))

;; ── in-memory backend ────────────────────────────────────────────────────────

(defn memory-store
  "An atom-backed store. Used by the tests and by `scripts/preview.clj`.

  The version is the log's count, which is exactly right for an append-only
  log: it changes if and only if somebody appended."
  ([] (memory-store {}))
  ([calendars]
   (let [state (atom {:calendars calendars :logs {}})]
     (reify YoteiStore
       (-calendar [_ did] (get-in @state [:calendars did]))
       (-log [_ did] (let [l (get-in @state [:logs did] [])] [l (count l)]))
       (-append! [_ did entry version]
         ;; `swap-vals!`, not `swap!` + a length check afterwards. Comparing
         ;; the resulting length to `(inc version)` cannot tell "I appended"
         ;; from "somebody else had already appended, so it is that length
         ;; anyway" — both look like success, which is exactly the lost race
         ;; this compare-and-set exists to fail. Only the before/after pair
         ;; says whether *this* call was the one that wrote.
         (let [[before after]
               (swap-vals! state
                           (fn [s]
                             (let [l (get-in s [:logs did] [])]
                               (if (= (count l) version)
                                 (assoc-in s [:logs did] (conj l entry))
                                 s))))]
           (when (not= (get-in before [:logs did] []) (get-in after [:logs did] []))
             (count (get-in after [:logs did] [])))))
       (-put-calendar! [_ did cal] (swap! state assoc-in [:calendars did] cal) cal)))))

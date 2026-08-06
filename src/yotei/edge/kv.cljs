(ns yotei.edge.kv
  "Workers KV as the append-only 予約 log.

  ## Why KV and not D1

  This is an application path, not a distributed-consensus one — CLAUDE.md's
  rule bans D1 as a *premise* under blockchain/ref/ledger paths, and yotei is
  neither. What it does need is a store whose failure mode is understood, and
  KV's is: eventually consistent reads, last-write-wins writes.

  Last-write-wins is fatal for a 予約 log, so the log is never written blind.
  Every write is gated on the version the caller read at (`YoteiStore`'s
  contract), and KV's `metadata` carries that version so the check does not
  cost a second read.

  ## The honest limitation — observed, not theorised

  **KV has no atomic compare-and-set.** The version check here narrows the race
  from 'always possible' to 'possible only inside one replication window', but
  it does not close it. Two writers that both observe the old version both
  write, and the second overwrites the first — G4 violated with nothing
  noticing.

  This is not a worry; it happened during the first live test, 2026-08-06. A
  confirmed 予約 was written directly to the key, and a `propose` arriving
  seconds later read a replica that still held the previous log, appended to
  *that*, and wrote it back. The confirmed 予約 was gone, and the slot it had
  taken was offered again. The symptom looked like a broken `is-free?`; the
  cause was a lost update, and only dumping the raw key showed it.

  Two consequences worth keeping:

  - The window is a replication window, not an instant. Reads went on serving
    the stale log for **over five minutes** in that test, so 'a second or two'
    is not the right mental model.
  - It is invisible from the outside. Nothing errored — a 予約 simply ceased
    to exist.

  That is not acceptable as the permanent answer, and it is not being sold as
  one. The fix is a Durable Object per calendar, which CLAUDE.md already
  prescribes for exactly this shape: 'DO は直列化器・realtime room として使い、
  ストレージは共有バックエンドに置く' — a DO is globally unique and
  single-threaded, so 'there is exactly one writer' comes for free instead of
  being implemented as a lease. The `YoteiStore` protocol is the seam that
  makes that swap a new backend rather than a rewrite.

  Until then this is honest about what it is: correct for one calendar owner
  with human-rate traffic, which is what a 予約 page is, and racy under a
  thundering herd, which a 予約 page should not have."
  (:require [clojure.edn :as edn]))

(defn- log-key [did] (str "yoyaku-log:" did))
(defn- cal-key [did] (str "calendar:" did))

(defn- read-log
  "`[entries version]` for `did`. A missing key is an empty log at version 0,
  not an error: a calendar with no 予約 yet is the normal first state."
  [kv did]
  (-> (.getWithMetadata ^js kv (log-key did) #js {:type "text"})
      (.then (fn [res]
               (let [value (some-> res .-value)
                     meta- (some-> res .-metadata)
                     entries (if value (edn/read-string value) [])
                     version (or (some-> meta- .-version) (count entries))]
                 [entries version])))))

(defn kv-store
  "A `YoteiStore` over a KV namespace.

  Every method returns a promise, so this namespace is **not** interchangeable
  with `memory-store` at the protocol level — the Worker awaits explicitly. The
  protocol is still what shapes it: the same four operations, the same version
  discipline, so the Durable Object backend that replaces it has a spec to meet."
  [kv]
  {:kv kv

   :calendar
   (fn [did]
     (-> (.get kv (cal-key did) #js {:type "text"})
         (.then (fn [s] (when s (edn/read-string s))))))

   :log (fn [did] (read-log kv did))

   :append!
   (fn [did entry version]
     (-> (read-log kv did)
         (.then (fn [[entries current]]
                  (if (not= current version)
                    ;; Somebody wrote between the caller's read and now. The
                    ;; caller re-reads and re-decides; it must not be retried
                    ;; here, because whether the 予約 is still legal is a
                    ;; question for `is-free?`, not for the store.
                    nil
                    (let [next- (conj entries entry)]
                      (-> (.put kv (log-key did) (pr-str next-)
                                #js {:metadata #js {:version (count next-)}})
                          (.then (fn [_] (count next-))))))))))

   :put-calendar!
   (fn [did cal]
     (-> (.put kv (cal-key did) (pr-str cal))
         (.then (fn [_] cal))))})

(defn confirmed
  "The confirmed 予約 for `did` — the same fold `yotei.store/confirmed` does,
  over a promise."
  [s did]
  (-> ((:log s) did)
      (.then (fn [[entries _]]
               (->> entries
                    (reduce (fn [acc e] (assoc acc (get e "yoyakuId") e)) {})
                    vals
                    (filterv #(= "confirmed" (get % "status"))))))))

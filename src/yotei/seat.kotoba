(ns yotei.seat
  "席 — a floor of tables, and which one can seat a party at a given instant.

  `yotei.yoyaku` answers *is this resource free*, exclusively: one confirmed
  予約 owns the interval on its calendar. That is the right answer for a
  person's calendar and the wrong shape for a restaurant, where nineteen
  o'clock is taken and available at the same time because there are twelve
  tables.

  **A table is a calendar.** This namespace adds no second notion of `free` —
  each table carries its own `calendarDid`, and `yoyaku/is-free?` decides the
  interval exactly as it does for a person. What is new here is only the
  question *which* table, which is a function of the party's size and the
  tables' seats.

  That choice is deliberate. A capacity counter kept beside the 予約 log would
  be a second thing that decides what is free, and `yotei.availability` already
  records why that is the bug that matters: an opening offered by one and
  refused by the other. Counting free tables by asking `is-free?` about each of
  them cannot drift from `is-free?`.

  ## Seating time is the slot

  A restaurant's 予約 is not `19:00` but `19:00 for ninety minutes`, and the
  next party sits when it ends. That is what `:yotei/slot-min` already means, so
  a table's calendar sets `slot-min` to the seating duration and
  `:yotei/windows` to the opening hours; notice and horizon keep their meaning.
  No new time model.

  ## What this deliberately does not do

  **Joining tables.** Two four-tops do not become a six-top here. Whether two
  particular tables can be pushed together is a fact about the room that no
  data in this namespace carries, and inferring it from seat counts would
  promise a party of six a table that does not exist.

  **Counting in public.** `open-times` returns instants, de-duplicated across
  tables. A caller cannot learn from it how many tables are free, which is G6:
  'two left at 19:00' is scarcity pressure, and it is also a daily attendance
  report on somebody's business."
  (:require [yotei.availability :as availability]
            [yotei.yoyaku :as yoyaku]))

(defn table-did
  "The calendar DID of one table. A DID fragment, because a table is a resource
  *within* the restaurant's identity rather than an identity of its own."
  [restaurant-did table-id]
  (str restaurant-did "#table:" table-id))

(defn table
  "One table: an id, how many it seats, and optional calendar overrides.

  `attrs` are `yotei.availability/calendar` attributes for this table alone —
  a terrace that closes at 21:00 while the room runs to 23:00. Absent, the
  floor's shared hours apply."
  [restaurant-did table-id seats & [attrs]]
  (when-not (and (integer? seats) (pos? seats))
    (throw (ex-info "席数は 1 以上の整数にしてください。"
                    {:type :yotei/bad-table :table-id table-id :seats seats})))
  (cond-> {:yotei/table-id table-id
           :yotei/calendar-did (table-did restaurant-did table-id)
           :yotei/seats seats}
    (seq attrs) (assoc :yotei/attrs attrs)))

(defn floor
  "A restaurant's tables plus the calendar attributes they share.

  Refuses duplicate table ids: two tables answering to one id would make
  `is-free?` speak for both, and the second party would arrive to a taken
  table with a confirmed 予約 in hand."
  [restaurant-did tables & [calendar-attrs]]
  (let [ids (map :yotei/table-id tables)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "卓 id が重複しています。"
                      {:type :yotei/duplicate-table
                       :duplicates (->> ids frequencies (filter #(< 1 (val %))) (map key) vec)})))
    (when (empty? tables)
      (throw (ex-info "卓が 1 つもありません。"
                      {:type :yotei/empty-floor :restaurant-did restaurant-did}))))
  {:yotei/restaurant-did restaurant-did
   :yotei/tables (vec tables)
   :yotei/calendar-attrs (or calendar-attrs {})})

(defn table-calendar
  "The `yotei.availability` calendar for one table: the floor's shared hours,
  with the table's own overrides on top."
  [flr tbl]
  (availability/calendar (:yotei/calendar-did tbl)
                         (merge (:yotei/calendar-attrs flr) (:yotei/attrs tbl))))

(defn fits?
  "Whether this table seats a party of `party-size`."
  [tbl party-size]
  (and (integer? party-size) (pos? party-size)
       (<= party-size (:yotei/seats tbl))))

(defn largest-seats
  "The biggest party the room can seat at all, ignoring who is sitting."
  [flr]
  (reduce max 0 (map :yotei/seats (:yotei/tables flr))))

(defn free-tables
  "Tables that fit the party AND are free for `[start, start+duration)`.

  Ordered smallest-sufficient first, then by table id — a total order, so a
  governor recomputing this gets the same table the advisor proposed rather
  than a different equally-valid one."
  [flr confirmed start-epoch-min duration-min party-size]
  (->> (:yotei/tables flr)
       (filter #(fits? % party-size))
       (filter #(yoyaku/is-free? (:yotei/calendar-did %)
                                 start-epoch-min duration-min confirmed))
       (sort-by (juxt :yotei/seats (comp str :yotei/table-id)))
       vec))

(defn assign
  "Choose the table for a party, or say why there is none.

  The two refusals are different facts and are kept apart: `:no-table-large-enough`
  is permanent for this party (offering another time will not help), while
  `:all-tables-taken` is about this instant only."
  [flr confirmed start-epoch-min duration-min party-size]
  (cond
    (not (and (integer? party-size) (pos? party-size)))
    {:yotei/refused :bad-party-size :yotei/party-size party-size}

    (> party-size (largest-seats flr))
    {:yotei/refused :no-table-large-enough
     :yotei/party-size party-size
     :yotei/largest-seats (largest-seats flr)}

    :else
    (if-let [tbl (first (free-tables flr confirmed start-epoch-min duration-min party-size))]
      {:yotei/table tbl}
      {:yotei/refused :all-tables-taken})))

(defn seatable?
  "The governor seam: recompute whether this party can sit here at this instant,
  rather than believing an advisor that says they can."
  [flr confirmed start-epoch-min duration-min party-size]
  (some? (:yotei/table (assign flr confirmed start-epoch-min duration-min party-size))))

(defn holds-table?
  "Whether `table-calendar-did` is a table of this floor that seats the party.

  Used at confirm time: the identity of the table is carried in the 予約's own
  `calendarDid`, and this is how yotei re-derives that it is a table at all
  rather than an arbitrary DID a caller supplied."
  [flr table-calendar-did party-size]
  (boolean
   (some (fn [t] (and (= table-calendar-did (:yotei/calendar-did t))
                      (fits? t party-size)))
         (:yotei/tables flr))))

(defn open-times
  "Instants in `[from, to)` at which *some* table can seat this party.

  De-duplicated across tables, ascending. The de-duplication is the point and
  not an optimization: a caller learns that 19:00 is possible, never that it is
  possible on exactly one table (G6)."
  [flr from-epoch-min to-epoch-min confirmed now-epoch-min party-size]
  (->> (:yotei/tables flr)
       (filter #(fits? % party-size))
       (mapcat (fn [t]
                 (availability/openings (table-calendar flr t)
                                        from-epoch-min to-epoch-min
                                        confirmed now-epoch-min)))
       (map :yotei/start-epoch-min)
       distinct
       sort
       vec))

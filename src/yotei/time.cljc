(ns yotei.time
  "Civil time as integer arithmetic, so scheduling is portable and testable.

  Every other namespace here speaks *epoch minutes* — a single integer, UTC.
  `yotei.yoyaku` already did (`startEpochMin`, `durationMin`), and the
  no-double-book invariant is an interval comparison, which is the one thing an
  integer does perfectly and a date library does with a timezone argument.

  What was missing was the way in and out. A booking page shows a Tuesday in
  March and a caller sends `2026-03-10T10:00:00Z`; neither is an integer until
  something converts it. Doing that with the platform's date type would put a
  reader conditional in the middle of the invariant and give the JVM and the
  browser two chances to disagree about a leap year.

  So the conversion is Howard Hinnant's `days_from_civil` / `civil_from_days`,
  which is exact for every proleptic-Gregorian date and is pure integer
  division. `quot` truncates toward zero the way the algorithm's C++ does, and
  every division it performs is on a non-negative value because the era term is
  computed first — so the two runtimes cannot drift.

  ## Timezone is a display concern and is carried explicitly

  Nothing here has a local timezone. A calendar declares `:yotei/tz-offset-min`
  and the view applies it. An offset is not a timezone — it does not know that
  Japan has never had DST while Berlin has — but it is honest about being an
  offset, whereas a server-local `LocalDateTime` silently claims to be a
  timezone and is wrong on the machine that renders it."
  (:require [clojure.string :as str]))

(def ^:private digit
  ;; Portable because a cljs char is a one-character string and `(seq \"12\")`
  ;; yields exactly the keys this map holds in both runtimes.
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})

(defn- digits->int
  "The integer `s` spells, or nil if `s` is empty or holds a non-digit.

  nil rather than 0: `parse-instant` distinguishes a malformed field from a
  zero one, and `Integer/parseInt`'s exception is a reader conditional."
  [s]
  (when (seq s)
    (reduce (fn [acc c]
              (if-let [d (digit c)]
                (+ (* acc 10) d)
                (reduced nil)))
            0
            s)))

(defn days-from-civil
  "Days since 1970-01-01 for the proleptic-Gregorian date y-m-d.

  Hinnant's algorithm. `y` is the astronomical year (1 BC is 0), `m` is 1-12."
  [y m d]
  (let [y (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn civil-from-days
  "The `[y m d]` that `days-from-civil` maps back from `z`."
  [z]
  (let [z (+ z 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (+ (- doe (quot doe 1460)) (quot doe 36524) (- (quot doe 146096))) 365)
        y (+ yoe (* era 400))
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp (quot (+ (* 5 doy) 2) 153)
        d (+ (- doy (quot (+ (* 153 mp) 2) 5)) 1)
        m (+ mp (if (< mp 10) 3 -9))]
    [(if (<= m 2) (inc y) y) m d]))

(def day-names
  "Weekday keywords indexed the way `day-of-week` returns them."
  [:sunday :monday :tuesday :wednesday :thursday :friday :saturday])

(defn day-of-week
  "The weekday of epoch-day `z` as a keyword.

  1970-01-01 was a Thursday, which is where the +4 comes from; the second
  branch keeps the modulus non-negative for dates before the epoch."
  [z]
  (nth day-names (if (>= z -4) (mod (+ z 4) 7) (+ (mod (+ z 5) 7) 6))))

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn- pad4 [n]
  (cond (< n 10) (str "000" n)
        (< n 100) (str "00" n)
        (< n 1000) (str "0" n)
        :else (str n)))

(defn parse-instant
  "`\"2026-03-10T10:00:00Z\"` as epoch minutes, or nil if it is not that.

  Accepts the ISO-8601 shapes a scheduler actually receives: a date alone
  (midnight), `T`-separated minutes, and optional seconds and `Z`. Seconds are
  truncated, not rounded — a slot boundary is a minute, and rounding
  `10:29:59.9` up to `10:30` would move a booking into the next slot.

  Only UTC. A trailing offset like `+09:00` is refused rather than ignored,
  because ignoring it would silently book nine hours from where the caller
  meant. Callers holding a local time convert with `:yotei/tz-offset-min`."
  [s]
  (let [s (str/trim (str s))
        s (if (str/ends-with? s "Z") (subs s 0 (dec (count s))) s)]
    (when (and (>= (count s) 10)
               (= \- (nth s 4)) (= \- (nth s 7))
               (not (or (str/includes? (subs s 10) "+")
                        (str/includes? (subs s 10) "-"))))
      (let [y (digits->int (subs s 0 4))
            m (digits->int (subs s 5 7))
            d (digits->int (subs s 8 10))
            rest- (subs s 10)
            [hh mm] (if (str/blank? rest-)
                      [0 0]
                      (when (and (>= (count rest-) 6)
                                 (contains? #{\T \space} (nth rest- 0))
                                 (= \: (nth rest- 3)))
                        [(digits->int (subs rest- 1 3))
                         (digits->int (subs rest- 4 6))]))]
        (when (and y m d hh mm
                   (<= 1 m 12) (<= 1 d 31) (<= 0 hh 23) (<= 0 mm 59)
                   ;; Reject 2026-02-30: it parses, and round-tripping it would
                   ;; silently become March 2nd.
                   (= d (nth (civil-from-days (days-from-civil y m d)) 2)))
          (+ (* (days-from-civil y m d) 1440) (* hh 60) mm))))))

(defn- floor-div
  "`a / b` rounded toward negative infinity, in integers.

  Not `quot`, which truncates toward zero: minute -1 is 23:59 on epoch day
  **-1**, and truncation would call it day 0 and render it as 1970-01-01T23:59
  — a date an hour in the future of a time in the past. Not `Math/floor`
  either, because `(/ -1 1440)` is a Ratio on the JVM and a float in
  ClojureScript, which is exactly the runtime disagreement this namespace
  exists to avoid."
  [a b]
  (let [q (quot a b)]
    (if (and (neg? (rem a b)) (not (zero? (rem a b)))) (dec q) q)))

(defn epoch-day
  "The epoch day containing epoch-minute `epoch-min`."
  [epoch-min]
  (floor-div epoch-min 1440))

(defn format-instant
  "Epoch minutes as `\"2026-03-10T10:00:00Z\"`."
  [epoch-min]
  (let [z (epoch-day epoch-min)
        [y m d] (civil-from-days z)
        rem- (- epoch-min (* z 1440))]
    (str (pad4 y) "-" (pad2 m) "-" (pad2 d)
         "T" (pad2 (quot rem- 60)) ":" (pad2 (mod rem- 60)) ":00Z")))

(defn day-start
  "Midnight UTC of the day containing `epoch-min`, in epoch minutes."
  [epoch-min]
  (* (epoch-day epoch-min) 1440))

(defn parse-hhmm
  "`\"09:30\"` as minutes past midnight, or nil.

  Availability windows are written this way because a window is a time of day
  that recurs, not an instant — `\"09:30\"` on every weekday is one fact, and
  writing it as an instant would make it 260 facts a year."
  [s]
  (let [s (str/trim (str s))]
    (when (and (= 5 (count s)) (= \: (nth s 2)))
      (let [h (digits->int (subs s 0 2))
            m (digits->int (subs s 3 5))]
        (when (and h m (<= 0 h 23) (<= 0 m 59))
          (+ (* h 60) m))))))

(defn format-hhmm
  "Minutes past midnight as `\"09:30\"`."
  [minutes]
  (str (pad2 (quot minutes 60)) ":" (pad2 (mod minutes 60))))

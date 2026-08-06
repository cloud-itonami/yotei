(ns yotei.availability
  "What a calendar owner publishes, and the openings that fall out of it.

  This is the half of Calendly that `cloud-itonami-app`'s Scheduler never had.
  There, an organizer picked a time and the invitee answered yes or no; the
  invitee could never *choose*. Choosing requires the owner to publish when
  they are free, which is a recurring fact (`:monday 10:00-17:00`), and
  requires somebody to turn that recurrence into concrete instants a stranger
  can click.

  ## A window is a time of day, an opening is an instant

  A window recurs and has no year. An opening is a specific UTC minute. The
  expansion between them is where the timezone lives, and it is the only place
  it lives: `yotei.yoyaku` compares integers and never learns that the
  owner is in Tokyo.

  The weekday is decided in the owner's local time. A window on `:monday` is
  Monday where the owner is standing — for a +09:00 owner it starts at 01:00
  UTC Monday, and deciding the weekday in UTC would put their Monday morning
  on Sunday and publish nothing.

  ## Refusals here are honest, not scarce (G6)

  An opening that is taken, too soon, or past the horizon is simply **absent**.
  There is no count, no 'last remaining', no strike-through. The manifest's G6
  forbids optimizing for conversion, and a taken slot rendered as unavailable
  is the smallest possible version of exactly that — it tells a stranger how
  busy the owner is, which is both a nudge and a leak of the owner's day.

  ## Notice and horizon are limits, not persuasion

  `:yotei/notice-min` keeps a stranger from taking a slot four minutes from
  now, and `:yotei/horizon-days` keeps them from taking one in 2031. Both
  narrow what is offered rather than showing something and refusing it later,
  because an opening you can see and cannot take is the interaction G6 exists
  to prevent."
  (:require [yotei.yoyaku :as yoyaku]
            [yotei.time :as t]))

(def defaults
  "The calendar settings a 予約 page assumes when the owner has not said.

  30-minute slots, an hour's notice, and 60 days ahead: long enough to be
  useful, short enough that an abandoned calendar stops offering meetings
  rather than offering them forever."
  {:yotei/slot-min 30
   :yotei/tz-offset-min 0
   :yotei/notice-min 60
   :yotei/horizon-days 60})

(defn calendar
  "A calendar that accepts 予約.

  `:yotei/windows` is a vector of `{:yotei/day :monday :yotei/from \"10:00\"
  :yotei/to \"17:00\"}`. `:yotei/tz-offset-min` is minutes east of UTC (Japan
  is 540) and applies to every window and every closed date."
  [calendar-did attrs]
  (merge defaults
         {:yotei/calendar-did calendar-did
          :yotei/windows []
          ;; Local dates ("2026-03-10") on which every window is withdrawn.
          ;; Holidays and leave: an exception to a recurrence, and cheaper to
          ;; state than to encode as a gap in the recurrence itself.
          :yotei/closed-dates #{}}
         attrs))

(defn window
  "One recurring opening. Refuses a window that is not a window."
  [day from to]
  (let [from-min (t/parse-hhmm from)
        to-min (t/parse-hhmm to)]
    (when-not (some #{day} t/day-names)
      (throw (ex-info (str "曜日ではありません: " day)
                      {:type :yotei/bad-window :day day})))
    (when-not (and from-min to-min)
      (throw (ex-info "時刻は HH:MM で指定してください。"
                      {:type :yotei/bad-window :from from :to to})))
    (when-not (< from-min to-min)
      ;; An empty or reversed window yields no openings, so it would look like
      ;; a calendar with no availability rather than a calendar misconfigured.
      (throw (ex-info "終了は開始より後にしてください。"
                      {:type :yotei/bad-window :from from :to to})))
    {:yotei/day day :yotei/from from :yotei/to to}))

(defn- local-date-iso [local-epoch-day]
  (let [[y m d] (t/civil-from-days local-epoch-day)]
    (subs (t/format-instant (* (t/days-from-civil y m d) 1440)) 0 10)))

(defn- closed? [cal local-epoch-day]
  (contains? (set (:yotei/closed-dates cal)) (local-date-iso local-epoch-day)))

(defn- windows-on [cal local-epoch-day]
  (let [dow (t/day-of-week local-epoch-day)]
    (filter #(= dow (:yotei/day %)) (:yotei/windows cal))))

(defn openings
  "Every takeable instant on `cal` within `[from-epoch-min, to-epoch-min)`.

  `confirmed` are the wire-shaped 予約 that `yotei.yoyaku` already
  understands; the overlap test is that namespace's `is-free?` rather than a
  second one written here, so an opening this function offers and a
  `propose-yoyaku` that accepts it cannot disagree about what 'free' means.

  Returns `[{:yotei/start-epoch-min .. :yotei/duration-min ..}]`, ascending."
  [cal from-epoch-min to-epoch-min confirmed now-epoch-min]
  (let [{:yotei/keys [calendar-did tz-offset-min slot-min notice-min horizon-days]}
        (merge defaults cal)
        ;; Everything below is decided in local minutes, then shifted back to
        ;; UTC exactly once, at the point an opening becomes an instant.
        earliest (max from-epoch-min (+ now-epoch-min notice-min))
        latest (min to-epoch-min (+ now-epoch-min (* horizon-days 1440)))]
    (if (>= earliest latest)
      []
      (let [first-day (t/epoch-day (+ earliest tz-offset-min))
            last-day (t/epoch-day (+ (dec latest) tz-offset-min))]
        (->> (range first-day (inc last-day))
             (remove #(closed? cal %))
             (mapcat
              (fn [local-day]
                (mapcat
                 (fn [w]
                   (let [from-min (t/parse-hhmm (:yotei/from w))
                         to-min (t/parse-hhmm (:yotei/to w))
                         ;; local minute -> UTC minute
                         start-utc (- (+ (* local-day 1440) from-min) tz-offset-min)
                         end-utc (- (+ (* local-day 1440) to-min) tz-offset-min)]
                     (->> (range start-utc end-utc slot-min)
                          (filter #(<= (+ % slot-min) end-utc))
                          (filter #(and (>= % earliest) (<= (+ % slot-min) latest)))
                          (filter #(yoyaku/is-free? calendar-did % slot-min confirmed))
                          (map (fn [s] {:yotei/start-epoch-min s
                                        :yotei/duration-min slot-min})))))
                 (windows-on cal local-day))))
             (sort-by :yotei/start-epoch-min)
             vec)))))

(defn open?
  "Whether `cal` actually offers `start-epoch-min` for `duration-min`.

  `propose-yoyaku` enforces no-double-book but knows nothing about windows,
  notice or horizon — without this a caller could POST an instant at 3am on a
  Sunday and it would be accepted because nothing had taken 3am on a Sunday.
  The 予約 route calls this first."
  [cal start-epoch-min duration-min confirmed now-epoch-min]
  (boolean
   (some (fn [o]
           (and (= start-epoch-min (:yotei/start-epoch-min o))
                (= duration-min (:yotei/duration-min o))))
         (openings cal start-epoch-min (+ start-epoch-min duration-min)
                   confirmed now-epoch-min))))

(defn by-local-day
  "`openings` grouped for rendering: `[{:yotei/date \"2026-03-10\" :yotei/openings [..]}]`.

  Grouped by the *owner's* local date, because that is the day the page shows
  and a UTC grouping would split a Tokyo afternoon across two headings."
  [cal openings-seq]
  (let [offset (:yotei/tz-offset-min (merge defaults cal))]
    (->> openings-seq
         (group-by #(subs (t/format-instant
                           (+ (:yotei/start-epoch-min %) offset))
                          0 10))
         (sort-by key)
         (mapv (fn [[date os]]
                 {:yotei/date date
                  :yotei/openings (vec (sort-by :yotei/start-epoch-min os))})))))

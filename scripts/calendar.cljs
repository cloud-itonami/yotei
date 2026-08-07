(ns calendar
  "Create or update a bookable calendar, validated before it is published.

  A calendar is one EDN value in KV under `calendar:<did>`. Writing it by hand
  works and is how the first one was made — which is also why this exists: a
  window written as `\"9:00\"` instead of `\"09:00\"` parses to nil, the day
  silently offers nothing, and the page looks like an empty schedule rather
  than a typo. `yotei.availability/window` already refuses that. So the spec
  goes through the real constructor, and a calendar that would publish nothing
  cannot be published.

  It also refuses to write a calendar that offers no openings at all in the
  next fortnight. That is not the same check: every window can be individually
  valid and the calendar still be useless — all windows on a day that is in
  `:closed-dates`, or a horizon shorter than the notice.

  ## One calendar per bookable thing, not per person

  Calendly's unit is the event type, not the human: a 15-minute chat and a
  90-minute review have different lengths, different hours and different links.
  Here that is one calendar each, `jun-15min` and `jun-review`, both owned by
  the same person. The segment is the URL, so it is what the visitor sees.

  Usage:
    nbb scripts/calendar.cljs list
    nbb scripts/calendar.cljs show <segment>
    nbb scripts/calendar.cljs put <spec.edn>
    nbb scripts/calendar.cljs put <spec.edn> --dry-run"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [yotei.availability :as av]
            [yotei.time :as t]))

(def kv-namespace-id "f1c62eb9ebb3436ea1ab8aaf92e7fee7")
(def host "app.itonami.cloud")

(defn- did [segment] (str "did:web:" host ":yotei:calendar:" segment))
(defn- public-url [segment] (str "https://" host "/yotei/c/" segment))

(def ^:private SEGMENT #"^[a-z0-9][a-z0-9-]{0,62}$")

(defn- wrangler!
  "Run wrangler and return stdout, or throw with its stderr.

  `--remote` on every call: without it wrangler writes to a local simulation
  and reports success, so a calendar would appear to be published and the URL
  would 404."
  [args]
  (let [r (cp/spawnSync "npx" (clj->js (concat ["wrangler" "kv"] args))
                        #js {:encoding "utf8" :cwd (js/process.cwd)})]
    (when-not (zero? (.-status r))
      (throw (ex-info (str "wrangler failed: " (.-stderr r)) {:args args})))
    (.-stdout r)))

(defn- validate!
  "Turn a spec into the calendar value, or throw saying what is wrong.

  Every window goes through `av/window`, which is the same constructor the
  tests use — this file holds no second opinion about what a window is."
  [{:keys [segment name owner-label owner-did purpose tz-offset-min slot-min
           notice-min horizon-days windows closed-dates
           owner-enc-key owner-sig-key notify-webhook]
    :or {tz-offset-min 540 slot-min 30 notice-min 60 horizon-days 60
         closed-dates #{}}}]
  (when-not (and segment (re-matches SEGMENT segment))
    (throw (ex-info (str "segment は [a-z0-9-] で始まる小文字英数字: " (pr-str segment))
                    {:segment segment})))
  (when (str/blank? (str owner-label))
    (throw (ex-info ":owner-label は必須（ページの見出しと本文に出る）" {})))
  (when-not (seq windows)
    (throw (ex-info ":windows が空 — 何も予約できないカレンダーになる" {})))
  (doseq [d closed-dates]
    (when-not (t/parse-instant d)
      (throw (ex-info (str ":closed-dates は YYYY-MM-DD: " (pr-str d)) {}))))
  (let [cal (assoc (av/calendar (did segment)
                                {:yotei/tz-offset-min tz-offset-min
                                 :yotei/slot-min slot-min
                                 :yotei/notice-min notice-min
                                 :yotei/horizon-days horizon-days
                                 :yotei/closed-dates (set closed-dates)
                                 :yotei/windows (mapv (fn [[day from to]]
                                                        (av/window day from to))
                                                      windows)})
                   ;; Public keys only. The private halves live in kagi as
                   ;; `yotei-owner-<segment>` and are used by
                   ;; scripts/owner.cljs on the owner's machine. Without
                   ;; :owner-enc-key the Worker stores the contact in plaintext
                   ;; AND the form stops claiming otherwise — the sentence and
                   ;; the ciphertext have one cause.
                   ;; Carried through explicitly. A field present in the spec
                   ;; and absent from this map is silently dropped — which is
                   ;; how the first notify-webhook was configured, published,
                   ;; and never fired.
                   :yotei/notify-webhook notify-webhook
                   :yotei/owner-enc-key owner-enc-key
                   :yotei/owner-sig-key owner-sig-key
                   :yotei/name (or name "")
                   :yotei/owner-label owner-label
                   :yotei/owner-did (or owner-did (str "did:web:" host ":org:yotei"))
                   :yotei/purpose (or purpose ""))
        ;; Would it actually offer anything? Individually-valid windows can
        ;; still add up to a calendar that publishes an empty page.
        now (t/parse-instant (subs (.toISOString (js/Date.)) 0 16))
        preview (av/openings cal now (+ now (* 14 1440)) [] now)]
    (when (empty? preview)
      (throw (ex-info "この設定では今後2週間に空きが1つも出ない（closed-dates / horizon / notice を確認）"
                      {:segment segment})))
    [cal preview]))

(defn- put! [spec-path dry-run?]
  (let [spec (edn/read-string (fs/readFileSync spec-path "utf8"))
        specs (if (map? spec) [spec] spec)]
    (doseq [s specs]
      (let [[cal preview] (validate! s)
            segment (:segment s)
            grouped (av/by-local-day cal preview)]
        (println "\n" (or (not-empty (str (:yotei/name cal)))
                          (:yotei/owner-label cal)) "—" segment)
        (println "  " (public-url segment))
        (when (:yotei/notify-webhook cal)
          (println "   通知先:" (:yotei/notify-webhook cal)))
        (println "  " (if (:yotei/owner-enc-key cal)
                        "連絡先は暗号化 / 確定は署名"
                        "⚠ 鍵なし — 連絡先は平文、確定できません（owner.cljs keygen）"))
        (println "  " (str (:yotei/slot-min cal) "分枠 / UTC+"
                           (t/format-hhmm (:yotei/tz-offset-min cal))
                           " / 今後2週間で " (count preview) " 枠 " (count grouped) " 日"))
        (if dry-run?
          (println "   (dry-run — 書き込んでいません)")
          (let [tmp (str "/tmp/yotei-cal-" segment ".edn")]
            (fs/writeFileSync tmp (pr-str cal) "utf8")
            (wrangler! ["key" "put" "--namespace-id" kv-namespace-id
                        (str "calendar:" (did segment)) "--path" tmp "--remote"])
            (fs/unlinkSync tmp)
            (println "   published")))))))

(defn- list! []
  (let [out (wrangler! ["key" "list" "--namespace-id" kv-namespace-id "--remote"])
        keys- (->> (try (js->clj (js/JSON.parse out)) (catch :default _ []))
                   (map #(get % "name"))
                   (filter #(str/starts-with? (str %) "calendar:"))
                   sort)]
    (if (empty? keys-)
      (println "カレンダーはまだありません。")
      (doseq [k keys-]
        (let [seg (last (str/split k #":"))]
          (println (str "  " seg "  " (public-url seg))))))))

(defn- show! [segment]
  (let [out (wrangler! ["key" "get" "--namespace-id" kv-namespace-id
                        (str "calendar:" (did segment)) "--remote"])
        cal (edn/read-string out)]
    (println (pr-str cal))))

(defn -main [& _]
  (let [args (vec (drop-while #(not (contains? #{"list" "show" "put"} %))
                              (js->clj js/process.argv)))
        [cmd a] args
        dry? (boolean (some #{"--dry-run"} args))]
    (try
      (case cmd
        "list" (list!)
        "show" (show! a)
        "put" (put! a dry?)
        (println "usage: nbb scripts/calendar.cljs (list | show <segment> | put <spec.edn> [--dry-run])"))
      (catch :default e
        (println "エラー:" (or (ex-message e) (str e)))
        (set! (.-exitCode js/process) 1)))))

(apply -main (drop 2 (js->clj js/process.argv)))

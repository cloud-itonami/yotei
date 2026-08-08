(ns busy
  "Push the owner's existing appointments into yotei, so it stops offering
  time that is already taken.

  ## Two sources, both on the owner's machine

  - `--macos` — Calendar.app through `kotoba-lang/shell`'s native host, behind
    the `calendar/read` capability. This is the useful one: it sees iCloud,
    Google, Exchange, subscribed calendars — whatever the owner has actually
    added — without yotei holding a credential for any of them.
  - `--ics <url|file>` — a published iCalendar. Google's *Secret address in
    iCal format* and iCloud's public share URL both land here, which is the
    path for a calendar that is not on this machine.

  Neither needs an OAuth app, a client secret, or a token yotei has to store.
  That is deliberate after the Resend hunt: a design that waits on a credential
  nobody can find is a design that does not ship.

  ## Only intervals leave the machine

  `yotei.busy` reduces events to `{:start :duration}` before anything is
  uploaded — no title, no attendees, no location. yotei needs to know *when*
  to keep quiet and nothing else, and this script is the boundary where that
  reduction happens rather than something the server is trusted to do.

  ## Signed, because writing here changes what strangers are offered

  The upload is signed with the same key that confirms a 予約 (G5). Anyone who
  could post busy blocks unsigned could blank an owner's calendar page, or —
  worse — un-block a slot the owner is actually in.

  Usage:
    nbb --classpath src scripts/busy.cljs push <segment> --google
    nbb --classpath src scripts/busy.cljs push <segment> --macos
    nbb --classpath src scripts/busy.cljs push <segment> --ics <url|file>
    nbb --classpath src scripts/busy.cljs show <segment>"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ical.ical :as ical]
            [promesa.core :as p]
            [yotei.busy :as busy]
            [yotei.envelope :as envelope]
            [yotei.time :as t]))

(def host "https://app.itonami.cloud")
(def kv-namespace-id "f1c62eb9ebb3436ea1ab8aaf92e7fee7")
(def fleet-root "/Users/junkawasaki/github/com-junkawasaki")
(def kagi-bin (str fleet-root "/orgs/kotoba-lang/kagi/bin/kagi"))
(def shell-dir (str fleet-root "/orgs/kotoba-lang/shell"))

(def args
  (let [argv (vec (js->clj js/process.argv))
        i (->> (map-indexed vector argv)
               (filter (fn [[_ a]] (str/ends-with? (str a) "busy.cljs")))
               ffirst)]
    (if i (subvec argv (inc i)) [])))

(defn- flag [name-]
  (let [i (.indexOf (clj->js args) name-)]
    (when (>= i 0) (or (nth args (inc i) nil) true))))

(defn- did [seg] (str "did:web:app.itonami.cloud:yotei:calendar:" seg))
(defn- key-name [seg] (str "yotei-owner-" seg))

(defn- run [cmd argv opts]
  (let [r (cp/spawnSync cmd (clj->js argv) (clj->js (merge {:encoding "utf8"
                                                            :maxBuffer 20000000} opts)))]
    {:out (str/replace (str (.-stdout r)) #"\[[0-9;]*m" "")
     :err (str (.-stderr r)) :status (.-status r)}))

(defn- kagi-get [n]
  (let [{:keys [out status]} (run kagi-bin ["get" n]
                                  {:env (doto (js/Object.assign #js {} js/process.env)
                                          (aset "FLEET_ROOT" fleet-root))})]
    (when (zero? status) (str/trim out))))

(defn- kv-get [k]
  (let [{:keys [out]} (run "npx" ["wrangler" "kv" "key" "get" "--namespace-id"
                                  kv-namespace-id k "--remote"] {})]
    (when-not (or (str/blank? out) (str/includes? out "ERROR"))
      ;; wrangler prints a banner first, so the value starts at the first EDN
      ;; opener. All three are needed: a 予約 log is a vector, a calendar is a
      ;; namespaced map (`#:yotei{…}`), and the busy blob is a plain map — the
      ;; first version of this looked for only the first two and reported a
      ;; freshly-pushed busy set as "not registered".
      (let [idx (->> ["[" "#:" "{"] (keep #(str/index-of out %)) sort first)]
        (when idx (try (edn/read-string (subs out idx)) (catch :default _ nil)))))))

;; ── sources ──────────────────────────────────────────────────────────────────

(defn- from-macos
  "Calendar.app via kotoba-lang/shell's native host.

  The capability policy is the narrowest that works: read, nothing else. The
  same call cloud-itonami-app already makes for its Scheduler view, so this is
  a second consumer of a path that exists rather than a new one."
  [from to]
  (let [{:keys [out status err]}
        (run "clojure" ["-M:run" "native-host" "provider"
                        "--target" "macos"
                        "--provider-command" "calendar/list-events"
                        "--host-arg" "--from" "--host-arg" from
                        "--host-arg" "--to" "--host-arg" to
                        "--policy-edn" "{:allow [\"calendar/read\"] :deny []}"]
             {:cwd shell-dir})]
    (if-not (zero? status)
      ;; Report what the host actually said. The first version of this printed
      ;; a guess ("check the permission"), which happened to be right and
      ;; would have been misleading for any other cause — the host
      ;; distinguishes "access denied" from "no such provider" and the
      ;; operator needs to know which.
      (let [detail (or (second (re-find #"\"error\":\"([^\"]+)\"" out))
                       (not-empty (str/trim err))
                       (not-empty (str/trim out))
                       "理由不明")]
        (throw (ex-info (str "macOS カレンダーを読めませんでした: " detail
                             "\n  システム設定 → プライバシーとセキュリティ → カレンダー で許可してください。")
                        {})))
      ;; The native host answers JSON; turn it into the same iCalendar model
      ;; the .ics path produces, so `yotei.busy` has exactly one input shape.
      (let [payload (js->clj (js/JSON.parse (str/trim (last (str/split out #"\n")))))]
        {:ical/events
         (mapv (fn [e]
                 (let [parse (fn [s] (when s
                                       (when-let [m (t/parse-instant
                                                     (str/replace (str s) #"\.[0-9]+Z?$" "Z"))]
                                         (let [d (t/epoch-day m) r (- m (* d 1440))
                                               [y mo dd] (t/civil-from-days d)]
                                           {:y y :m mo :d dd :hh (quot r 60) :mm (mod r 60)
                                            :utc? true}))))]
                   {:ical/uid (get e "id")
                    :ical/dtstart (parse (get e "start"))
                    :ical/dtend (parse (get e "end"))}))
               (get payload "events"))}))))

(defn- from-google
  "Google Calendar freeBusy, with a token the caller already has.

  ## Where the token comes from, and why not from here

  `cloud-itonami-app` already implements the whole OAuth workflow — Google
  provider, PKCE, refresh, and `calendar.readonly` already in its scope list —
  and its `identity/access-token` says in as many words: *never returns a
  token reference or token through an HTTP/public view*. That is the right
  refusal, and it is why this script does not ask the app for one. A second
  OAuth client registered to yotei would be a second thing to keep secret for
  no gain.

  So the token is supplied: `$GOOGLE_ACCESS_TOKEN`, or kagi
  `yotei-google-token`. The intended end state is that the app performs this
  freeBusy call itself, holding the token it already has, and pushes the
  intervals — at which point this function is what runs there, unchanged.

  ## freeBusy, not events.list

  The response has nowhere to put a title. events.list would send summaries
  and attendees across the network to be discarded locally, which is a promise
  about our code; freeBusy means Google never sends them. It also expands
  recurrences server-side, which is the limitation `--ics` has to report."
  [from to]
  (let [token (or (some-> js/process.env.GOOGLE_ACCESS_TOKEN)
                  (kagi-get "yotei-google-token"))]
    (when-not (seq (str token))
      (throw (ex-info (str "Google のアクセストークンがありません。\n"
                           "  $GOOGLE_ACCESS_TOKEN か kagi の yotei-google-token に入れてください。\n"
                           "  OAuth の接続自体は cloud-itonami-app が持っています"
                           "（Google provider, calendar.readonly、PKCE + refresh）。\n"
                           "  ただし GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET が未設定なので、"
                           "まず Google Cloud で OAuth クライアントを作る必要があります（オーナー作業）。")
                      {})))
    (p/let [r (js/fetch (or (some-> js/process.env.YOTEI_GOOGLE_FREEBUSY_URL)
                            "https://www.googleapis.com/calendar/v3/freeBusy")
                        #js {:method "POST"
                             :headers #js {"authorization" (str "Bearer " token)
                                           "content-type" "application/json"}
                             :body (js/JSON.stringify
                                    (clj->js {:timeMin from :timeMax to
                                              :items [{:id "primary"}]}))})
            body (.json r)]
      (if-not (.-ok r)
        (throw (ex-info (str "Google freeBusy が失敗しました [" (.-status r) "]: "
                             (get-in (js->clj body) ["error" "message"] ""))
                        {}))
        (js->clj body)))))

(defn- from-ics [src]
  (p/let [text (if (str/starts-with? src "http")
                 (p/let [r (js/fetch src)]
                   (if-not (.-ok r)
                     (throw (ex-info (str "ICS を取得できませんでした: HTTP " (.-status r)) {}))
                     (.text r)))
                 (fs/readFileSync src "utf8"))]
    (ical/parse-str text)))

;; ── push ─────────────────────────────────────────────────────────────────────

(defn- push! [seg]
  (p/let [cal (kv-get (str "calendar:" (did seg)))
          _ (when-not cal (throw (ex-info (str "カレンダー " seg " がありません。") {})))
          secret (some-> (kagi-get (key-name seg)) edn/read-string)
          _ (when-not secret
              (throw (ex-info (str "kagi に " (key-name seg) " がありません。owner.cljs keygen を先に。") {})))
          offset (or (:yotei/tz-offset-min cal) 540)
          horizon (or (:yotei/horizon-days cal) 60)
          now (t/parse-instant (subs (.toISOString (js/Date.)) 0 16))
          [from to] (busy/window now horizon)
          model (cond
                  (flag "--macos") (from-macos (t/format-instant from) (t/format-instant to))
                  (flag "--ics") (from-ics (flag "--ics"))
                  (flag "--google") ::google
                  :else (throw (ex-info "--macos / --ics <url|file> / --google のいずれかを指定してください。" {})))
          ;; freeBusy answers intervals directly, so it skips the iCalendar
          ;; model entirely rather than being squeezed through it.
          raw (if (= ::google model)
                (p/let [resp (from-google (t/format-instant from) (t/format-instant to))]
                  (busy/from-google-freebusy resp))
                (busy/from-ical model offset (or (:yotei/slot-min cal) 30)))
          intervals (busy/merge-adjacent (busy/within raw from to))
          report (if (= ::google model)
                   ;; freeBusy expands recurrences server-side, so there is no
                   ;; un-expanded remainder to warn about — unlike --ics.
                   {:events (count raw) :intervals (count raw) :recurring-not-expanded 0}
                   (busy/ingest-report model raw))
          payload {:calendarDid (did seg) :intervals intervals
                   :generatedAt (.toISOString (js/Date.))}
          ;; Signed over the exact bytes that will be stored, so the server
          ;; cannot be handed one payload and store another.
          body (js/JSON.stringify (clj->js payload))
          signature (envelope/sign (:private (:sig secret)) body)
          res (js/fetch (str host "/yotei/busy/" seg)
                        #js {:method "POST"
                             :headers #js {"content-type" "application/json"}
                             :body (js/JSON.stringify
                                    (clj->js {:payload body :signature signature}))})
          out (.json res)]
    (println (str seg " — " (:events report) " 件のイベント → "
                  (count intervals) " 区間（" (t/format-instant from)
                  " 〜 " (t/format-instant to) "）"))
    (when (pos? (:recurring-not-expanded report))
      ;; Said out loud. A silent omission here reads as success while leaving
      ;; every recurrence of a weekly meeting bookable.
      (println (str "  ⚠ 繰り返し予定 " (:recurring-not-expanded report)
                    " 件は展開していません — 初回のみ塞がります")))
    (if (get (js->clj out) "ok")
      (println "  push しました。")
      (do (println "  失敗 [" (.-status res) "]:" (get (js->clj out) "error"))
          (set! (.-exitCode js/process) 1)))))

(defn- show! [seg]
  (p/let [cal (kv-get (str "calendar:" (did seg)))
          stored (kv-get (str "busy:" (did seg)))
          offset (or (:yotei/tz-offset-min cal) 540)]
    (if-not (seq (:intervals stored))
      (println "busy は登録されていません。")
      (do (println (str seg " — " (count (:intervals stored)) " 区間  ("
                        (:generated-at stored) ")"))
          (doseq [{:keys [start duration]} (:intervals stored)]
            (let [l (t/format-instant (+ start offset))
                  e (t/format-instant (+ start offset duration))]
              (println
               (str "  " (subs l 0 10) " "
                    (cond
                      ;; A 24-hour block rendered as HH:MM〜HH:MM reads
                      ;; "00:00〜00:00", which looks like nothing at all
                      ;; rather than like the whole day it blocks.
                      (= duration 1440) "終日"
                      ;; Anything crossing midnight needs its end date, for
                      ;; the same reason.
                      (not= (subs l 0 10) (subs e 0 10))
                      (str (subs l 11 16) "〜" (subs e 0 10) " " (subs e 11 16))
                      :else (str (subs l 11 16) "〜" (subs e 11 16)))))))))))

(defn -main []
  (let [[cmd seg] args]
    (-> (case cmd
          "push" (push! seg)
          "show" (show! seg)
          (p/resolved
           (println "usage: nbb --classpath src scripts/busy.cljs (push|show) <segment> [--macos | --ics <url|file>]")))
        (p/catch (fn [e]
                   (println "エラー:" (or (ex-message e) (str e)))
                   (set! (.-exitCode js/process) 1))))))

(-main)

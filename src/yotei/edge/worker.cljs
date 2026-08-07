(ns yotei.edge.worker
  "The 予約 page as a Cloudflare Worker.

  Mounted in the `ai-gftd-repository-dispatch` namespace as `yotei`, so
  `itonami-fleet-dispatch` reaches it at `app.itonami.cloud/yotei/*` and strips
  its own name from the path. Every route below is written against the path the
  actor sees — `/c/alice`, not `/yotei/c/alice` — which is what lets it be
  tested without knowing where it is mounted.

  ## The ingress is ClojureScript on purpose

  Kotoba has no ingress capability (no Request→Response effect), so an entry
  point cannot be written in `.kotoba` today — ADR-2606290000, and CLAUDE.md
  says so directly. Everything the request *decides* is nonetheless in the
  portable `.cljc` namespaces; this file parses, awaits and serialises, and
  makes no scheduling decision of its own. That is the boundary to hold: if a
  rule about who may take which slot ever appears in this file, it has escaped
  the layer that is tested.

  ## No JavaScript is served

  The page is forms and links. That is not minimalism for its own sake — a 予約
  page is the last thing somebody opens on a bad connection in a hurry, and a
  form that works before a bundle loads is the difference between a meeting and
  a missed one."
  (:require [clojure.string :as str]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [yotei.availability :as av]
            [yotei.edge.kv :as kv]
            [yotei.time :as t]
            [yotei.view :as view]
            [yotei.edge.log-do]
            [yotei.edge.notify :as notify]
            [yotei.envelope :as envelope]
            [yotei.store :as store])
  (:require-macros [yotei.edge.inline :refer [inline-resource]]))

(def dds-css (inline-resource "jp_go_dds/dds.css"))

(defn- html-response
  [body {:keys [status title description]
         :or {status 200 title "yotei"}}]
  (js/Response.
   (page/->page {:title title :description description :lang "ja"
                 :css dds-css
                 :head view/head-extras
                 :app-css (str tokens/bridge-css "\n" view/app-css)}
                body)
   #js {:status status
        :headers #js {"content-type" "text/html; charset=utf-8"
                      ;; A 予約 page must never be cached: a slot that was free
                      ;; when the page was built is the one thing that goes
                      ;; stale in a way the visitor pays for.
                      "cache-control" "no-store"
                      "referrer-policy" "strict-origin-when-cross-origin"
                      "x-content-type-options" "nosniff"}}))

(defn- json-response [obj status]
  (js/Response. (js/JSON.stringify (clj->js obj))
                #js {:status status
                     :headers #js {"content-type" "application/json"
                                   "cache-control" "no-store"}}))

(defn- form-params
  "A urlencoded body as a Clojure map. Returns a promise."
  [request]
  (-> (.text request)
      (.then (fn [body]
               (let [p (js/URLSearchParams. body)]
                 (into {} (map (fn [[k v]] [k v])) (es6-iterator-seq (.entries p))))))))

(defn- now-epoch-min []
  ;; The clock is read here and passed down. Nothing in `yotei.availability` or
  ;; `yotei.store` reads a clock, which is what makes them testable at fixed
  ;; instants — the moment one of them calls `Date.now()` its tests start
  ;; depending on when they run.
  (Math/floor (/ (js/Date.now) 60000)))

(defn- calendar-did
  "A path segment becomes a DID here and nowhere else.

  The segment is the calendar's short name; the DID is derived rather than
  accepted, so a caller cannot reach another actor's calendar by sending one."
  [host segment]
  (str "did:web:" host ":yotei:calendar:" segment))

(def ^:private SEGMENT #"^[a-z0-9][a-z0-9-]{0,62}$")

(defn- constant-time=
  "String equality that does not leak how much of a secret matched.

  `=` returns as soon as two characters differ, so the time it takes says how
  long the shared prefix was. That is enough to guess a token one character at
  a time given enough attempts."
  [a b]
  (let [a (str a) b (str b)]
    (and (= (count a) (count b))
         (zero? (reduce (fn [acc i]
                          (bit-or acc (bit-xor (.charCodeAt a i) (.charCodeAt b i))))
                        0
                        (range (count a)))))))

(defn- log-stub
  "The Durable Object that owns `did`'s 予約 log.

  `idFromName` on the calendar DID, so the object *is* the calendar: one
  serialization domain per calendar, which is the granularity the invariant
  needs (no-double-book is per-calendar) and no coarser."
  [env did]
  (let [ns- (.-YOYAKU_LOG ^js env)]
    (.get ns- (.idFromName ns- did))))

(defn- do-call
  "Ask the calendar's object to do something. Returns a promise of its JSON."
  [env did op body]
  (let [url (str "https://yoyaku-log/?op=" op "&did=" (js/encodeURIComponent did))]
    (-> (.fetch (log-stub env did) url
                (clj->js (cond-> {:method (if body "POST" "GET")}
                           body (assoc :body (js/JSON.stringify (clj->js body))
                                       :headers {"content-type" "application/json"}))))
        (.then (fn [r] (.json r)))
        (.then (fn [j] (js->clj j))))))

(defn- confirmed-via-do
  "The confirmed 予約, read through the object rather than off a KV replica."
  [env did]
  (-> (do-call env did "read" nil)
      (.then (fn [j] (store/current-confirmed (get j "entries"))))))

(defn- openings-page [env did cal]
  (let [now (now-epoch-min)]
    (-> (confirmed-via-do env did)
        (.then (fn [confirmed]
                 (let [os (av/openings cal now (+ now (* 14 1440)) confirmed now)
                       label (or (:yotei/owner-label cal) "この人")]
                   (html-response
                    (view/yoyaku-page {:owner-label label
                                        :purpose (:yotei/purpose cal)
                                        :calendar cal
                                        :openings os})
                    ;; The title distinguishes the links. Three calendars owned
                    ;; by one person rendered three identical titles, so a
                    ;; visitor holding two of them could not tell which tab was
                    ;; which — the duration and purpose differed only in the
                    ;; body.
                    ;; Named calendars use their name; the duration is not
                    ;; appended, because a name like "15分の相談" already says
                    ;; it and "15分の相談（15分）" reads like a bug. Unnamed
                    ;; ones fall back to the owner plus the length, which is
                    ;; what distinguishes them when nothing else does.
                    {:title (if-let [n (not-empty (str (:yotei/name cal)))]
                              (str n " — " label " — yotei")
                              (str label "の予定を押さえる（"
                                   (:yotei/slot-min cal) "分） — yotei"))
                     :description "空いている時間を選んで申し込めます。"})))))))

(defn- handle-select
  "The chosen time, echoed back as a form to fill in.

  Re-validated rather than trusted: the visitor may have had the page open for
  an hour, and offering a form for a slot that has since gone would collect
  their details and then refuse them."
  [env did cal params]
  (let [now (now-epoch-min)
        start (t/parse-instant (get params "start"))
        minutes (js/parseInt (get params "minutes" "0") 10)]
    (if-not (and start (pos? minutes))
      (js/Promise.resolve
       (html-response (view/refused-page {:reason "時間の指定が読み取れませんでした。"})
                      {:status 400 :title "エラー — yotei"}))
      (-> (confirmed-via-do env did)
          (.then (fn [confirmed]
                   (if-not (av/open? cal start minutes confirmed now)
                     (html-response
                      (view/refused-page {:reason "その時間は、この画面を開いている間に埋まりました。"})
                      {:status 409 :title "この時間は取れませんでした — yotei"})
                     (html-response
                      (view/confirm-form {:owner-label (or (:yotei/owner-label cal) "この人")
                                          :calendar cal
                                          :start-epoch-min start
                                          :duration-min minutes})
                      {:title "この時間で申し込む — yotei"}))))))))

(defn- handle-propose
  "Write the proposal — by asking the calendar's Durable Object to.

  This used to read the log, decide, and append at the version it read at.
  That is the read-modify-write that lost six of eight 予約 in a live probe:
  the version check narrows the window and KV has no atomic compare-and-set to
  close it. Now the read, the decision and the append all happen inside one
  object that handles one request at a time, so there is no window."
  [env did cal params ctx]
  (let [now (now-epoch-min)
        start (t/parse-instant (get params "start"))
        minutes (js/parseInt (get params "minutes" "0") 10)
        label (or (:yotei/owner-label cal) "\u3053\u306e\u4eba")
        refuse (fn [reason status]
                 (html-response (view/refused-page {:reason reason})
                                {:status status
                                 :title "\u3053\u306e\u6642\u9593\u306f\u53d6\u308c\u307e\u305b\u3093\u3067\u3057\u305f \u2014 yotei"}))]
    (if-not (and start (pos? minutes)
                 (seq (str/trim (get params "name" "")))
                 (seq (str/trim (get params "contact" ""))))
      (js/Promise.resolve
       (html-response (view/refused-page
                       {:reason "\u304a\u540d\u524d\u3068\u9023\u7d61\u5148\u3092\u5165\u529b\u3057\u3066\u304f\u3060\u3055\u3044\u3002"})
                      {:status 400 :title "\u30a8\u30e9\u30fc \u2014 yotei"}))
      (let [yoyaku-id (str "y-" (js/crypto.randomUUID))
            ;; Name AND contact go inside one envelope. The name was very
            ;; nearly stored in the clear so a webhook could say who booked —
            ;; but a name is booking PII exactly as much as an address is, and
            ;; G2 does not have a carve-out for the convenient half. What the
            ;; notification loses, `owner.cljs list` recovers on the owner's
            ;; own machine, where the key already is.
            who (js/JSON.stringify
                 (clj->js {:name (str/trim (get params "name"))
                           :contact (str/trim (get params "contact"))}))
            contact-p (if-let [k (:yotei/owner-enc-key cal)]
                        (envelope/seal k who yoyaku-id)
                        (js/Promise.resolve
                         (str "unencrypted-pending-envelope:" who)))]
        (-> contact-p
         (.then
          (fn [contact-ref]
           (let [req {"yoyakuId" yoyaku-id
                 "calendarDid" did
                 "requesterDid" ""
                 "responderDid" (or (:yotei/owner-did cal) "")
                 "startEpochMin" start
                 "durationMin" minutes
                 ;; The visitor's own submission is the consent, and it is
                 ;; recorded as a reference rather than as the text they
                 ;; typed (G8/G2).
                 "consentRef" (str "self:" (.toISOString (js/Date.)))
                 ;; G2: contact is meant to be an envelope reference. No
                 ;; envelope service is wired, so it is stored under a marker
                 ;; that says so rather than a name implying encryption that
                 ;; did not happen — and the form says so too.
                 "contactRef" contact-ref}]
        (-> (do-call env did "propose"
                     ;; The calendar travels as EDN: it is a Clojure value with
                     ;; namespaced keys and a set, none of which survives JSON.
                     {:cal (pr-str cal) :req req :now now})
            (.then (fn [r]
                     (if (get r "refused")
                       (refuse (get r "reason") 409)
                       (do
                         ;; waitUntil: the 予約 is already stored, so a slow or
                         ;; broken webhook must not delay the response and must
                         ;; not be able to turn a success into a refusal.
                         (when ctx (.waitUntil ^js ctx (notify/notify! cal (get r "entry"))))
                         (html-response
                          (view/proposed-page {:owner-label label
                                             :calendar cal
                                             :start-epoch-min start
                                             :duration-min minutes})
                          {:title "\u7533\u3057\u8fbc\u307f\u3092\u53d7\u3051\u4ed8\u3051\u307e\u3057\u305f \u2014 yotei"}))))))))))))))

(defn handle
  "Route one request. Returns a promise of a Response."
  [request env ctx]
  (let [url (js/URL. (.-url request))
        method (.-method request)
        segs (vec (remove str/blank? (str/split (.-pathname url) #"/")))
        host (or (some-> ^js env .-YOTEI_HOST) "app.itonami.cloud")]
    (cond
      (and (= method "GET") (= ["health"] segs))
      (js/Promise.resolve (json-response {:ok true :actor "yotei" :mount "/yotei"} 200))

      ;; Confirming a 予約. The owner signs
      ;; "yotei/confirm/v1\n<calendar did>\n<yoyaku id>" with their private
      ;; key and posts the signature; the Worker verifies against the public
      ;; key in the calendar. That is G5 exactly: yotei can check a
      ;; confirmation and cannot manufacture one, because it holds no private
      ;; key. Unauthenticated on purpose — the signature *is* the
      ;; authentication, so there is no session to steal and no second
      ;; admission rule to keep in step with this one.
      (and (= method "POST") (= "confirm" (first segs)) (= 2 (count segs)))
      (let [segment (second segs)]
        (if-not (re-matches SEGMENT segment)
          (js/Promise.resolve (json-response {:error "invalid calendar"} 400))
          (let [store (kv/kv-store (.-YOYAKU ^js env))
                did (calendar-did host segment)]
            (-> (js/Promise.all #js [((:calendar store) did) (.json request)])
                (.then
                 (fn [[cal body]]
                   (let [b (js->clj body)
                         yoyaku-id (get b "yoyakuId")
                         signature (get b "signature")
                         pub (:yotei/owner-sig-key cal)]
                     (cond
                       (nil? cal) (json-response {:error "no such calendar"} 404)
                       (nil? pub) (json-response
                                   {:error "this calendar has no signing key — regenerate it with scripts/owner.cljs"}
                                   409)
                       (or (str/blank? (str yoyaku-id)) (str/blank? (str signature)))
                       (json-response {:error "yoyakuId and signature are required"} 400)
                       :else
                       (-> (envelope/verify pub (envelope/confirm-message did yoyaku-id) signature)
                           (.then (fn [ok?]
                                    (if-not ok?
                                      (json-response {:error "signature does not verify"} 401)
                                      (-> (do-call env did "confirm"
                                                   {:yoyakuId yoyaku-id
                                                    ;; The DO records the
                                                    ;; signature reference, and
                                                    ;; `confirm-yoyaku` refuses
                                                    ;; anything whose origin is
                                                    ;; not a member.
                                                    :signature {"origin" "member"
                                                                "ref" signature}})
                                          (.then (fn [r]
                                                   (json-response
                                                    (if (get r "refused")
                                                      {:ok false :reason (get r "reason")}
                                                      {:ok true :yoyaku (get r "entry")})
                                                    (if (get r "refused") 409 200)))))))))))))))))

      ;; Operator-only, and deliberately not part of the 予約 surface: it
      ;; empties a calendar's log. Gated on a secret compared in constant time,
      ;; and it exists because this session created ~35 test 予約 through the
      ;; public page and the Durable Object — correctly — will not give them
      ;; back to anyone who merely asks.
      ;;
      ;; It is not an owner console. When the owner view lands, confirming and
      ;; cancelling belong there behind a member signature (G5); this stays an
      ;; operations tool for the person holding the deploy credentials.
      (and (= method "POST") (= ["admin" "clear"] (take 2 segs)) (= 3 (count segs)))
      (let [secret (some-> ^js env .-YOTEI_ADMIN_TOKEN)
            given (.get (.-headers request) "x-yotei-admin")]
        (if (or (nil? secret) (not (constant-time= secret given)))
          (js/Promise.resolve (json-response {:error "unauthorized"} 401))
          (let [segment (nth segs 2)]
            (if-not (re-matches SEGMENT segment)
              (js/Promise.resolve (json-response {:error "invalid calendar"} 400))
              (-> (do-call env (calendar-did host segment) "clear" {})
                  (.then (fn [r] (json-response {:cleared true :calendar segment
                                                 :result r} 200))))))))

      (and (= "c" (first segs)) (>= (count segs) 2))
      (let [segment (second segs)]
        (if-not (re-matches SEGMENT segment)
          (js/Promise.resolve (json-response {:error "invalid calendar"} 400))
          (let [store (kv/kv-store (.-YOYAKU ^js env))
                did (calendar-did host segment)]
            ;; The calendar *definition* still comes from KV: it is
            ;; configuration, written by the CLI, and read-only here. Only the
            ;; 予約 log needs serializing.
            (-> ((:calendar store) did)
                (.then
                 (fn [cal]
                   (cond
                     (nil? cal)
                     ;; Named, not generic: "this calendar does not exist" and
                     ;; "yotei is down" need different reactions from whoever
                     ;; was sent the link.
                     (json-response {:error "no such calendar" :calendar segment} 404)

                     (and (= method "GET") (= 2 (count segs)))
                     (openings-page env did cal)

                     ;; Both stages POST to this same URL and say which they
                     ;; are with `step`, so the view never has to know where it
                     ;; is mounted. A relative action would have resolved to
                     ;; /yotei/c/select and lost the calendar.
                     (and (= method "POST") (= 2 (count segs)))
                     (-> (form-params request)
                         (.then (fn [params]
                                  (case (get params "step")
                                    "select" (handle-select env did cal params)
                                    "propose" (handle-propose env did cal params ctx)
                                    (js/Promise.resolve
                                     (json-response {:error "unknown step"} 400))))))

                     :else (json-response {:error "not found"} 404))))))))

      :else
      (js/Promise.resolve (json-response {:error "not found"} 404)))))

(def app
  #js {:fetch
       (fn [request env ctx]
         (-> (js/Promise.resolve (handle request env ctx))
             (.then (fn [r] r))
             (.catch (fn [e]
                       ;; The detail is returned rather than swallowed: this
                       ;; Worker is behind a dispatch router that already
                       ;; distinguishes "not deployed" from "failed", and a
                       ;; bare 500 would collapse that distinction again.
                       (json-response {:error "yotei failed" :detail (str e)} 500)))))})

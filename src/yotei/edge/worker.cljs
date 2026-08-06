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
            [yotei.store :as store])
  (:require-macros [yotei.edge.inline :refer [inline-resource]]))

(def dds-css (inline-resource "jp_go_dds/dds.css"))

(defn- html-response
  [body {:keys [status title description]
         :or {status 200 title "yotei"}}]
  (js/Response.
   (page/->page {:title title :description description :lang "ja"
                 :css dds-css
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

(defn- openings-page [store did cal]
  (let [now (now-epoch-min)]
    (-> (kv/confirmed store did)
        (.then (fn [confirmed]
                 (let [os (av/openings cal now (+ now (* 14 1440)) confirmed now)
                       label (or (:yotei/owner-label cal) "この人")]
                   (html-response
                    (view/yoyaku-page {:owner-label label
                                        :purpose (:yotei/purpose cal)
                                        :calendar cal
                                        :openings os})
                    {:title (str label "の予定を押さえる — yotei")
                     :description "空いている時間を選んで申し込めます。"})))))))

(defn- handle-select
  "The chosen time, echoed back as a form to fill in.

  Re-validated rather than trusted: the visitor may have had the page open for
  an hour, and offering a form for a slot that has since gone would collect
  their details and then refuse them."
  [store did cal params]
  (let [now (now-epoch-min)
        start (t/parse-instant (get params "start"))
        minutes (js/parseInt (get params "minutes" "0") 10)]
    (if-not (and start (pos? minutes))
      (js/Promise.resolve
       (html-response (view/refused-page {:reason "時間の指定が読み取れませんでした。"})
                      {:status 400 :title "エラー — yotei"}))
      (-> (kv/confirmed store did)
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
  "Write the proposal.

  The rules are `yotei.store/decide-propose`'s — the same pure function the
  JVM store calls. This reads the log, turns the decision into a page, and
  appends at the version it read at. It decides nothing itself."
  [store did cal params]
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
      (-> ((:log store) did)
          (.then
           (fn [pair]
             (let [entries (first pair)
                   version (second pair)
                   req {"yoyakuId" (str "y-" (js/crypto.randomUUID))
                        "calendarDid" did
                        "requesterDid" ""
                        "responderDid" (or (:yotei/owner-did cal) "")
                        "startEpochMin" start
                        "durationMin" minutes
                        ;; The visitor's own submission is the consent, and it
                        ;; is recorded as a reference rather than as the text
                        ;; they typed (G8/G2).
                        "consentRef" (str "self:" (.toISOString (js/Date.)))
                        ;; G2: contact is an envelope reference. This build has
                        ;; no envelope service wired, so it is stored under a
                        ;; marker that says so rather than under a name that
                        ;; implies encryption that did not happen.
                        "contactRef" (str "unencrypted-pending-envelope:"
                                          (get params "contact"))}
                   decision (store/decide-propose cal did entries req now)]
               (if (= :refuse (:action decision))
                 (js/Promise.resolve (refuse (get (:result decision) "reason") 409))
                 (-> ((:append! store) did (:entry decision) version)
                     (.then (fn [v]
                              (if v
                                (html-response
                                 (view/proposed-page {:owner-label label
                                                      :calendar cal
                                                      :start-epoch-min start
                                                      :duration-min minutes})
                                 {:title "\u7533\u3057\u8fbc\u307f\u3092\u53d7\u3051\u4ed8\u3051\u307e\u3057\u305f \u2014 yotei"})
                                (refuse "\u540c\u6642\u306b\u5225\u306e\u7533\u3057\u8fbc\u307f\u304c\u3042\u308a\u307e\u3057\u305f\u3002\u3082\u3046\u4e00\u5ea6\u304a\u8a66\u3057\u304f\u3060\u3055\u3044\u3002" 409)))))))))))))

(defn handle
  "Route one request. Returns a promise of a Response."
  [request env]
  (let [url (js/URL. (.-url request))
        method (.-method request)
        segs (vec (remove str/blank? (str/split (.-pathname url) #"/")))
        host (or (some-> ^js env .-YOTEI_HOST) "app.itonami.cloud")]
    (cond
      (and (= method "GET") (= ["health"] segs))
      (js/Promise.resolve (json-response {:ok true :actor "yotei" :mount "/yotei"} 200))

      (and (= "c" (first segs)) (>= (count segs) 2))
      (let [segment (second segs)]
        (if-not (re-matches SEGMENT segment)
          (js/Promise.resolve (json-response {:error "invalid calendar"} 400))
          (let [store (kv/kv-store (.-YOYAKU ^js env))
                did (calendar-did host segment)]
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
                     (openings-page store did cal)

                     ;; Both stages POST to this same URL and say which they
                     ;; are with `step`, so the view never has to know where it
                     ;; is mounted. A relative action would have resolved to
                     ;; /yotei/c/select and lost the calendar.
                     (and (= method "POST") (= 2 (count segs)))
                     (-> (form-params request)
                         (.then (fn [params]
                                  (case (get params "step")
                                    "select" (handle-select store did cal params)
                                    "propose" (handle-propose store did cal params)
                                    (js/Promise.resolve
                                     (json-response {:error "unknown step"} 400))))))

                     :else (json-response {:error "not found"} 404))))))))

      :else
      (js/Promise.resolve (json-response {:error "not found"} 404)))))

(def app
  #js {:fetch
       (fn [request env _ctx]
         (-> (js/Promise.resolve (handle request env))
             (.then (fn [r] r))
             (.catch (fn [e]
                       ;; The detail is returned rather than swallowed: this
                       ;; Worker is behind a dispatch router that already
                       ;; distinguishes "not deployed" from "failed", and a
                       ;; bare 500 would collapse that distinction again.
                       (json-response {:error "yotei failed" :detail (str e)} 500)))))})

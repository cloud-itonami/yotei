(ns concurrency-probe
  "Fire N 予約 at one calendar at once and count how many survive.

  Not a load test. It measures one thing: whether an append can be lost. Each
  request takes a *different* slot, so every one of them is legal and every one
  of them must end up in the log. Anything missing was destroyed by a
  read-modify-write racing another — the lost update `yotei.edge.kv` documents.

  Expected before the fix: fewer entries than requests. After: exactly N.

  Run: nbb --classpath src scripts/concurrency_probe.cljs <segment> [n]"
  (:require ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [promesa.core :as p]
            [yotei.time :as t]))

(def host "https://app.itonami.cloud")
(def kv-namespace-id "f1c62eb9ebb3436ea1ab8aaf92e7fee7")

(def args
  ;; Everything after this script's own path. Neither a fixed index nor
  ;; "first non-flag" works: nbb's flags come first and `--classpath src`
  ;; puts a bare `src` in the middle, which both of those happily read as the
  ;; segment. This is the third time an argv offset has bitten in this repo.
  (let [argv (vec (js->clj js/process.argv))
        i (->> (map-indexed vector argv)
               (filter (fn [[_ a]] (str/ends-with? (str a) "concurrency_probe.cljs")))
               ffirst)]
    (if i (subvec argv (inc i)) [])))

(def segment (or (first args) "jun"))
(def n (let [v (js/parseInt (or (second args) "8") 10)]
         (if (js/Number.isNaN v) 8 v)))

(defn- did [seg] (str "did:web:app.itonami.cloud:yotei:calendar:" seg))

(defn- kv [& a]
  (let [r (cp/spawnSync "npx" (clj->js (concat ["wrangler" "kv"] a))
                        #js {:encoding "utf8"})]
    ;; stdout only, and ANSI stripped: on a miss wrangler prints a coloured
    ;; error whose escape sequence begins with `[`, which is exactly what the
    ;; payload scan looks for. Reading that as EDN yields a symbol, and the
    ;; failure then surfaces as ICounted rather than as "the key is absent".
    (str/replace (str (.-stdout r)) #"\u001b\[[0-9;]*m" "")))

(defn- log-entries
  "The stored log, or ::unparseable.

  wrangler prints a banner before the value, and feeding that to the EDN
  reader returns whatever the banner's first token parses as — a symbol, not a
  collection, so the failure surfaces as `ICounted` rather than as a parse
  error. The payload starts at the first `[`."
  []
  (let [out (kv "key" "get" "--namespace-id" kv-namespace-id
                (str "yoyaku-log:" (did segment)) "--remote")
        i (str/index-of (str out) "[")]
    (cond
      (or (str/blank? out) (str/includes? out "not found")
          (str/includes? out "ERROR") (nil? i)) []
      :else (let [v (try (edn/read-string (subs out i)) (catch :default _ ::unparseable))]
              (if (vector? v) v ::unparseable)))))

(defn- wait-for-log
  "Poll the mirror until it stops changing, then return it.

  Not a fixed sleep: KV read-after-write took ~20s the first time this probe
  ran, and a 3s wait read an absent key and reported every 予約 as lost."
  [expect-at-least]
  (p/loop [tries 0 prev -1]
    (let [e (log-entries)
          n (if (vector? e) (count e) -1)]
      (if (or (and (>= n expect-at-least) (= n prev)) (>= tries 15))
        e
        (p/let [_ (p/delay 5000)] (p/recur (inc tries) n))))))

(defn- offered
  "What the page is offering: `{:starts [...] :minutes n}`.

  The duration is read from the page rather than assumed. Hardcoding 30 made
  every request to a 15-minute calendar a legitimate 409, and the probe then
  reported \"no 予約 lost\" — which was true and completely uninformative,
  because none had been accepted."
  []
  (-> (js/fetch (str host "/yotei/c/" segment))
      (.then #(.text %))
      (.then (fn [html]
               {:starts (vec (map second (re-seq #"name=\"start\" value=\"([^\"]+)\"" html)))
                :minutes (or (second (re-find #"name=\"minutes\" value=\"([0-9]+)\"" html))
                             "30")}))))

(defn- propose [start minutes]
  (-> (js/fetch (str host "/yotei/c/" segment)
                #js {:method "POST"
                     :headers #js {"content-type" "application/x-www-form-urlencoded"}
                     :body (str "step=propose&start=" (js/encodeURIComponent start)
                                "&minutes=" (js/encodeURIComponent minutes)
                                "&name=probe&contact=probe@example.com")})
      (.then (fn [r] {:status (.-status r) :start start}))
      (.catch (fn [e] {:status :error :start start :error (str e)}))))

(defn -main []
  ;; No clearing. The Durable Object owns the log now, so deleting the KV
  ;; mirror deletes nothing — it measured one entry MORE than it expected the
  ;; first time, which was a leftover the "clear" had not cleared. A delta is
  ;; the right measurement anyway: it does not destroy anyone's 予約 to run.
  (p/let [before (let [e (log-entries)] (if (vector? e) (count e) 0))
          _ (println "calendar:" segment "  requests:" n "  既存:" before)
          page (offered)
          picked (vec (take n (:starts page)))
          minutes (:minutes page)]
    (if (< (count picked) n)
      (println "空き枠が" (count picked) "しかない — n を下げるか別のカレンダーで")
      (p/let [_ (println "各リクエストは別々の枠を取る（全部通るのが正しい）/" minutes "分枠")
              ;; All at once. Promise.all on fetch means they leave together;
              ;; whether they arrive together is the network's business, which
              ;; is exactly the condition being probed.
              results (js/Promise.all (clj->js (map #(propose % minutes) picked)))
              ;; Poll rather than sleep a fixed interval. KV read-after-write
              ;; took ~20s in the first run of this probe, and a 3s wait read
              ;; an absent key and reported every single 予約 as lost — which
              ;; would have been a much more alarming and much less true
              ;; number than the real one.
              entries (wait-for-log (+ before 1))]
        (let [rs (js->clj results :keywordize-keys true)
              accepted (count (filter #(= 200 (:status %)) rs))
              refused (count (filter #(= 409 (:status %)) rs))
              errored (count (remove #(#{200 409} (:status %)) rs))
              stored (if (= ::unparseable entries) -1 (- (count entries) before))
              stored-starts (when (vector? entries)
                              (set (map #(t/format-instant (get % "startEpochMin")) entries)))
              missing (when stored-starts
                        (remove stored-starts (map :start (filter #(= 200 (:status %)) rs))))]
          (println "\n  HTTP 200 (受理):" accepted)
          (println "  HTTP 409 (拒否):" refused)
          (when (pos? errored) (println "  その他:" errored))
          (println "  ログの増分:" stored "（" before "→" (if (vector? entries) (count entries) "?") "）")
          (if (= accepted stored)
            (println "\n  ✅ 失われた 予約 は無い")
            (do (println "\n  ❌ LOST UPDATE:" (- accepted stored) "件が消えた")
                (doseq [m (take 5 missing)]
                  (println "     消えた枠:" m))
                (set! (.-exitCode js/process) 1))))))))

(-> (-main) (p/catch (fn [e] (println "probe error:" (str e))
                       (set! (.-exitCode js/process) 1))))

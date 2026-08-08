(ns owner
  "The calendar owner's side: see who asked, read their contact, confirm.

  ## Why this is a CLI and not a web console

  Confirming requires a private key (G5: yotei holds none, so only a member
  signature confirms) and reading a contact requires a *decryption* key. A
  passkey can do the first and cannot do the second — it signs
  `authenticatorData || clientDataHash` and has no decrypt operation at all.
  So a browser console would need the owner's raw private key in the browser,
  which is a key-management story this repo has not earned yet.

  Here the key stays in kagi on the owner's machine, is used for one operation,
  and is never sent anywhere. The Worker receives a signature and ciphertext
  stays ciphertext everywhere except in this process.

  A web console is still worth building. It should come with a real answer for
  where the key lives, not before one.

  Usage:
    nbb --classpath src scripts/owner.cljs keygen <segment>
    nbb --classpath src scripts/owner.cljs list <segment>
    nbb --classpath src scripts/owner.cljs confirm <segment> <yoyakuId>
    nbb --classpath src scripts/owner.cljs decline <segment> <yoyakuId>
    nbb --classpath src scripts/owner.cljs watch   <segment> [--approve]"
  (:require ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [promesa.core :as p]
            [yotei.envelope :as envelope]
            [yotei.time :as t]))

(def host "https://app.itonami.cloud")
(def kv-namespace-id "f1c62eb9ebb3436ea1ab8aaf92e7fee7")
(def fleet-root "/Users/junkawasaki/github/com-junkawasaki")
(def kagi-bin (str fleet-root "/orgs/kotoba-lang/kagi/bin/kagi"))

(def args
  (let [argv (vec (js->clj js/process.argv))
        i (->> (map-indexed vector argv)
               (filter (fn [[_ a]] (str/ends-with? (str a) "owner.cljs")))
               ffirst)]
    (if i (subvec argv (inc i)) [])))

(defn- did [seg] (str "did:web:app.itonami.cloud:yotei:calendar:" seg))
(defn- key-name [seg] (str "yotei-owner-" seg))

(defn- run [cmd argv opts]
  (let [r (cp/spawnSync cmd (clj->js argv)
                        (clj->js (merge {:encoding "utf8"} opts)))]
    {:out (str/replace (str (.-stdout r)) #"\[[0-9;]*m" "")
     :err (str (.-stderr r))
     :status (.-status r)}))

(defn- kagi-get [name-]
  (let [{:keys [out status]} (run kagi-bin ["get" name-] {:env (doto (js/Object.assign #js {} js/process.env)
                                                                (aset "FLEET_ROOT" fleet-root))})]
    (when (zero? status) (str/trim out))))

(defn- kagi-add! [name- value]
  (run kagi-bin ["add" name- "-c" "personal"]
       {:input value
        :env (doto (js/Object.assign #js {} js/process.env)
               (aset "FLEET_ROOT" fleet-root))}))

(defn- kv-get [k]
  (let [{:keys [out]} (run "npx" ["wrangler" "kv" "key" "get" "--namespace-id" kv-namespace-id
                                  k "--remote"] {})]
    (when-not (or (str/blank? out) (str/includes? out "ERROR"))
      (let [i (str/index-of out "[")
            j (str/index-of out "#:")
            start (cond (and i j) (min i j) i i j j :else nil)]
        (when start (try (edn/read-string (subs out start)) (catch :default _ nil)))))))

;; ── keygen ───────────────────────────────────────────────────────────────────

(defn- keygen! [seg]
  (p/let [keys- (envelope/generate-keys)]
    (if (kagi-get (key-name seg))
      (do (println "既に" (key-name seg) "があります。")
          (println "上書きすると、この鍵で封をした既存の 予約 の連絡先が**永久に読めなくなります**。")
          (println "本当に作り直すなら kagi で先に削除してください。")
          (set! (.-exitCode js/process) 1))
      (let [{:keys [status err]} (kagi-add! (key-name seg)
                                            (pr-str {:enc (:enc keys-) :sig (:sig keys-)}))]
        (if-not (zero? status)
          (do (println "kagi への保存に失敗:" err) (set! (.-exitCode js/process) 1))
          (do
            (println "秘密鍵を kagi personal/" (key-name seg) " に保存しました。")
            (println "\ncalendars/*.edn の該当エントリに次を足して put し直してください:\n")
            (println (str "  :owner-enc-key " (pr-str (:public (:enc keys-)))))
            (println (str "  :owner-sig-key " (pr-str (:public (:sig keys-)))))
            (println "\n公開鍵だけです。秘密鍵はこの端末から出ません。")))))))

;; ── list ─────────────────────────────────────────────────────────────────────

(defn- fold-current [entries]
  (->> entries (reduce (fn [acc e] (assoc acc (get e "yoyakuId") e)) {}) vals
       (sort-by #(get % "startEpochMin"))))

(defn- decode-who
  "The envelope's plaintext as `{:name .. :contact ..}`.

  Envelopes written before the name moved inside hold a bare contact string,
  so a value that is not JSON is treated as the contact — old 予約 stay
  readable instead of rendering as an error."
  [plain]
  (let [t (try (js->clj (js/JSON.parse plain)) (catch :default _ nil))]
    (if (map? t)
      {:name (get t "name") :contact (get t "contact")}
      {:name nil :contact plain})))

(defn- list! [seg]
  (p/let [cal (kv-get (str "calendar:" (did seg)))
          entries (or (kv-get (str "yoyaku-log:" (did seg))) [])
          secret (some-> (kagi-get (key-name seg)) edn/read-string)
          offset (or (:yotei/tz-offset-min cal) 540)
          current (fold-current entries)]
    (if (empty? current)
      (println "予約 はまだありません。")
      (p/let [rows (p/all
                    (for [e current]
                      (let [ref- (get e "contactRef")]
                        (p/let [plain (cond
                                        (and (envelope/sealed? ref-) secret)
                                        (-> (envelope/open (:private (:enc secret)) ref-
                                                           (get e "yoyakuId"))
                                            (.catch (fn [_] nil)))
                                        (envelope/sealed? ref-) nil
                                        :else (str/replace ref- #"^unencrypted-pending-envelope:" ""))]
                          (assoc e ::who
                                 (cond
                                   (some? plain) (decode-who plain)
                                   (envelope/sealed? ref-)
                                   {:name nil :contact (if secret "（復号できません）"
                                                           "（封のまま — 鍵がありません）")}
                                   :else {:name nil :contact "（不明）"}))))))]
        (println (str seg " — " (count rows) " 件\n"))
        (doseq [e rows]
          (let [start (get e "startEpochMin")
                local (t/format-instant (+ start offset))]
            (println (str "  " (get e "status")
                          "  " (subs local 0 10) " " (subs local 11 16)
                          "〜" (subs (t/format-instant (+ start offset (get e "durationMin"))) 11 16)
                          "  " (get e "yoyakuId")))
            (println (str "      " (or (:name (::who e)) "（名前なし）")
                          "  /  " (:contact (::who e))))))
        (println "\n確定: nbb --classpath src scripts/owner.cljs confirm" seg "<yoyakuId>")))))

;; ── confirm ──────────────────────────────────────────────────────────────────

(defn- confirm! [seg yoyaku-id]
  (p/let [secret (some-> (kagi-get (key-name seg)) edn/read-string)]
    (if-not secret
      (do (println "kagi に" (key-name seg) "がありません。先に keygen してください。")
          (set! (.-exitCode js/process) 1))
      (p/let [msg (envelope/confirm-message (did seg) yoyaku-id)
              signature (envelope/sign (:private (:sig secret)) msg)
              res (js/fetch (str host "/yotei/confirm/" seg)
                            #js {:method "POST"
                                 :headers #js {"content-type" "application/json"}
                                 :body (js/JSON.stringify
                                        (clj->js {:yoyakuId yoyaku-id :signature signature}))})
              body (.json res)]
        (let [b (js->clj body)]
          (if (get b "ok")
            (println "確定しました:" yoyaku-id)
            (do (println "確定できませんでした [" (.-status res) "]:"
                         (or (get b "reason") (get b "error")))
                (set! (.-exitCode js/process) 1))))))))

(defn- cancel!
  "Decline a proposal.

  Uses the same route a visitor's own link uses. That is not a shortcut: the
  route already refuses anything that is not `proposed`, and the 予約 id is
  the capability in both directions. An owner-only cancel endpoint would be a
  second admission rule saying the same thing, and the two would drift.

  Declining a *confirmed* 予約 is deliberately not possible here — that is an
  agreement with somebody else, and unpicking it is a conversation."
  [seg yoyaku-id]
  (p/let [res (js/fetch (str host "/yotei/c/" seg "/y/" yoyaku-id)
                        #js {:method "POST"
                             :headers #js {"content-type" "application/x-www-form-urlencoded"}
                             :body "step=cancel"})]
    (if (.-ok res)
      (println "  → 却下しました:" yoyaku-id)
      (do (println "  → 却下できませんでした [" (.-status res) "]")
          (set! (.-exitCode js/process) 1)))))

(defn- ask-approval!
  "Show what is being approved and get an explicit yes or no.

  The consent-screen shape, and the parts that matter are the ones
  `cloud-itonami-app`'s `passkey/start-authorization!` spells out for the
  stronger version of this: **what the human is shown must be what is acted
  on**, and the two must be bound together rather than the second being
  re-supplied afterwards. So the 予約 id is displayed and the decided-upon id
  is the one carried into `confirm!` — there is no second lookup in between
  that could return a different 予約.

  A modal, not a banner. Everywhere else in this repo a notification is
  deliberately non-focus-stealing, because this machine runs many sessions
  competing for the keyboard. An approval is the exception: a prompt nobody
  notices is a prompt that gets ignored, and the whole point here is that
  nothing is confirmed without a person deciding.

  Returns :approve, :decline or :later."
  [{:keys [title lines]}]
  (let [body (str/join "\n" lines)
        script (str "display dialog " (pr-str body)
                    " with title " (pr-str title)
                    " buttons {\"あとで\", \"却下\", \"承認\"}"
                    " default button \"承認\""
                    " with icon note")
        {:keys [out status]} (run "osascript" ["-e" script] {})]
    (cond
      (not (zero? status)) :later          ; dismissed, or no GUI session
      (str/includes? out "承認") :approve
      (str/includes? out "却下") :decline
      :else :later)))

(defn- notify-desktop! [title body]
  ;; `display notification`, not `display dialog`: a banner does not take
  ;; focus. This machine runs many concurrent sessions competing for it, and a
  ;; modal would steal the keyboard from whichever one had it.
  (run "osascript" ["-e" (str "display notification " (pr-str body)
                              " with title " (pr-str title))] {}))

(defn- watch!
  "Poll a calendar and announce 予約 as they arrive.

  With `--approve`, each new proposal is put to the owner as a consent dialog
  rather than a banner: what it shows is what it acts on, and nothing is
  confirmed without a person deciding. Answering \"あとで\" leaves the 予約
  proposed and asks again next round — a deferral is not a decision, and
  treating it as one would silently drop the request.

  The owner's side of the loop with no webhook and no endpoint to run. The
  Worker's webhook is for sending somewhere else; this is for sitting on the
  owner's machine, which is where the decryption key already is — so unlike a
  webhook it can show who asked *and* their contact, without either leaving
  the machine."
  [seg approve?]
  (p/loop [seen #{} first-pass? true]
    (p/let [entries (or (kv-get (str "yoyaku-log:" (did seg))) [])
            secret (some-> (kagi-get (key-name seg)) edn/read-string)
            cal (kv-get (str "calendar:" (did seg)))
            offset (or (:yotei/tz-offset-min cal) 540)
            current (fold-current entries)
            fresh (remove #(contains? seen (str (get % "yoyakuId") "/" (get % "status")))
                          current)]
      (when (and (seq fresh) (not first-pass?))
        (doseq [e fresh]
          (p/let [ref- (get e "contactRef")
                  plain (if (and (envelope/sealed? ref-) secret)
                          (-> (envelope/open (:private (:enc secret)) ref- (get e "yoyakuId"))
                              (.catch (fn [_] nil)))
                          (when-not (envelope/sealed? ref-)
                            (str/replace (str ref-) #"^unencrypted-pending-envelope:" "")))
                  local (t/format-instant (+ (get e "startEpochMin") offset))
                  ;; Decrypted here and nowhere else. The webhook deliberately
                  ;; carries no identity; this runs where the key is.
                  who (or (:name (decode-who (or plain ""))) "（名前なし）")
                  when- (str (subs local 0 10) " " (subs local 11 16))]
            (println (str "\n● " (get e "status") "  " when- "  " who
                          "\n  " (get e "yoyakuId")))
            (if-not (and approve? (= "proposed" (get e "status")))
              (notify-desktop! (str "yotei — " (or (:yotei/name cal) seg))
                               (str who " / " when-))
              ;; The consent step. What is shown and what is acted on are the
              ;; same 予約 — the id below is the one carried into confirm!,
              ;; with no second lookup that could return a different one.
              (p/let [contact (:contact (decode-who (or plain "")))
                      answer (ask-approval!
                              {:title (str "yotei — " (or (:yotei/name cal) seg))
                               :lines [(str "申し込みがありました。")
                                       ""
                                       (str "  日時: " when-)
                                       (str "  お名前: " who)
                                       (str "  連絡先: " contact)
                                       ""
                                       (str "  ID: " (get e "yoyakuId"))
                                       ""
                                       "承認するとこの時間が確定し、他の人は取れなくなります。"]})]
                (case answer
                  :approve (p/let [_ (confirm! seg (get e "yoyakuId"))] nil)
                  :decline (p/let [_ (cancel! seg (get e "yoyakuId"))] nil)
                  (println "  → あとで（次の巡回でまた尋ねます）")))))))
      (when first-pass?
        (println (str "watch " seg " — 既存 " (count current) " 件。新着を待ちます（Ctrl-C で終了）")))
      (p/let [_ (p/delay 30000)]
        (p/recur (into #{} (map #(str (get % "yoyakuId") "/" (get % "status")) current))
                 false)))))

(defn -main []
  (let [[cmd a b] args]
    (case cmd
      "keygen" (keygen! a)
      "list" (list! a)
      "confirm" (confirm! a b)
      "decline" (cancel! a b)
      "watch" (watch! a (boolean (some #{"--approve"} args)))
      (println "usage: nbb --classpath src scripts/owner.cljs (keygen | list | confirm | decline | watch [--approve]) <segment> [yoyakuId]"))))

(-> (p/resolved (-main))
    (p/catch (fn [e] (println "error:" (str e)) (set! (.-exitCode js/process) 1))))

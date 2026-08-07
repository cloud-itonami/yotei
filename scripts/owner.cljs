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
    nbb --classpath src scripts/owner.cljs confirm <segment> <yoyakuId>"
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
                        (p/let [contact (cond
                                          (and (envelope/sealed? ref-) secret)
                                          (-> (envelope/open (:private (:enc secret)) ref-
                                                             (get e "yoyakuId"))
                                              (.catch (fn [_] "（復号できません）")))
                                          (envelope/sealed? ref-) "（封のまま — 鍵がありません）"
                                          :else (str "（平文・封筒以前）"
                                                     (str/replace ref- #"^unencrypted-pending-envelope:" "")))]
                          (assoc e ::contact contact)))))]
        (println (str seg " — " (count rows) " 件\n"))
        (doseq [e rows]
          (let [start (get e "startEpochMin")
                local (t/format-instant (+ start offset))]
            (println (str "  " (get e "status")
                          "  " (subs local 0 10) " " (subs local 11 16)
                          "〜" (subs (t/format-instant (+ start offset (get e "durationMin"))) 11 16)
                          "  " (get e "yoyakuId")))
            (println (str "      連絡先: " (::contact e)))))
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

(defn -main []
  (let [[cmd a b] args]
    (case cmd
      "keygen" (keygen! a)
      "list" (list! a)
      "confirm" (confirm! a b)
      (println "usage: nbb --classpath src scripts/owner.cljs (keygen | list | confirm) <segment> [yoyakuId]"))))

(-> (p/resolved (-main))
    (p/catch (fn [e] (println "error:" (str e)) (set! (.-exitCode js/process) 1))))

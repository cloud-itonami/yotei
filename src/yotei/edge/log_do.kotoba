(ns yotei.edge.log-do
  "One Durable Object per calendar: the single writer its 予約 log never had.

  ## What this replaces, and why it had to be replaced

  KV has no atomic compare-and-set. `yotei.edge.kv` gated every write on the
  version it read at, which narrows the race without closing it, and the
  measurement was not close: firing eight 予約 at one live calendar, each for a
  different slot so every one was legal, **eight were accepted and two were
  stored**. Six people would have been told 申し込みを受け付けました and had
  their 予約 silently deleted by somebody else's read-modify-write.

  A Durable Object is globally unique per name and single-threaded. 'There is
  exactly one writer' stops being something to implement — no lease, no fencing
  epoch, no retry loop — and becomes a property of where the code runs. This is
  the shape CLAUDE.md prescribes for precisely this problem: use the DO as the
  serializer.

  ## Storage: authoritative here, mirrored to KV

  `ctx.storage` is strongly consistent and transactional, so it holds the
  authority. But per-object storage is private to the object, and if it were
  the only copy the 予約 面 would be split into one island per calendar —
  CLAUDE.md's other rule, and the reason DO storage is not simply 'the answer'.

  So every append also writes the KV mirror. KV is then a *projection*: delete
  it and nothing is lost, because the DO rebuilds it. That is the test which
  decides whether a store is a premise or a cache, and this one is a cache.

  The mirror write is deliberately not awaited before responding. It cannot
  fail the append — the append already happened, durably — and blocking on it
  would trade the correctness this object exists for against latency it does
  not owe anyone.

  ## It holds no rules

  Every decision is still `yotei.store/decide-propose` and `decide-confirm`,
  the same pure functions the JVM store calls. This object contributes
  serialization and nothing else. If a rule about who may take which slot ever
  appears here, it has escaped the layer that is tested."
  (:require [clojure.edn :as edn]
            [shadow.cljs.modern :refer [defclass]]
            [yotei.store :as store]
            [yotei.yoyaku :as yoyaku]))

(def ^:private LOG-KEY "log")

(defn- kv-key [did] (str "yoyaku-log:" did))

(defn- json [obj status]
  (js/Response. (js/JSON.stringify (clj->js obj))
                #js {:status status
                     :headers #js {"content-type" "application/json"}}))

(defclass YoyakuLog
  (field ctx)
  (field env)

  (constructor [this c e]
    (set! ctx c)
    (set! env e))

  Object
  ;; Every method below runs to completion before the next request is
  ;; dispatched. That single fact is the entire fix.

  (readLog [_this]
    (-> (.get (.-storage ctx) LOG-KEY)
        (.then (fn [s] (if s (edn/read-string s) [])))))

  (writeLog [_this did entries]
    (-> (.put (.-storage ctx) LOG-KEY (pr-str entries))
        (.then (fn [_]
                 ;; Mirror, not persist. The append is already durable above;
                 ;; this keeps the 予約 面 queryable outside this object.
                 ;; waitUntil so a slow KV write cannot delay the response, and
                 ;; a failed one cannot undo an append that has happened.
                 (.waitUntil ctx (.put (.-YOYAKU ^js env) (kv-key did) (pr-str entries)))
                 entries))))

  (fetch [this request]
    (let [url (js/URL. (.-url request))
          op (.get (.-searchParams url) "op")
          did (.get (.-searchParams url) "did")]
      (case op
        ;; Reads go through the object too, so a caller cannot observe a KV
        ;; replica that is behind this object's own writes.
        "read"
        (-> (.readLog this)
            (.then (fn [entries] (json {:entries entries :count (count entries)} 200))))

        ;; Bodies are read WITHOUT :keywordize-keys. The 予約 wire shape is
        ;; string-keyed ("yoyakuId", "startEpochMin"), and keywordizing would
        ;; quietly rename every field before `decide-propose` looked at it.
        "propose"
        (-> (.json request)
            (.then (fn [body]
                     (let [b (js->clj body)
                           cal (edn/read-string (get b "cal"))
                           req (get b "req")
                           now (get b "now")]
                       (-> (.readLog this)
                           (.then (fn [entries]
                                    (let [d (store/decide-propose cal did entries req now)]
                                      (if (= :refuse (:action d))
                                        (json {:refused true
                                               :reason (get (:result d) "reason")} 200)
                                        (-> (.writeLog this did (conj entries (:entry d)))
                                            (.then (fn [_]
                                                     (json {:refused false
                                                            :entry (:entry d)} 200))))))))))))) 

        "confirm"
        (-> (.json request)
            (.then (fn [body]
                     (let [b (js->clj body)
                           yoyaku-id (get b "yoyakuId")
                           signature (get b "signature")]
                       (-> (.readLog this)
                           (.then (fn [entries]
                                    (let [d (store/decide-confirm entries yoyaku-id signature)]
                                      (if (= :refuse (:action d))
                                        (json {:refused true
                                               :reason (get (:result d) "reason")} 200)
                                        (-> (.writeLog this did (conj entries (:entry d)))
                                            (.then (fn [_]
                                                     (json {:refused false
                                                            :entry (:entry d)} 200)))))))))))))

        "cancel"
        (-> (.json request)
            (.then (fn [body]
                     (let [yid (get (js->clj body) "yoyakuId")]
                       (-> (.readLog this)
                           (.then (fn [entries]
                                    (let [target (->> entries
                                                      (reduce (fn [acc e]
                                                                (assoc acc (get e "yoyakuId") e)) {})
                                                      (#(get % yid)))]
                                      (if (nil? target)
                                        (json {:refused true :reason "no such 予約"} 200)
                                        ;; Appended, not mutated: the log keeps
                                        ;; that it was proposed before it was
                                        ;; cancelled (G3).
                                        (-> (.writeLog this did
                                                       (conj entries
                                                             (yoyaku/cancel-yoyaku target)))
                                            (.then (fn [_] (json {:refused false} 200)))))))))))))

        "clear"
        (-> (.delete (.-storage ctx) LOG-KEY)
            (.then (fn [_]
                     (.waitUntil ctx (.delete (.-YOYAKU ^js env) (kv-key did)))
                     (json {:cleared true} 200))))

        (js/Promise.resolve (json {:error "unknown op" :op op} 400))))))

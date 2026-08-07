(ns envelope-test
  "Round-trips and refusals for the envelope, run on nbb's WebCrypto.

  Not in test/ with the JVM suite: `yotei.envelope` is `.cljs` because it needs
  WebCrypto, which the JVM does not have. Running it here proves it works in
  the runtime the CLI uses, and the Worker uses the same API.

  Run: nbb --classpath src scripts/envelope_test.cljs"
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [yotei.envelope :as env]))

(def failures (atom []))

(defn check! [label ok?]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures conj label) (println "  FAIL" label))))

(defn- rejects?
  "True if `pf` rejects. A resolved promise here is the failure — silently
  returning a wrong plaintext is the outcome these tests exist to forbid."
  [pf]
  (-> (pf) (.then (fn [_] false)) (.catch (fn [_] true))))

(defn -main []
  (p/let [{:keys [enc sig]} (env/generate-keys)
          other (env/generate-keys)
          aad "y-abc123"

          ;; ── seal / open ──
          sealed (env/seal (:public enc) "jun@example.com" aad)
          _ (check! "sealed value announces its version"
                    (str/starts-with? sealed env/prefix))
          _ (check! "sealed?" (env/sealed? sealed))
          _ (check! "plaintext is not in the envelope"
                    (not (str/includes? sealed "jun@example.com")))
          opened (env/open (:private enc) sealed aad)
          _ (check! "round-trips" (= "jun@example.com" opened))

          ;; ── the refusals that matter ──
          wrong-key (rejects? #(env/open (:private (:enc other)) sealed aad))
          _ (check! "another owner's key cannot open it" wrong-key)

          wrong-aad (rejects? #(env/open (:private enc) sealed "y-different"))
          _ (check! "an envelope cannot be moved to another 予約 (AAD)" wrong-aad)

          ;; Flip one character inside the base64 payload, leaving the
          ;; structure intact, so GCM's tag is what refuses rather than the
          ;; JSON parser.
          body (subs sealed (count env/prefix))
          mid (quot (count body) 2)
          flipped (str env/prefix (subs body 0 mid)
                       (if (= \A (nth body mid)) "B" "A")
                       (subs body (inc mid)))
          bad-ct (rejects? #(env/open (:private enc) flipped aad))
          _ (check! "tampered ciphertext is refused, not decoded" bad-ct)

          truncated (str env/prefix (subs body 4))
          bad-shape (rejects? #(env/open (:private enc) truncated aad))
          _ (check! "a malformed envelope rejects (not throws past .catch)" bad-shape)

          plain (rejects? #(env/open (:private enc) "unencrypted-pending-envelope:x" aad))
          _ (check! "a pre-envelope legacy value is refused by name" plain)
          _ (check! "and is not mistaken for sealed"
                    (not (env/sealed? "unencrypted-pending-envelope:x")))

          ;; ── two seals of the same text differ (ephemeral key per 予約) ──
          s2 (env/seal (:public enc) "jun@example.com" aad)
          _ (check! "two seals of the same text are different ciphertexts"
                    (not= sealed s2))

          ;; ── signatures (G5) ──
          ;;
          ;; Each verify is BOUND, not inlined into check!. `verify` returns a
          ;; promise, and a promise is truthy: `(not <promise>)` is false, so
          ;; the negative checks all failed and — worse — the positive one
          ;; passed without verifying anything.
          msg (env/confirm-message "did:web:x:yotei:calendar:jun" "y-abc123")
          signature (env/sign (:private sig) msg)
          v-ok (env/verify (:public sig) msg signature)
          _ (check! "owner signature verifies" (true? v-ok))

          v-other-msg (env/verify (:public sig)
                                  (env/confirm-message "did:web:x:yotei:calendar:jun" "y-other")
                                  signature)
          _ (check! "a different 予約 id does not verify" (false? v-other-msg))

          v-other-cal (env/verify (:public sig)
                                  (env/confirm-message "did:web:x:yotei:calendar:other" "y-abc123")
                                  signature)
          _ (check! "another calendar's confirm does not verify" (false? v-other-cal))

          v-other-key (env/verify (:public (:sig other)) msg signature)
          _ (check! "another owner's key does not verify" (false? v-other-key))

          v-garbage (env/verify (:public sig) msg "bm90LWEtc2ln")
          _ (check! "garbage signature does not verify" (false? v-garbage))]
    (println "\n────────────")
    (if (empty? @failures)
      (println "全て合格")
      (do (println "失敗:" (str/join ", " @failures))
          (set! (.-exitCode js/process) 1)))))

(-> (-main) (p/catch (fn [e] (println "harness error:" (str e))
                       (set! (.-exitCode js/process) 1))))

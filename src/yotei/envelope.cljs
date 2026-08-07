(ns yotei.envelope
  "Sealing a visitor's contact so only the calendar owner can read it.

  G2 says the person making a 予約 is carried as an encrypted envelope, never
  as a profile attribute. Until now it was carried as
  `unencrypted-pending-envelope:<plaintext>` — a marker chosen so the state
  could not be mistaken, and the form was changed to stop claiming otherwise.
  This is the envelope.

  ## Who can open it, and who cannot

  The calendar publishes an **encryption public key**. The Worker seals to it
  and immediately forgets the plaintext. yotei holds no private key — the same
  property G5 asserts about signatures — so a compromised Worker, a leaked KV
  dump and a subpoenaed Durable Object all yield ciphertext.

  The owner's private key lives in kagi and is used by `scripts/owner.cljs` on
  their machine. It is deliberately **not** a passkey: a passkey signs and
  cannot decrypt, which is exactly the split that made cloud-itonami-app's own
  note say a Passkey 'cannot produce a Data Integrity proof'. Confirmation
  could use one; reading a contact cannot.

  ## The construction

  ECDH P-256 to a fresh ephemeral keypair, HKDF-SHA-256 to an AES-256-GCM key,
  and the ephemeral public key travels with the ciphertext. Standard ECIES
  shape, all of it in WebCrypto, which both a Worker and nbb have — so the
  sealing side and the opening side are the same code rather than two
  implementations that must agree.

  Forward secrecy per 予約 falls out of the ephemeral key: recovering one
  visitor's contact does not help with the next one's.

  The 予約 id is bound in as additional authenticated data, so an envelope
  cannot be lifted from one 予約 and pasted onto another — the ciphertext
  would still decrypt to the right bytes, but GCM refuses because the AAD no
  longer matches."
  (:require [clojure.string :as str]))

(def ^:private subtle (.-subtle js/crypto))
(def ^:private ec #js {:name "ECDH" :namedCurve "P-256"})
(def ^:private ecdsa #js {:name "ECDSA" :namedCurve "P-256"})

(defn- b64 [buf]
  (let [bytes (js/Uint8Array. buf)]
    (js/btoa (.apply js/String.fromCharCode nil bytes))))

(defn- unb64 [s]
  (let [bin (js/atob s)
        arr (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset arr i (.charCodeAt bin i)))
    arr))

(defn- utf8 [s] (.encode (js/TextEncoder.) s))
(defn- from-utf8 [buf] (.decode (js/TextDecoder.) buf))

;; ── keys ─────────────────────────────────────────────────────────────────────

(defn generate-keys
  "A fresh owner key set: one for sealing, one for signing.

  Two keypairs and not one because WebCrypto binds a key to an algorithm and
  a usage — an ECDH key cannot sign and an ECDSA key cannot derive. Reusing
  one key across both would also be poor practice independently of the API.

  Returns `{:enc {:public jwk :private jwk} :sig {:public jwk :private jwk}}`."
  []
  (-> (js/Promise.all
       #js [(.generateKey subtle ec true #js ["deriveKey" "deriveBits"])
            (.generateKey subtle ecdsa true #js ["sign" "verify"])])
      (.then (fn [[enc sig]]
               (js/Promise.all
                #js [(.exportKey subtle "jwk" (.-publicKey enc))
                     (.exportKey subtle "jwk" (.-privateKey enc))
                     (.exportKey subtle "jwk" (.-publicKey sig))
                     (.exportKey subtle "jwk" (.-privateKey sig))])))
      (.then (fn [[ep esk sp ssk]]
               {:enc {:public (js->clj ep) :private (js->clj esk)}
                :sig {:public (js->clj sp) :private (js->clj ssk)}}))))

(defn- import-jwk [jwk alg usages]
  (.importKey subtle "jwk" (clj->js jwk) alg true (clj->js usages)))

;; ── seal / open ──────────────────────────────────────────────────────────────

(def prefix
  "How a sealed envelope announces itself.

  Versioned, because the day this construction is replaced there will be
  envelopes in the log made with the old one, and `open` has to know which is
  which without guessing."
  "cloud.itonami.encrypted.v1:")

(defn sealed?
  "Whether a `contactRef` is actually an envelope.

  Callers must not assume: the log holds entries written before this existed,
  under `unencrypted-pending-envelope:`, and treating one of those as
  ciphertext would either throw or — worse — silently hand back nonsense."
  [s]
  (str/starts-with? (str s) prefix))

(defn seal
  "Seal `plaintext` to the owner's encryption public key.

  `aad` binds the envelope to something (the 予約 id), so it cannot be moved
  to another 予約."
  [owner-public-jwk plaintext aad]
  (-> (js/Promise.all
       #js [(import-jwk owner-public-jwk ec [])
            (.generateKey subtle ec true #js ["deriveKey" "deriveBits"])])
      (.then (fn [[owner-pub eph]]
               (-> (.deriveBits subtle
                                #js {:name "ECDH" :public owner-pub}
                                (.-privateKey eph)
                                256)
                   (.then (fn [shared]
                            (js/Promise.all
                             #js [(.exportKey subtle "jwk" (.-publicKey eph))
                                  (.importKey subtle "raw" shared #js {:name "HKDF"}
                                              false #js ["deriveKey"])])))
                   (.then (fn [[eph-jwk hkdf]]
                            (-> (.deriveKey subtle
                                            #js {:name "HKDF" :hash "SHA-256"
                                                 :salt (utf8 prefix)
                                                 :info (utf8 (str aad))}
                                            hkdf
                                            #js {:name "AES-GCM" :length 256}
                                            false #js ["encrypt"])
                                (.then (fn [k]
                                         (let [iv (.getRandomValues js/crypto (js/Uint8Array. 12))]
                                           (-> (.encrypt subtle
                                                         #js {:name "AES-GCM" :iv iv
                                                              :additionalData (utf8 (str aad))}
                                                         k (utf8 plaintext))
                                               (.then (fn [ct]
                                                        (str prefix
                                                             (js/btoa
                                                              (js/JSON.stringify
                                                               (clj->js {:epk eph-jwk
                                                                         :iv (b64 iv)
                                                                         :ct (b64 ct)}))))))))))))))))))

(defn open
  "Open an envelope with the owner's encryption private key.

  Rejects rather than returning nil on a bad key or tampered ciphertext: GCM
  authenticates, and a caller that got a value back would have no way to know
  whether it was the value that was sealed."
  [owner-private-jwk envelope aad]
  (if-not (sealed? envelope)
    (js/Promise.reject (js/Error. (str "not a v1 envelope: " (subs (str envelope) 0 32))))
    ;; Parsed inside a try that turns a throw into a rejection. It used to
    ;; throw synchronously, so a caller writing `(.catch (open ...))` would not
    ;; catch a malformed envelope at all — the exception escaped past the
    ;; promise entirely. A corrupt envelope is exactly the case that handler
    ;; exists for.
    (let [payload (try (js->clj (js/JSON.parse (js/atob (subs envelope (count prefix)))))
                       (catch :default _ ::malformed))]
      (if (= ::malformed payload)
        (js/Promise.reject (js/Error. "envelope is not readable"))
      (-> (js/Promise.all
           #js [(import-jwk owner-private-jwk ec ["deriveKey" "deriveBits"])
                (import-jwk (get payload "epk") ec [])])
          (.then (fn [[priv epk]]
                   (.deriveBits subtle #js {:name "ECDH" :public epk} priv 256)))
          (.then (fn [shared]
                   (.importKey subtle "raw" shared #js {:name "HKDF"} false #js ["deriveKey"])))
          (.then (fn [hkdf]
                   (.deriveKey subtle
                               #js {:name "HKDF" :hash "SHA-256"
                                    :salt (utf8 prefix)
                                    :info (utf8 (str aad))}
                               hkdf
                               #js {:name "AES-GCM" :length 256}
                               false #js ["decrypt"])))
          (.then (fn [k]
                   (.decrypt subtle
                             #js {:name "AES-GCM"
                                  :iv (unb64 (get payload "iv"))
                                  :additionalData (utf8 (str aad))}
                             k (unb64 (get payload "ct")))))
          (.then from-utf8))))))

;; ── signatures (G5) ──────────────────────────────────────────────────────────

(defn sign
  "Sign `message` with the owner's signing private key. Base64."
  [owner-private-jwk message]
  (-> (import-jwk owner-private-jwk ecdsa ["sign"])
      (.then (fn [k]
               (.sign subtle #js {:name "ECDSA" :hash "SHA-256"} k (utf8 message))))
      (.then b64)))

(defn verify
  "Whether `signature` is the owner's over `message`.

  This is what makes a confirmation member-signed rather than server-asserted:
  the Worker can check it and cannot produce it."
  [owner-public-jwk message signature]
  (-> (import-jwk owner-public-jwk ecdsa ["verify"])
      (.then (fn [k]
               (.verify subtle #js {:name "ECDSA" :hash "SHA-256"} k
                        (unb64 signature) (utf8 message))))
      (.catch (fn [_] false))))

(defn confirm-message
  "Exactly what a confirmation signature covers.

  The calendar and the 予約 id together: signing the id alone would let a
  signature meant for one calendar confirm a same-named 予約 on another."
  [calendar-did yoyaku-id]
  (str "yotei/confirm/v1\n" calendar-did "\n" yoyaku-id))

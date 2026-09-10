(ns yotei.edge.notify
  "Telling the owner a 予約 arrived.

  Until now nothing did. Somebody could take a slot and the owner would not
  know until they ran `owner.cljs list` — a 予約 page that quietly accumulates
  appointments nobody is told about is worse than one that is honestly closed.

  ## What is sent, and what is not

  **Neither the name nor the contact.** The first version of this sent the
  name, reasoning that a notification without it cannot be acted on. That was
  wrong: a name is booking PII exactly as much as an address is, and G2 has no
  carve-out for the convenient half. Sending it would have handed it to
  whatever service the webhook URL belongs to, in the clear, defeating the
  envelope two namespaces over.

  So the notification carries **when**, and an id. Who is inside the envelope,
  and `owner.cljs list` opens it on the owner's own machine — which is the one
  place the key exists, and therefore the one place identity should appear.

  ## It cannot fail the 予約

  Called inside `waitUntil`, and every error is swallowed after being logged.
  A 予約 that was accepted and stored has happened; a notification that did
  not send is a smaller problem than a visitor being told 'this time is no
  longer available' because somebody's webhook was down.

  ## Why a webhook and not email

  Email is the obvious channel and would need a Resend key. The one the
  secrets map documents at `op://gftdcojp/gftd.resend/credential` is **not in
  that vault** (checked 2026-08-07; 1Password answered, so this is absence and
  not a timeout), and it is not in kagi or the Keychain under the documented
  names either. Rather than block, this takes a URL — which the owner can
  point at anything, and which needs no credential yotei has to hold.

  When the key surfaces, email becomes another branch here and the calendar
  gains `:yotei/notify-email`. The seam is already the right shape."
  (:require [yotei.time :as t]))

(defn payload
  "The notification body. Metadata plus the name; never the contact."
  [cal entry]
  (let [offset (or (:yotei/tz-offset-min cal) 540)
        start (get entry "startEpochMin")
        local (t/format-instant (+ start offset))]
    {:event "yoyaku.proposed"
     :calendar (:yotei/name cal)
     :calendarDid (:yotei/calendar-did cal)
     :yoyakuId (get entry "yoyakuId")
     :startsAt (t/format-instant start)
     :startsAtLocal (str (subs local 0 10) " " (subs local 11 16))
     :durationMin (get entry "durationMin")
     :status (get entry "status")
     ;; Said explicitly so a reader does not go looking for a name or contact
     ;; field and conclude it was forgotten.
     :who "sealed — open with: nbb --classpath src scripts/owner.cljs list <segment>"}))

(defn notify!
  "POST the notification, if the calendar has somewhere to send it.

  Returns a promise that always resolves. The caller passes it to `waitUntil`."
  [cal entry]
  (if-let [url (:yotei/notify-webhook cal)]
    (-> (js/fetch url
                  #js {:method "POST"
                       :headers #js {"content-type" "application/json"}
                       :body (js/JSON.stringify (clj->js (payload cal entry)))})
        (.then (fn [r]
                 (when-not (.-ok r)
                   (js/console.log "yotei notify: webhook returned" (.-status r)))
                 true))
        (.catch (fn [e]
                  (js/console.log "yotei notify: failed" (str e))
                  true)))
    (js/Promise.resolve false)))

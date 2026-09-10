(ns yotei.schedule-test
  "Appointments: making one, asking people to it, and answering.

  Ported from `cloud.itonami.app.scheduler-test` when the domain moved here.
  The assertions are the same ones that suite made — that is the point of
  porting them rather than rewriting: the consolidation is only safe if the
  behaviour it moves is the behaviour that arrives.

  What changed is the shape, not the claims. There is no store and no
  `use-fixtures` resetting one; each function takes the calendars map and
  returns the next, so the state under test is a local binding."
  (:require [clojure.test :refer [deftest is testing]]
            [yotei.schedule :as schedule]))

(def ^:private alice "person-alice")
(def ^:private bob "person-bob")
(def ^:private carol "person-carol")

(def ^:private morning
  {:title "四半期の打ち合わせ"
   :start "2026-08-03T09:00:00Z" :end "2026-08-03T10:00:00Z"})

(defn- ex-type [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                 (:type (ex-data e)))))

(deftest an-appointment-is-made-and-seen-by-the-people-on-it
  (let [{:keys [calendars result]} (schedule/create {} "evt-1" (assoc morning :attendees [bob]) alice)
        event (:event result)]
    (is (= "organizer" (:role event)))
    (is (= [bob] (:attendees event)))
    ;; Dense: an invitee who has not answered is `needs-action`, not absent.
    ;; `:calendar/rsvp` is sparse, and a list built from it omits exactly the
    ;; people the organizer is waiting on.
    (is (= {bob "needs-action"} (:rsvp event)))
    (is (nil? (:your-rsvp event)) "the organizer was not asked")
    (testing "the invitee sees it in their own list, as an attendee"
      (let [seen (first (:items (schedule/events calendars bob)))]
        (is (= (:id event) (:id seen)))
        (is (= "attendee" (:role seen)))
        (is (= "needs-action" (:your-rsvp seen)))))
    (testing "and somebody who is on neither side sees nothing"
      (is (= [] (:items (schedule/events calendars carol)))))))

(deftest an-event-you-are-not-on-does-not-exist-to-you
  ;; The same answer for "no such event" and "not yours". Telling them apart
  ;; tells a stranger that a meeting they were not invited to happened.
  (let [{:keys [calendars]} (schedule/create {} "evt-1" morning alice)]
    (doseq [[id label] [["evt-1" "somebody else's event"]
                        ["evt-nothing" "an id that was never minted"]]]
      (is (= :scheduler/not-found
             (ex-type #(schedule/respond calendars id "accepted" carol)))
          label))))

(deftest answering-is-for-the-invited-and-only-the-three-answers
  (let [{c0 :calendars} (schedule/create {} "evt-1" (assoc morning :attendees [bob]) alice)
        {c1 :calendars r1 :result} (schedule/respond c0 "evt-1" "accepted" bob)]
    (is (= "accepted" (:your-rsvp (:event r1))))
    (is (= {bob "accepted"} (:rsvp (first (:items (schedule/events c1 alice))))))
    (testing "a change of mind is the answer, not a second one"
      (let [{c2 :calendars} (schedule/respond c1 "evt-1" "declined" bob)]
        (is (= {bob "declined"} (:rsvp (first (:items (schedule/events c2 alice))))))))
    (testing "the organizer cannot answer their own invitation"
      ;; They can see it, so this is 403-shaped and not 404-shaped: not
      ;; invited, rather than no such event.
      (is (= :scheduler/not-invited
             (ex-type #(schedule/respond c1 "evt-1" "accepted" alice)))))
    (testing "and an answer the model does not know is refused by name"
      ;; `calendar/respond` returns the calendar unchanged for an unknown
      ;; status, which as an API would be 200 and no change.
      (is (= :scheduler/unknown-rsvp
             (ex-type #(schedule/respond c1 "evt-1" "maybe-ish" bob)))))))

(deftest inviting-is-the-organizers-and-twice-is-once
  (let [{c0 :calendars} (schedule/create {} "evt-1" morning alice)
        {c1 :calendars r1 :result} (schedule/invite c0 "evt-1" bob alice)]
    (is (false? (:already? r1)))
    (let [{r2 :result} (schedule/invite c1 "evt-1" bob alice)]
      (is (true? (:already? r2)))
      ;; The list would gain a duplicate and the RSVP map would not, so the
      ;; same person would appear twice with one answer.
      (is (= [bob] (:attendees (:event r2)))))
    (testing "an attendee cannot invite"
      (is (= :scheduler/not-organizer
             (ex-type #(schedule/invite c1 "evt-1" carol bob)))))
    (testing "and the organizer is not invitable"
      (is (= :scheduler/organizer-is-not-an-attendee
             (ex-type #(schedule/invite c1 "evt-1" alice alice)))))))

(deftest an-appointment-with-no-time-is-refused-by-the-model
  ;; `calendar.validate` already knows what a broken event is. This checks the
  ;; domain asks it rather than holding a second opinion.
  (doseq [[attrs label] [[{:title "いつか"} "no times at all"]
                         [{:title "逆さま" :start "2026-08-03T10:00:00Z"
                           :end "2026-08-03T09:00:00Z"} "ends before it starts"]]]
    (is (= :scheduler/invalid-event
           (ex-type #(schedule/create {} "evt-1" attrs alice)))
        label)))

(deftest a-clash-is-what-you-said-yes-to
  (let [{c0 :calendars} (schedule/create {} "evt-1" (assoc morning :attendees [bob]) alice)
        {c1 :calendars} (schedule/create c0 "evt-2"
                                         {:title "重なる打ち合わせ" :attendees [bob]
                                          :start "2026-08-03T09:30:00Z"
                                          :end "2026-08-03T10:30:00Z"}
                                         carol)]
    (testing "an unanswered invitation still counts as a clash"
      ;; It is on your calendar until you say otherwise, which is why declining
      ;; is worth doing.
      (is (= ["evt-1"] (mapv :id (schedule/conflicts c1 "evt-2" bob)))))
    (testing "declining clears it"
      (let [{c2 :calendars} (schedule/respond c1 "evt-1" "declined" bob)]
        (is (= [] (schedule/conflicts c2 "evt-2" bob)))))
    (testing "and an event never clashes with itself"
      (is (= [] (schedule/conflicts c1 "evt-1" alice))))))

(deftest cancelling-is-the-organizers-and-takes-it-off-everyones-list
  (let [{c0 :calendars} (schedule/create {} "evt-1" (assoc morning :attendees [bob]) alice)]
    (is (= :scheduler/not-organizer (ex-type #(schedule/cancel c0 "evt-1" bob))))
    (let [{c1 :calendars} (schedule/cancel c0 "evt-1" alice)]
      (is (= [] (:items (schedule/events c1 alice))))
      (is (= [] (:items (schedule/events c1 bob)))
          "the invitation was a mention in this event, so it goes with it"))))

(deftest an-unauthenticated-actor-is-refused-before-anything-is-read
  (doseq [actor ["" "   " nil]]
    (is (= :identity/unauthenticated (ex-type #(schedule/events {} actor))))
    (is (= :identity/unauthenticated (ex-type #(schedule/create {} "e" morning actor))))))

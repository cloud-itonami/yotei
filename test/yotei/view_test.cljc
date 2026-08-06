(ns yotei.view-test
  "The page's promises, as assertions.

  These are not screenshot tests. Each one pins a claim the docstrings make
  about what the page will and will not do — G6 (no scarcity, no conversion
  nudges), G5 (it does not say 'confirmed', because it cannot confirm) and G2
  (it asks only for what holds the slot). A claim in a docstring with nothing
  checking it is a claim that drifts."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [yotei.availability :as av]
            [yotei.time :as t]
            [yotei.view :as view]))

(def cal
  (av/calendar "did:web:app.itonami.cloud:yotei:calendar:alice"
               {:yotei/tz-offset-min 540
                :yotei/slot-min 30
                :yotei/windows [(av/window :monday "10:00" "12:00")]}))

(def now (t/parse-instant "2026-03-08T00:00:00Z"))

(defn- openings []
  (av/openings cal now (+ now (* 3 1440)) [] now))

(defn- ctx [& {:as over}]
  (merge {:owner-label "アリス" :purpose "30分の打ち合わせ"
          :calendar cal :openings (openings)}
         over))

(defn- text-of
  "Every string in a hiccup tree, joined — attribute values included, so a
  nudge hidden in an aria-label or a placeholder is caught too."
  [tree]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (string? x) (swap! acc conj x)) x) tree)
    (str/join " " @acc)))

(defn- tags-of [tree]
  (let [acc (atom #{})]
    (walk/postwalk (fn [x]
                     (when (and (vector? x) (keyword? (first x)))
                       (swap! acc conj (first x)))
                     x)
                   tree)
    @acc))

;; ── G6: honest availability, no scarcity ──
(deftest booking-page-carries-no-scarcity-signal
  (let [txt (text-of (view/booking-page (ctx)))]
    (doseq [nudge ["残り" "あと僅か" "まもなく" "お早め" "人気" "限定" "急" "締切"
                   "left" "hurry" "only" "last chance"]]
      (testing nudge
        (is (not (str/includes? (str/lower-case txt) (str/lower-case nudge))))))))

(deftest booking-page-does-not-render-taken-slots-at-all
  ;; Not disabled, not struck through — absent. A greyed-out row would both
  ;; nudge the visitor and leak how busy the owner is.
  (let [taken [{"status" "confirmed" "calendarDid" (:yotei/calendar-did cal)
                "startEpochMin" (t/parse-instant "2026-03-09T01:00:00Z")
                "durationMin" 30}]
        os (av/openings cal now (+ now (* 3 1440)) taken now)
        tree (view/booking-page (ctx :openings os))
        txt (text-of tree)]
    (is (not (str/includes? txt "10:00")))
    (is (str/includes? txt "10:30"))
    (testing "and nothing is rendered disabled"
      (is (not (str/includes? (pr-str tree) ":disabled"))))))

(deftest an-empty-calendar-says-so
  ;; A blank region cannot be told apart from a broken page.
  (let [txt (text-of (view/booking-page (ctx :openings [])))]
    (is (str/includes? txt "空いている時間がありません"))))

;; ── G5: it must not claim to have confirmed ──
(deftest booking-page-says-who-actually-confirms
  (let [txt (text-of (view/booking-page (ctx)))]
    (is (str/includes? txt "この画面では確定しません"))))

(deftest proposed-page-does-not-say-confirmed
  (let [tree (view/proposed-page (ctx :start-epoch-min (t/parse-instant "2026-03-09T01:00:00Z")
                                      :duration-min 30))
        txt (text-of tree)]
    (is (str/includes? txt "まだ確定していません"))
    (is (not (str/includes? txt "予約が確定")))
    (is (not (str/includes? (str/lower-case txt) "confirmed")))))

;; ── G2: only what holds the slot ──
(deftest confirm-form-asks-for-nothing-it-does-not-need
  (let [tree (view/confirm-form (ctx :start-epoch-min (t/parse-instant "2026-03-09T01:00:00Z")
                                     :duration-min 30))
        names (->> (str/split (pr-str tree) #"\s+")
                   (filter #(str/starts-with? % ":name")))]
    (testing "three fields plus the hidden slot, and no marketing consent"
      (is (= #{"name" "contact" "purpose" "start" "minutes"}
             (set (re-seq #"(?<=:name \")[a-z]+" (pr-str tree))))))
    (is (not (str/includes? (pr-str tree) "checkbox")))
    (is (seq names))))

(deftest contact-field-says-what-happens-to-it
  (let [txt (text-of (view/confirm-form
                      (ctx :start-epoch-min (t/parse-instant "2026-03-09T01:00:00Z")
                           :duration-min 30)))]
    (is (str/includes? txt "暗号化"))))

;; ── timezone is never implicit ──
(deftest every-page-that-shows-a-time-shows-the-offset
  (doseq [[label tree]
          [["openings" (view/booking-page (ctx))]
           ["confirm" (view/confirm-form (ctx :start-epoch-min (t/parse-instant "2026-03-09T01:00:00Z")
                                              :duration-min 30))]
           ["proposed" (view/proposed-page (ctx :start-epoch-min (t/parse-instant "2026-03-09T01:00:00Z")
                                                :duration-min 30))]]]
    (testing label
      (is (str/includes? (text-of tree) "UTC+09:00")))))

(deftest local-times-are-rendered-in-the-owners-offset
  ;; 01:00Z is 10:00 in a +09:00 calendar. Rendering the UTC face would be an
  ;; off-by-nine-hours 予約 that nothing else in the stack would catch.
  (let [txt (text-of (view/booking-page (ctx)))]
    (is (str/includes? txt "10:00"))
    (is (str/includes? txt "2026年3月9日（月）"))))

;; ── accessibility claims the docstrings make ──
(deftest each-time-button-has-a-self-sufficient-accessible-name
  (let [tree (view/booking-page (ctx))
        labels (re-seq #"(?<=:aria-label \")[^\"]+" (pr-str tree))]
    (is (seq labels))
    (testing "every one carries its date, not just the clock face"
      (is (every? #(str/includes? % "2026年") labels)))))

(deftest the-page-is-a-list-of-forms-so-it-works-without-javascript
  (let [tags (tags-of (view/booking-page (ctx)))]
    (is (contains? tags :form))
    (is (contains? tags :button))
    (is (not (contains? tags :script)))))

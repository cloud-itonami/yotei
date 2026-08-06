(ns yotei.view
  "The page you send to somebody so they can pick a time.

  Pure hiccup, no I/O — `yotei.render` reads the stylesheet and hands it in, so
  this namespace stays portable and a test can assert on the tree instead of on
  a screenshot.

  ## What this page deliberately does not do

  The manifest's G6 forbids optimizing for conversion, and a 予約 page is where
  that is normally violated. So: no 'only 2 slots left', no countdown, no
  greyed-out rows showing how busy the owner is, no pre-ticked reminder
  opt-in, no field that is not needed to hold the slot. A taken time is simply
  not on the page — `yotei.availability` already removed it, and adding it back
  as a disabled row would leak the owner's day and nudge the visitor at once.

  ## It says who confirms, because it is not this page

  G5 means yotei holds no key: a stranger's choice becomes a **proposal**, and
  the calendar owner's passkey turns it into a 予約. Every other 予約 page in
  the world says 'Confirmed!' at this point. Saying that here would be a lie
  with a calendar entry attached, so the page says what actually happened and
  what is waited on.

  ## Styling is the token contract only

  Every value is a `--hig-*` custom property that `jp-go-dds.tokens/bridge-css`
  resolves onto DADS primitives. No hex, no px font size, no second dark
  palette — `jp-go-dds.page` already ships `color-scheme` and the inversion
  layer, so both themes come from the same tree."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [yotei.availability :as av]
            [yotei.time :as t]))

(def app-css
  "Layout only. Everything that is a *value* comes from the token contract.

  Unlayered on purpose: library CSS ships inside `@layer`, so this wins without
  a single compound selector. If it grows past a screen, the pattern belongs
  upstream in `dds-ext-*` rather than here."
  "
.yoyaku { max-width: 44rem; margin: 0 auto; padding: var(--hig-spacing-6) var(--hig-spacing-content-margin) var(--hig-spacing-10); }
.yoyaku__lede { color: var(--hig-color-secondary-label); margin: var(--hig-spacing-2) 0 0; }
.yoyaku__owner { color: var(--hig-color-secondary-label); font-weight: 700; margin: var(--hig-spacing-1) 0 0; }
.yoyaku__facts { display: flex; flex-wrap: wrap; gap: var(--hig-spacing-2); margin: var(--hig-spacing-4) 0 0; padding: 0; list-style: none; }
.yoyaku__day { margin: var(--hig-spacing-6) 0 0; }
.yoyaku__date { font-size: var(--hig-text-headline-font-size); line-height: var(--hig-text-headline-line-height); font-weight: 700; margin: 0 0 var(--hig-spacing-3); padding-bottom: var(--hig-spacing-2); border-bottom: var(--hig-hairline) solid var(--hig-color-separator); }
.yoyaku__times { display: grid; grid-template-columns: repeat(auto-fill, minmax(7.5rem, 1fr)); gap: var(--hig-spacing-2); padding: 0; margin: 0; list-style: none; }
.yoyaku__time button { width: 100%; min-height: 2.75rem; font-variant-numeric: tabular-nums; }
.yoyaku__empty { color: var(--hig-color-secondary-label); background: var(--hig-color-secondary-system-grouped-background); border-radius: var(--hig-radius-md); padding: var(--hig-spacing-5); margin: var(--hig-spacing-6) 0 0; }
.yoyaku__note { color: var(--hig-color-tertiary-label); font-size: var(--hig-text-footnote-font-size); line-height: var(--hig-text-footnote-line-height); margin: var(--hig-spacing-6) 0 0; }
.yoyaku__form { display: grid; gap: var(--hig-spacing-4); margin: var(--hig-spacing-5) 0 0; }
.yoyaku__slot { font-weight: 700; font-variant-numeric: tabular-nums; }
")

(def head-extras
  "Extra <head> elements every 予約 page carries.

  One inline favicon, and it is here for a reason that is not decoration. A
  browser asks the *origin root* for /favicon.ico on every page load. This app
  is mounted at a path under a dispatch router whose actor-name pattern
  rejects a dot, so that automatic request comes back 400 and lands in the
  console of every visitor — a real error, logged on a page that is otherwise
  error-free, pointing at nothing they can act on.

  Declaring an icon inline stops the request being made at all. It is a data
  URI rather than a file so the page stays self-contained: no second request
  to fail, and nothing to deploy alongside."
  [[:link {:rel "icon"
           :href (str "data:image/svg+xml,"
                      "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E"
                      "%3Ccircle cx='8' cy='8' r='7' fill='none' stroke='%230031d8' stroke-width='1.5'/%3E"
                      "%3Cpath d='M8 4v4.4l3 1.8' fill='none' stroke='%230031d8' "
                      "stroke-width='1.5' stroke-linecap='round'/%3E%3C/svg%3E")}]])

(def ^:private weekday-ja
  {:sunday "日" :monday "月" :tuesday "火" :wednesday "水"
   :thursday "木" :friday "金" :saturday "土"})

(defn- offset-label
  "`+09:00`. Shown next to every time, because a page of bare clock faces is
  the single most common way a cross-timezone 予約 lands an hour off."
  [offset-min]
  (let [sign (if (neg? offset-min) "-" "+")
        a (Math/abs (long offset-min))]
    (str sign (t/format-hhmm a))))

(defn- local-hhmm [offset-min epoch-min]
  (subs (t/format-instant (+ epoch-min offset-min)) 11 16))

(defn- date-label
  "`\"2026-03-09\"` → `\"2026年3月9日（月）\"`.

  Parsed through `yotei.time` rather than `Integer/parseInt`, which exists only
  on the JVM — this namespace renders in the browser too."
  [iso-date]
  (let [day (t/epoch-day (t/parse-instant iso-date))
        [y m d] (t/civil-from-days day)]
    (str y "年" m "月" d "日（" (weekday-ja (t/day-of-week day)) "）")))

(defn openings-section
  "The choosable times, grouped by the owner's local date.

  An empty result is a sentence, not a blank region: a visitor who sent a link
  and sees nothing cannot tell whether the owner is fully booked or the page is
  broken, and those need different next actions."
  [cal grouped]
  (let [offset (:yotei/tz-offset-min (merge av/defaults cal))]
    (if (empty? grouped)
      [:p {:class "yoyaku__empty"}
       "いまのところ空いている時間がありません。"
       "しばらくしてから、もう一度この画面を開いてください。"]
      (into [:div]
            (for [{:yotei/keys [date openings]} grouped]
              [:section {:class "yoyaku__day"}
               [:h2 {:class "yoyaku__date"} (date-label date)]
               (into [:ul {:class "yoyaku__times"}]
                     (for [o openings
                           :let [start (:yotei/start-epoch-min o)
                                 hhmm (local-hhmm offset start)]]
                       [:li {:class "yoyaku__time"}
                        ;; No :action — the form posts to the URL it was
                        ;; served from. A relative "select" would resolve
                        ;; against /yotei/c/jun to /yotei/c/select and drop the
                        ;; calendar, and an absolute path would make this view
                        ;; know where it is mounted. `step` says which stage
                        ;; this is instead.
                        [:form {:method "post"}
                         [:input {:type "hidden" :name "step" :value "select"}]
                         [:input {:type "hidden" :name "start"
                                  :value (t/format-instant start)}]
                         [:input {:type "hidden" :name "minutes"
                                  :value (str (:yotei/duration-min o))}]
                         (dds/button hhmm
                                     {:type :outline
                                      :submit? true
                                      ;; The visible label is a bare time, so
                                      ;; the accessible name carries the date —
                                      ;; a screen-reader user tabbing the grid
                                      ;; otherwise hears "10:00" eleven times.
                                      :aria-label (str (date-label date) " " hhmm
                                                       " から"
                                                       (:yotei/duration-min o) "分")})]]))])))))

(defn yoyaku-page
  "The whole page a visitor lands on."
  [{:keys [owner-label purpose calendar openings]}]
  (let [cal (merge av/defaults calendar)
        offset (:yotei/tz-offset-min cal)
        grouped (av/by-local-day cal openings)]
    [:main {:class "yoyaku"}
     (dds/heading 1 (if (seq (str (:yotei/name cal)))
                     (str (:yotei/name cal))
                     (str owner-label "の予定を押さえる"))
                  {:size "32"})
     (when (seq (str (:yotei/name cal)))
       [:p {:class "yoyaku__owner"} owner-label])
     (when (seq purpose) [:p {:class "yoyaku__lede"} purpose])
     [:ul {:class "yoyaku__facts"}
      [:li (dds/chip-label (str (:yotei/slot-min cal) "分"))]
      [:li (dds/chip-label (str "時刻は UTC" (offset-label offset) " 表示"))]]
     (openings-section cal grouped)
     [:p {:class "yoyaku__note"}
      "選んだ時間は" owner-label
      "に届き、承認されると確定します。この画面では確定しません。"]]))

(defn confirm-form
  "Asked after a time is chosen: the least that can hold a slot.

  One contact field, and it is a free-text handle rather than a typed email
  input, because G2 forbids building a profile out of the person reserving —
  yotei never validates, normalizes, enriches or stores it as a profile
  attribute.

  **The label says only what is true today.** It used to say the handle was
  encrypted; it is not. `yotei.edge.worker` stores it under the marker
  `unencrypted-pending-envelope:` because the envelope service G2 points at is
  not wired yet, and the marker was chosen precisely so nobody could mistake
  the state — and then the page told visitors the opposite. Promising a
  stranger encryption that does not exist is worse than asking for the handle
  plainly, so the promise waits until the envelope does. When
  `contactRef` really becomes `com.etzhayyim.encrypted.*`, this line changes
  with it and not before."
  [{:keys [owner-label calendar start-epoch-min duration-min]}]
  (let [cal (merge av/defaults calendar)
        offset (:yotei/tz-offset-min cal)
        iso (t/format-instant start-epoch-min)
        date (subs (t/format-instant (+ start-epoch-min offset)) 0 10)]
    [:main {:class "yoyaku"}
     (dds/heading 1 "この時間で申し込む" {:size "32"})
     [:p {:class "yoyaku__lede"}
      [:span {:class "yoyaku__slot"}
       (date-label date) " " (local-hhmm offset start-epoch-min)
       "〜" (local-hhmm offset (+ start-epoch-min duration-min))]
      (str " （UTC" (offset-label offset) "・" duration-min "分）")]
     ;; Same URL, different step — see `yoyaku-page`.
     [:form {:class "yoyaku__form" :method "post"}
      [:input {:type "hidden" :name "step" :value "propose"}]
      [:input {:type "hidden" :name "start" :value iso}]
      [:input {:type "hidden" :name "minutes" :value (str duration-min)}]
      (dds/form-field {:label "お名前" :for "yoyaku-name"
                       :requirement "必須" :required? true}
                      (dds/input-text {:id "yoyaku-name" :name "name"
                                       :required true :autocomplete "name"}))
      (dds/form-field {:label "連絡のつく手段"
                       :for "yoyaku-contact"
                       :requirement "必須" :required? true
                       :support-id "yoyaku-contact-support"
                       :support (str "この予定の連絡にだけ使います。" owner-label
                                     "が受け取ります。他の用途には使いません。")}
                      (dds/input-text {:id "yoyaku-contact" :name "contact"
                                       :required true
                                       :aria-describedby "yoyaku-contact-support"}))
      (dds/form-field {:label "用件" :for "yoyaku-purpose"
                       :requirement "任意" :required? false}
                      (dds/textarea {:id "yoyaku-purpose" :name "purpose" :rows 3}))
      [:div (dds/button "この時間で申し込む" {:type :solid-fill :submit? true})]]
     [:p {:class "yoyaku__note"}
      "送信すると" owner-label "に届きます。"
      owner-label "が承認した時点で確定し、それまでこの時間は他の人も選べます。"]]))

(defn proposed-page
  "What actually happened, said plainly.

  Not 'Confirmed'. The slot is not held yet (`is-free?` counts only confirmed
  予約), and telling somebody their meeting is booked when it is not is the
  one failure a 予約 page cannot recover from."
  [{:keys [owner-label calendar start-epoch-min duration-min]}]
  (let [cal (merge av/defaults calendar)
        offset (:yotei/tz-offset-min cal)
        date (subs (t/format-instant (+ start-epoch-min offset)) 0 10)]
    [:main {:class "yoyaku"}
     (dds/heading 1 "申し込みを受け付けました" {:size "32"})
     (dds/notification-banner
      {:type :info-1 :heading "まだ確定していません"}
      [:p (str owner-label "が承認すると確定します。結果はご連絡します。")])
     [:p {:class "yoyaku__lede"}
      [:span {:class "yoyaku__slot"}
       (date-label date) " " (local-hhmm offset start-epoch-min)
       "〜" (local-hhmm offset (+ start-epoch-min duration-min))]
      (str " （UTC" (offset-label offset) "）")]
     [:p {:class "yoyaku__note"}
      "この画面は閉じてかまいません。"]]))

(defn refused-page
  "A refusal that says which rule refused, so the visitor knows whether to
  retry. 'Something went wrong' would make a lost race and a closed calendar
  look the same."
  [{:keys [reason]}]
  [:main {:class "yoyaku"}
   (dds/heading 1 "この時間は取れませんでした" {:size "32"})
   (dds/notification-banner
    {:type :warning :heading "別の時間を選んでください"}
    [:p (or reason "その時間はすでに埋まりました。")])
   [:p {:class "yoyaku__note"}
    [:a {:href "."} "空いている時間の一覧に戻る"]]])

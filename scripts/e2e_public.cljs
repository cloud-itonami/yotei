(ns e2e-public
  "Drive the public 予約 page in a real browser, as a stranger would.

  curl proved the bytes; it cannot prove the page. The one bug that reached
  production this week was `action=\"select\"` resolving to /yotei/c/select and
  dropping the calendar — invisible to curl and to every unit test, because
  none of them resolved a URL. A browser resolves it, so a browser is what
  checks it.

  Headless, and through the installed Chrome rather than a downloaded browser:
  this machine runs many concurrent Claude Code sessions competing for OS
  focus, and a headed browser would steal it. Headless takes no focus.

  Run: nbb scripts/e2e_public.cljs [base-url]"
  (:require ["playwright$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def base
  ;; Picked by shape, not by position. nbb's own flags (`--classpath …`) sit in
  ;; argv ahead of the script's, so index 2 is whatever nbb was invoked with —
  ;; the first run of this harness tried to navigate to "--classpath".
  (or (first (filter #(str/starts-with? % "http") (js->clj js/process.argv)))
      "https://app.itonami.cloud/yotei/c/jun"))

(def failures (atom []))

(defn check! [label ok? detail]
  (if ok?
    (println "  ok  " label (if detail (str "— " detail) ""))
    (do (swap! failures conj label)
        (println "  FAIL" label (if detail (str "— " detail) "")))))

(defn -main []
  (p/let [browser (.launch (.-chromium pw)
                           #js {:headless true :channel "chrome"})
          ;; A fresh context: no cookies, no storage, no session. If the page
          ;; needed authentication this is where it would fail, which is the
          ;; thing "公開されているか" actually asks.
          ctx (.newContext browser #js {:viewport #js {:width 900 :height 1000}})
          page (.newPage ctx)
          console-errors (atom [])
          _ (.on page "console"
                 (fn [msg] (when (= "error" (.type msg))
                             (swap! console-errors conj (.text msg)))))
          _ (.on page "pageerror" (fn [e] (swap! console-errors conj (str e))))

          ;; ── 1. the page a stranger opens ───────────────────────────────
          resp (.goto page base #js {:waitUntil "load" :timeout 30000})
          status (.status resp)
          title (.title page)
          _ (println "\n[1] 公開ページ" base)
          _ (check! "HTTP 200 with no credentials" (= 200 status) (str "status " status))
          _ (check! "has a title" (str/includes? title "yotei") title)

          slots (.$$ page "button.dads-button")
          n-slots (count slots)
          days (.$$ page "h2.yoyaku__date")
          _ (check! "offers slots" (pos? n-slots) (str n-slots " 枠"))
          _ (check! "grouped by day" (pos? (count days)) (str (count days) " 日"))

          ;; The design-system stylesheet must actually have applied — an
          ;; unstyled page still contains every string a text assertion looks
          ;; for, so assert on computed layout instead.
          btn-w (.evaluate page "(() => { const b = document.querySelector('button.dads-button'); return b ? Math.round(b.getBoundingClientRect().width) : 0; })()")
          body-bg (.evaluate page "getComputedStyle(document.body).backgroundColor")
          _ (check! "CSS applied (button has real width)" (> btn-w 40) (str btn-w "px"))
          _ (check! "no console errors" (empty? @console-errors)
                    (str/join "; " (take 2 @console-errors)))

          first-label (.textContent (first slots))
          first-start (.evaluate page "(() => document.querySelector('input[name=start]').value)()")

          ;; ── 2. click a time, exactly as a person does ──────────────────
          _ (println "\n[2] 枠をクリック:" first-label first-start)
          _ (p/all [(.waitForNavigation page #js {:timeout 30000})
                    (.click (first slots))])
          url2 (.url page)
          title2 (.title page)
          _ (check! "stayed on this calendar (the action= bug)"
                    (str/includes? url2 "/c/jun") url2)
          _ (check! "reached the form" (str/includes? title2 "申し込む") title2)

          name-box (.$ page "#yoyaku-name")
          contact-box (.$ page "#yoyaku-contact")
          _ (check! "asks for a name" (some? name-box) nil)
          _ (check! "asks for a contact" (some? contact-box) nil)
          echoed (.evaluate page "(() => document.querySelector('input[name=start]').value)()")
          _ (check! "carries the chosen time forward" (= echoed first-start) echoed)

          ;; ── 3. fill it in and submit ───────────────────────────────────
          _ (println "\n[3] 記入して送信")
          ;; page.fill(selector, value) rather than elementHandle.fill(value):
          ;; nbb's interop could not dispatch `.fill` on the handle, and the
          ;; page-level form is the one Playwright documents anyway.
          _ (.fill page "#yoyaku-name" "ブラウザ検証")
          _ (.fill page "#yoyaku-contact" "e2e@example.com")
          _ (.fill page "#yoyaku-purpose" "実ブラウザからの疎通確認")
          _ (p/all [(.waitForNavigation page #js {:timeout 30000})
                    (.click page "button[type=submit]")])
          body3 (.innerText page "body")
          _ (check! "accepted" (str/includes? body3 "申し込みを受け付けました") nil)
          ;; The single most important string on the page: it must not claim a
          ;; confirmation it cannot make (G5 — yotei holds no key).
          _ (check! "does NOT claim confirmed"
                    (and (str/includes? body3 "まだ確定していません")
                         (not (str/includes? body3 "予約が確定")))
                    nil)

          ;; ── 4. the slot is now taken for the next visitor ──────────────
          _ (println "\n[4] 別の来訪者から見た状態")
          ctx2 (.newContext browser)
          page2 (.newPage ctx2)
          _ (.goto page2 base #js {:waitUntil "load" :timeout 30000})
          starts2 (.evaluate page2 "(() => Array.from(document.querySelectorAll('input[name=start]')).map(i => i.value))()")
          still? (some #{first-start} (js->clj starts2))
          ;; A *proposal* deliberately does not hold the slot — nothing is held
          ;; until the owner signs (G5). So it must still be offered.
          _ (check! "proposed does not hold the slot (G5)" (some? still?)
                    (str (count (js->clj starts2)) " 枠"))

          _ (.close ctx2)
          _ (.close ctx)
          _ (.close browser)]
    (println "\n────────────────────────────")
    (if (empty? @failures)
      (println "全て合格")
      (do (println "失敗:" (str/join ", " @failures))
          (set! (.-exitCode js/process) 1)))))

(-> (-main)
    (p/catch (fn [e]
               (println "harness error:" (str e))
               (set! (.-exitCode js/process) 1))))

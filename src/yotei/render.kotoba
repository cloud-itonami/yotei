(ns yotei.render
  "hiccup → a whole HTML document.

  JVM-only (`.clj`, not `.cljc`) because it reads a file. The Cloudflare
  ingress inlines the same stylesheet at build time instead — see
  `yotei.worker`. This is the SSR and preview path.

  It is the only namespace here that touches the filesystem, and it touches it for
  one reason: `jp-go-dds.page/page` is pure and takes the stylesheet as a
  string, so somebody has to read it. Keeping that somebody in one place is
  what lets `yotei.view` be tested as data.

  The stylesheet is read once and held, because a 予約 page is rendered per
  request and re-reading a ~100 KB file per request is a cost with no payer."
  (:require [clojure.java.io :as io]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [yotei.view :as view]))

(def ^:private dds-css
  (delay (slurp (io/resource "jp_go_dds/dds.css"))))

(defn document
  "A full HTML document string for one of `yotei.view`'s pages.

  `tokens/bridge-css` comes first in the app CSS so the `--hig-*` contract is
  defined before `yotei.view/app-css` consumes it. Reversing them would leave
  every token unresolved — and unresolved custom properties collapse silently
  rather than erroring, so the page would render un-styled and look like a
  layout bug rather than an ordering one."
  [{:keys [title description dark?]} body]
  (page/->page {:title title
                :description description
                :lang "ja"
                :css @dds-css
                :dark? (boolean dark?)
                :head view/head-extras
                :app-css (str tokens/bridge-css "\n" view/app-css)}
               body))

(defn yoyaku-document
  "The page you send somebody."
  [{:keys [owner-label] :as ctx}]
  (document {:title (str (str owner-label) "の予定を押さえる — yotei")
             :description "空いている時間を選んで申し込めます。"}
            (view/yoyaku-page ctx)))

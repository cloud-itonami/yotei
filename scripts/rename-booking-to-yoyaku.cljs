(ns rename-booking-to-yoyaku
  "One-shot: rename the wire vocabulary from `booking` to `予約`/`yoyaku`, and
  re-home the lexicon NSIDs from the retired etzhayyim domain onto itonami.

  Two renames in one pass, because doing them separately would mean touching
  the same 50 files twice and leaving an intermediate commit in which the NSID
  says `com.etzhayyim` while the repo is mounted at `app.itonami.cloud`.

    com.etzhayyim.apps.yotei.proposeBooking -> cloud.itonami.apps.yotei.proposeYoyaku
    com.etzhayyim.yotei.booking             -> cloud.itonami.yotei.yoyaku
    bookingId                               -> yoyakuId

  Safe to run now and only now: nothing consumes these lexicons — the actor has
  never been deployed and `yotei.etzhayyim.com` never resolved. Once a record
  exists under an NSID, renaming it is a migration rather than a rename.

  `com.etzhayyim.encrypted.*` is deliberately NOT rewritten: that is the
  envelope type G2 points at, it belongs to another actor's namespace, and
  renaming somebody else's type because our own prefix changed would break the
  reference rather than move it.

  Run: nbb scripts/rename-booking-to-yoyaku.cljs [--check]"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def root (or (some-> js/process.env.YOTEI_ROOT) "."))

(def substitutions
  "Ordered. The NSID rewrites must run before the bare-word ones, or
  `com.etzhayyim.yotei.booking` would first become `...yotei.yoyaku` under a
  prefix that no longer gets rewritten."
  [;; 1. NSID prefixes (longest first)
   ["com.etzhayyim.apps.yotei." "cloud.itonami.apps.yotei."]
   ["com.etzhayyim.yotei." "cloud.itonami.yotei."]
   ;; 2. operation names inside NSIDs and elsewhere
   ["proposeBooking" "proposeYoyaku"]
   ["confirmBooking" "confirmYoyaku"]
   ["cancelBooking" "cancelYoyaku"]
   ["getBooking" "getYoyaku"]
   ["listBookings" "listYoyaku"]
   ;; 3. field names on the wire
   ["bookingId" "yoyakuId"]
   ;; 4. the record type itself
   ["yotei.booking" "yotei.yoyaku"]])

(def file-renames
  {"proposeBooking" "proposeYoyaku"
   "confirmBooking" "confirmYoyaku"
   "cancelBooking" "cancelYoyaku"
   "getBooking" "getYoyaku"
   "listBookings" "listYoyaku"
   "booking" "yoyaku"})

(defn- walk
  "Every file under `dir`, recursively."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (mapcat (fn [entry]
              (let [p (path/join dir entry)]
                (if (.isDirectory (fs/statSync p)) (walk p) [p])))
            (fs/readdirSync dir))))

(defn- rewrite [s]
  (reduce (fn [acc [from to]] (str/replace acc from to)) s substitutions))

(defn- target-files []
  (concat (walk (path/join root "data"))
          (walk (path/join root "wire"))
          [(path/join root "manifest.edn")
           (path/join root "lexicon-projection.edn")]))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        files (filter fs/existsSync (target-files))
        changed (atom [])
        renamed (atom [])]
    ;; 1. contents
    (doseq [f files]
      (let [before (fs/readFileSync f "utf8")
            after (rewrite before)]
        (when (not= before after)
          (swap! changed conj f)
          (when-not check? (fs/writeFileSync f after "utf8")))))
    ;; 2. filenames — after contents, so a rewritten file is not looked for
    ;;    under its old name.
    (doseq [f files]
      (let [dir (path/dirname f)
            base (path/basename f)
            ext (path/extname base)
            stem (subs base 0 (- (count base) (count ext)))]
        (when-let [to (get file-renames stem)]
          (let [dest (path/join dir (str to ext))]
            (swap! renamed conj [f dest])
            (when-not check?
              (when (fs/existsSync f) (fs/renameSync f dest)))))))
    (println (if check? "check:" "rewrote:") (count @changed) "file(s) with content changes")
    (doseq [[from to] @renamed]
      (println "  rename" (path/basename from) "->" (path/basename to)))
    ;; A leftover is the failure mode that matters: a half-renamed vocabulary
    ;; is worse than an un-renamed one, because grep stops finding the rest.
    (when-not check?
      (let [left (->> (filter fs/existsSync (target-files))
                      (filter (fn [f]
                                (let [s (fs/readFileSync f "utf8")]
                                  (or (str/includes? s "com.etzhayyim.apps.yotei")
                                      (str/includes? s "com.etzhayyim.yotei")
                                      (str/includes? s "bookingId")
                                      (re-find #"(?i)Booking" s))))))]
        (if (seq left)
          (do (println "\nLEFTOVER 'booking' in:")
              (doseq [f left] (println "  " f))
              (js/process.exit 1))
          (println "\nno 'booking' left in data/ wire/ manifest.edn lexicon-projection.edn"))))))

;; argv, not `(-main)`. Called with no arguments the `--check` flag could never
;; be seen, so a dry run silently wrote — which is how this script was first
;; run. nbb puts script args at index 2 onward, the same as node.
(apply -main (drop 2 (js->clj js/process.argv)))

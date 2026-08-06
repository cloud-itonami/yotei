(ns yotei.manifest-test
  "The app-org declaration is a contract with a generator, so it is checked.

  `manifest.edn` is where this repo says which app org owns it and where it
  mounts under `app.itonami.cloud`. The router's registry is generated from
  these declarations across repos rather than hand-listed on the host, which
  only works if every declaration is present and shaped the same. A typo here
  would otherwise surface as an app that silently fails to mount."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def m (delay (edn/read-string (slurp "manifest.edn"))))

(deftest declares-its-mount-under-the-shared-app-host
  (is (= "app.itonami.cloud" (:app/host @m)))
  (is (= "/yotei" (:app/mount @m)))
  (is (true? (:app/default? @m))))

(deftest belongs-to-an-app-org
  (let [org (:app/org @m)]
    (is (map? org))
    (testing "every field the registry joins on is present"
      (is (string? (:organization/id org)))
      (is (string? (:organization/name org)))
      (is (string? (:organization/did org))))
    (testing "the did is derived from the host and the org id, not free text"
      (is (= (str "did:web:" (:app/host @m) ":org:" (:organization/id org))
             (:organization/did org))))))

(deftest mount-and-surface-agree
  ;; A surface path outside the mount would be routed to another app, or to
  ;; nothing — either way the declaration would be a lie the router believes.
  (let [mount (:app/mount @m)]
    (doseq [{:keys [path]} (:app/surface @m)]
      (testing path
        (is (str/starts-with? path (str mount "/")))))))

(deftest the-retired-domain-is-recorded-not-deleted
  ;; The lexicons were minted under it; a DID that changes identity silently is
  ;; worse than one recorded as retired.
  (is (some #{"yotei.etzhayyim.com"} (:actor/domain-retired @m))))

(deftest the-gates-this-code-implements-are-still-declared
  (let [ids (set (map :gate/id (:actor/gates @m)))]
    (doseq [g ["G2" "G4" "G5" "G6" "G8"]]
      (testing g (is (contains? ids g))))))

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
(def b (delay (edn/read-string (slurp "blueprint.edn"))))

(deftest declares-its-mount-in-the-blueprint
  ;; blueprint.edn, not manifest.edn: ADR-2608093000 D1. The Fleet view reads a
  ;; catalog generated from blueprint files, so an app that declares its mount
  ;; anywhere else does not exist to the screen that lists apps.
  (is (= "/yotei" (:itonami.blueprint/mount @b)))
  (is (= "https://app.itonami.cloud/yotei" (:itonami.blueprint/endpoint @b))))

(deftest the-app-facts-live-in-exactly-one-file
  ;; The duplication this replaced is the failure mode: two files carrying the
  ;; same fact means one of them silently goes stale.
  (is (empty? (filter #(= "app" (namespace %)) (keys @m)))
      "manifest.edn must not carry :app/* any more — blueprint.edn is the source"))

(deftest belongs-to-an-app-org
  (let [org (:itonami.blueprint/org @b)]
    (is (map? org))
    (testing "every field the registry joins on is present"
      (is (string? (:organization/id org)))
      (is (string? (:organization/name org)))
      (is (string? (:organization/did org))))
    (testing "the did is derived from the host and the org id, not free text"
      (is (= (str "did:web:app.itonami.cloud:org:" (:organization/id org))
             (:organization/did org))))))

(deftest mount-and-surface-agree
  ;; A surface path outside the mount would be routed to another app, or to
  ;; nothing — either way the declaration would be a lie the router believes.
  (let [mount (:itonami.blueprint/mount @b)]
    (doseq [{:keys [path]} (:itonami.blueprint/surface @b)]
      (testing path
        (is (str/starts-with? path (str mount "/")))))))

(deftest the-retired-domain-is-recorded-not-deleted
  ;; The lexicons were minted under it; a DID that changes identity silently is
  ;; worse than one recorded as retired.
  (is (some #{"yotei.etzhayyim.com"} (:actor/domain-retired @m))))

(deftest the-catalog-can-see-this-app
  ;; :endpoint is the field the generator uses to tell a callable actor from a
  ;; directory record. Without it yotei is listed and not reachable.
  (is (string? (:itonami.blueprint/endpoint @b)))
  (is (= "yotei" (:itonami.blueprint/id @b))
      "id must equal the repo name, which is the script name, which is the mount segment"))

(deftest the-gates-this-code-implements-are-still-declared
  (let [ids (set (map :gate/id (:actor/gates @m)))]
    (doseq [g ["G2" "G4" "G5" "G6" "G8"]]
      (testing g (is (contains? ids g))))))

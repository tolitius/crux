(ns ^:no-doc crux.fixtures
  (:require [clojure.test.check.clojure-test :as tcct]
            [crux.api :as crux]
            [crux.io :as cio]
            [crux.node :as n]
            [crux.standalone :as standalone]
            [crux.tx :as tx])
  (:import crux.api.ICruxAPI
           (java.util ArrayList List UUID)
           (java.io File)
           (java.nio.file Files FileVisitResult SimpleFileVisitor)
           java.nio.file.attribute.FileAttribute))

(defn with-silent-test-check [f]
  (binding [tcct/*report-completion* false]
    (f)))

(defn with-tmp-dir* [prefix f]
  (let [dir (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (cio/delete-dir dir)))))

(defmacro with-tmp-dir [prefix [dir-binding] & body]
  `(with-tmp-dir* ~prefix (fn [~(-> dir-binding (with-meta {:type File}))]
                            ~@body)))

(def ^:dynamic ^ICruxAPI *api*)
(def ^:dynamic *opts* nil)

(defn with-opts [opts f]
  (binding [*opts* (merge *opts* opts)]
    (f)))

(defn with-standalone-topology [f]
  (with-opts {::n/topology '[crux.standalone/topology]}
    f))

(defn with-standalone-doc-store [f]
  (with-opts (-> *opts*
                 (update ::n/topology conj (-> standalone/topology
                                               (select-keys [::n/document-store
                                                             ::standalone/event-log]))))
    f))

(defn with-kv-dir [f]
  (with-tmp-dir "kv-store" [db-dir]
    (with-opts {:crux.kv/db-dir db-dir}
      f)))

(defn with-node [f]
  (with-open [node (crux/start-node *opts*)]
    (binding [*api* node]
      (f))))

(defn submit+await-tx
  ([tx-ops] (submit+await-tx *api* tx-ops))
  ([api tx-ops]
   (let [tx (crux/submit-tx api tx-ops)]
     (crux/await-tx api tx)
     tx)))

(defn maps->tx-ops
  ([maps]
   (vec (for [m maps]
          [:crux.tx/put m])))
  ([maps ts]
   (vec (for [m maps]
          [:crux.tx/put m ts]))))

(defn transact!
  "Helper fn for transacting entities "
  ([api entities]
   (transact! api entities (cio/next-monotonic-date)))
  ([^ICruxAPI api entities ts]
   (doto (crux/submit-tx api (maps->tx-ops entities ts))
     (->> (crux/await-tx api)))
   entities))

(defn random-person []
  {:crux.db/id (UUID/randomUUID)
   :name (rand-nth ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"])
   :last-name (rand-nth ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"])
   :sex (rand-nth [:male :female])
   :age (rand-int 100)
   :salary (rand-int 100000)})

(defn people [people-mixins]
  (->> people-mixins (map merge (repeatedly random-person))))

;; Literal vectors aren't type hinted as List in Clojure, and cannot
;; be type hinted without via a var.
(defn vec->array-list ^java.util.List [^List v]
  (ArrayList. v))

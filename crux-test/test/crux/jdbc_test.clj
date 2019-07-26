(ns crux.jdbc-test
  (:require [clojure.test :as t]
            [crux.db :as db]
            [crux.jdbc :as j]
            [taoensso.nippy :as nippy]
            [next.jdbc :as jdbc]
            [crux.fixtures.api :refer [*api*]]
            [crux.fixtures.jdbc :as fj]
            [crux.fixtures.postgres :as fp]
            [crux.codec :as c]
            [crux.kafka :as k]
            [next.jdbc.result-set :as jdbcr])
  (:import crux.api.ICruxAPI))

(defn- with-each-jdbc-node [f]
  (t/testing "H2 Database"
    (fj/with-jdbc-node :h2 f))
  (t/testing "SQLite Database"
    (fj/with-jdbc-node :sqlite f))
  (t/testing "Postgresql Database"
    (fp/with-embedded-postgres f))
  ;; You need to setup mysql first with the respective user, and
  ;; create the db cruxtest:
  #_(t/testing "MYSQL Database"
    (fj/with-jdbc-node :mysql f {:user "cruxtest"
                                 :password "cruxtest"})))

(t/use-fixtures :each with-each-jdbc-node)

(t/deftest test-happy-path-jdbc-event-log
  (let [doc {:crux.db/id :origin-man :name "Adam"}
        submitted-tx (.submitTx *api* [[:crux.tx/put doc]])]
    (.sync *api* (:crux.tx/tx-time submitted-tx) nil)
    (t/is (.entity (.db *api*) :origin-man))
    (t/testing "Tx log"
      (with-open [tx-log-context (.newTxLogContext *api*)]
        (t/is (= [{:crux.tx/tx-id 2,
                   :crux.tx/tx-time (:crux.tx/tx-time submitted-tx)
                   :crux.api/tx-ops
                   [[:crux.tx/put
                     (str (c/new-id (:crux.db/id doc)))
                     (str (c/new-id doc))]]}]
                 (.txLog *api* tx-log-context 0 false)))))))

(defn- docs [ds id]
  (map (comp nippy/thaw :v) (jdbc/execute! ds ["SELECT V FROM tx_events WHERE TOPIC = 'docs' AND EVENT_KEY = ?" id]
                                           {:builder-fn jdbcr/as-unqualified-lower-maps})))

(t/deftest test-docs-retention
  (let [tx-log (:tx-log *api*)

        doc {:crux.db/id (c/new-id :some-id) :a :b}
        doc-hash (str (c/new-id doc))

        tx-1 (db/submit-tx tx-log [[:crux.tx/put doc]])]

    (t/is (= 1 (count (docs (:ds (:tx-log *api*)) doc-hash))))
    (t/is (= [doc] (docs (:ds (:tx-log *api*)) doc-hash)))

    (t/testing "Compaction"
      (db/submit-doc tx-log doc-hash {:crux.db/id (c/new-id :some-id) :a :evicted})
      (t/is (= [{:crux.db/id (c/new-id :some-id) :a :evicted}] (docs (:ds (:tx-log *api*)) doc-hash))))))
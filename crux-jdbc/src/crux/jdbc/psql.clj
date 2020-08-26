(ns ^:no-doc crux.jdbc.psql
  (:require [crux.jdbc :as j]
            [taoensso.nippy :as nippy]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as jdbcr]
            [next.jdbc.sql.builder :as qb]))

(defmethod j/setup-schema! :psql [_ ds]
  (jdbc/execute! ds ["create table if not exists tx_events (event_offset serial PRIMARY KEY,
                                                            event_key VARCHAR,
                                                            tx_time timestamp default CURRENT_TIMESTAMP,
                                                            topic VARCHAR NOT NULL,
                                                            v bytea NOT NULL,
                                                            compacted INTEGER NOT NULL)"])
  (jdbc/execute! ds ["create index if not exists tx_events_event_key_idx
                             on tx_events(compacted, event_key)"])
  ; (jdbc/execute! ds ["alter table tx_events                                    ;; TODO postres does not have add contraint if not exists
  ;                           add constraint event_key_uq unique (event_key)"])  ;;      so this runs for the first time, but needs a DB fn or two queries after that
  )

(defn make-insert-batch-query [topic batch]
  (let [qhead "insert into tx_events (event_key, v, topic, compacted) values "
        rows (->> (for [[id doc] batch]
                    [(str id) (nippy/freeze doc) topic 0])
                  (into []))
        [qhead & data] (qb/for-insert-multi :tx_events
                                            [:event_key :v :topic :compacted]
                                            rows
                                            {})
        qfoot " on conflict on constraint event_key_uq
                            do update set event_key = excluded.event_key,
                            v = excluded.v,
                            topic = excluded.topic,
                            compacted = excluded.compacted"]
    (concat [(str qhead qfoot)] data)))

(defmethod j/insert-batch! :psql [_ ds topic batch]
  (when (seq batch)
    (let [query (make-insert-batch-query topic batch)]
      (jdbc/with-transaction [tx ds]
        (jdbc/execute! ds
                       query
                       {:return-keys true
                        :builder-fn jdbcr/as-unqualified-lower-maps})))))

(defmethod j/->pool-options :psql [_ {:keys [username user] :as options}]
  (assoc options :username (or username user)))

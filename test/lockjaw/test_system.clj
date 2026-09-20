(ns lockjaw.test-system
  (:require
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [next.jdbc.connection :as connection]
   [next.jdbc.protocols :as jdbc.protocols])
  (:import
   (com.zaxxer.hikari
    HikariDataSource)))

(def db-spec
  {:dbtype "postgresql"
   :username (or (System/getenv "POSTGRES_USER") "lockjaw")
   :password (or (System/getenv "POSTGRES_PASSWORD") "password")
   :host (or (System/getenv "POSTGRES_HOST") "127.0.0.1")
   :port (Integer/parseInt (or (System/getenv "POSTGRES_PORT") "6001"))
   :dbname (or (System/getenv "POSTGRES_DB") "lockjaw_test")
   :maximumPoolSize 2})

(defrecord ConnectionPool
           [config datasource]
  component/Lifecycle
  (start
    [this]
    (log/infof "%s connecting=%s %s:%s"
               (:poolName config)
               (:dbname config)
               (:host config)
               (:port config))
    (assoc this :datasource (connection/->pool HikariDataSource config)))
  (stop
    [this]
    (log/warnf "%s disconnecting=%s %s:%s"
               (:poolName config)
               (:dbname config)
               (:host config)
               (:port config))
    (when datasource
      (.close ^HikariDataSource datasource))
    (assoc this :datasource nil))
  jdbc.protocols/Sourceable
  (get-datasource [this]
    (:datasource this)))

(defn create-pool
  "Minimal stand-in for a connection pool component - the tests need two
  independent pools, as advisory locks are held per connection/session."
  [pool-name]
  (map->ConnectionPool {:config (assoc db-spec :poolName pool-name)}))

(defn create
  [extra]
  (component/map->SystemMap
   (merge extra
          {:db-conn (create-pool "test-1")
           :db-conn-2 (create-pool "test-2")})))

(defn start!
  [sysatom extra]
  (reset! sysatom (component/start (create extra))))

(defn stop!
  [sysatom]
  (swap! sysatom component/stop))

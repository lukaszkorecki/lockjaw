(ns lockjaw.operation
  (:require
   [next.jdbc :as jdbc]))

(def acquire-lock-query "SELECT pg_try_advisory_lock(?)")

(def release-lock-query "SELECT pg_advisory_unlock(?)")

(def release-all-locks-query "SELECT pg_advisory_unlock_all()")

;; pg_backend_pid() is the session serving this very query, so this asks
;; "does *my* session hold the lock" rather than "does anyone". It relies on
;; the connection being the same session that acquired the lock - which is
;; what advisory locks require of you anyway, pool or no pool.
(def find-lock-for-id-query
  "SELECT objid FROM pg_locks WHERE locktype = 'advisory' AND objid = ? AND pid = pg_backend_pid()")

(def find-all-locks "SELECT * from pg_locks WHERE locktype = 'advisory'")

(def no-lock ::no-lock)

(defn acquire-lock
  [db-conn lock-id]
  (-> (jdbc/execute-one! db-conn [acquire-lock-query lock-id])
      :pg_try_advisory_lock
      true?))

(defn release-lock
  [db-conn lock-id]
  (-> (jdbc/execute-one! db-conn [release-lock-query lock-id])
      :pg_advisory_unlock
      true?))

(defn lock-acquired?
  "Whether this connection's session holds the lock, straight from Postgres."
  [db-conn lock-id]
  (some? (jdbc/execute-one! db-conn [find-lock-for-id-query lock-id])))

(defn release-all-locks!
  "Releases all locks hold by this connection, regardless of how many were acquired"
  [db-conn]
  (jdbc/execute-one! db-conn [release-all-locks-query]))

(defn all-locks
  [db-conn]
  (jdbc/execute! db-conn [find-all-locks]))

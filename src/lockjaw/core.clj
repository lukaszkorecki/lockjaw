(ns lockjaw.core
  (:require
   [clojure.tools.logging :as log]
   [lockjaw.operation :as operation]
   [lockjaw.util :as util]))

(defn- lock-id-for
  "Which lock an operation applies to: the component's own, or an entity lock
  when opts carry a `:name`."
  [{:keys [lock-id]} {lock-name :name}]
  (if lock-name
    (util/name-to-id lock-name)
    lock-id))

(defn start
  "Computes the lock id out of the component's name. Called for you if the
  component is part of a Component system, otherwise call it yourself."
  [{:keys [name] :as this}]
  (let [lock-id (util/name-to-id name)]
    (log/infof "name=%s status=starting lock-id=%s" name lock-id)
    (assoc this :lock-id lock-id)))

(defn stop
  "Releases every lock held by the connection and clears the lock id."
  [{:keys [name lock-id db-conn] :as this}]
  (log/warnf "name=%s status=stopping lock-id=%s cleaning all locks!" name lock-id)
  (operation/release-all-locks! db-conn)
  (assoc this :lock-id nil))

(defn create
  "Creates a lock component - a plain map carrying its behaviour as metadata.

  Needs a `:name` and, once started, a `:db-conn` holding a Postgres connection.
  The component keeps no state of its own: Postgres is the only thing that
  knows which locks are held.

  Both `lockjaw.protocol/Lockjaw` and Component's `Lifecycle` are
  `:extend-via-metadata true`, so Component is never required here - it only has
  to be on the classpath of an application that puts this in a system."
  [{:keys [name] :as args}]
  {:pre [(and (string? name) (not (.isEmpty ^String name)))]}
  (with-meta args
    {'com.stuartsierra.component/start start
     'com.stuartsierra.component/stop stop

     'lockjaw.protocol/acquire!
     (fn acquire!
       ([this] (acquire! this nil))
       ([this opts]
        (operation/acquire-lock (:db-conn this) (lock-id-for this opts))))

     'lockjaw.protocol/acquired?
     (fn acquired?
       ([this] (acquired? this nil))
       ([this opts]
        (operation/lock-acquired? (:db-conn this) (lock-id-for this opts))))

     'lockjaw.protocol/release!
     (fn release!
       ([this] (release! this nil))
       ([this opts]
        (operation/release-lock (:db-conn this) (lock-id-for this opts))))

     'lockjaw.protocol/release-all!
     (fn release-all! [this]
       (operation/release-all-locks! (:db-conn this)))}))

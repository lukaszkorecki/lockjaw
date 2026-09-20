(ns lockjaw.mock)

(defn start
  "The mock has nothing to set up - here so it can sit in a Component system
  in place of the real thing."
  [this]
  this)

(defn stop
  "The mock holds no locks, so there's nothing to release."
  [this]
  this)

(defn create
  "Creates a mock, which by default always returns true on acquring the lock.
  use (create {:always-acquire false}) to make it always fail to acquire.

  Every method of the Lockjaw protocol answers with that same flag, so a mock
  created with :always-acquire true also reports the lock as acquired."
  [options]
  (with-meta {:always-acquire? (get options :always-acquire true)}
    {'com.stuartsierra.component/start start
     'com.stuartsierra.component/stop stop

     'lockjaw.protocol/acquire!
     (fn acquire!
       ([this] (acquire! this nil))
       ([this _opts] (:always-acquire? this)))

     'lockjaw.protocol/acquired?
     (fn acquired?
       ([this] (acquired? this nil))
       ([this _opts] (:always-acquire? this)))

     'lockjaw.protocol/release!
     (fn release!
       ([this] (release! this nil))
       ([this _opts] (:always-acquire? this)))

     'lockjaw.protocol/release-all!
     (fn release-all! [this]
       (:always-acquire? this))}))

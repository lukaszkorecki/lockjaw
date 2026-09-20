(ns lockjaw.protocol)

(defprotocol Lockjaw
  :extend-via-metadata true
  (acquire! [this] [this opts]
    "Tries to get the lock. With no opts it locks on the component's own name,
    pass `{:name \"user-123\"}` to lock on an arbitrary entity instead.")
  (acquired? [this] [this opts]
    "Checks whether this connection's session currently holds the lock,
    according to Postgres.")
  (release! [this] [this opts]
    "Releases the lock. Takes the same opts as `acquire!`.")
  (release-all! [this]
    "Releases every advisory lock held by this component's connection."))

;; Both macros take a map as their first argument - :lock is the component and
;; the rest is passed to acquire!/release! as opts. Keeping the lock inside that
;; map is what lets the body follow without any ambiguity about where opts end.

(defmacro with-lock
  "Run the code if the lock is obtained, otherwise return
  `:lockjaw.operation/no-lock`.

  ```clojure
  (with-lock {:lock a-lock} (do-work))
  (with-lock {:lock a-lock :name \"user-123\"} (do-work))
  ```"
  [opts & body]
  `(let [opts# ~opts
         lock# (:lock opts#)
         lock-opts# (dissoc opts# :lock)]
     (if (acquire! lock# lock-opts#)
       (do
         ~@body)
       :lockjaw.operation/no-lock)))

(defmacro with-lock!
  "Like *with-lock* but releases the lock after use."
  [opts & body]
  `(let [opts# ~opts
         lock# (:lock opts#)
         lock-opts# (dissoc opts# :lock)]
     (try
       (if (acquire! lock# lock-opts#)
         (do
           ~@body)
         :lockjaw.operation/no-lock)
       (finally
         (release! lock# lock-opts#)))))

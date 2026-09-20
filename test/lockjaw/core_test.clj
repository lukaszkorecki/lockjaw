(ns lockjaw.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.stuartsierra.component :as component]
   [lockjaw.core]
   [lockjaw.protocol :as lock]
   [lockjaw.test-system :as ts]))

(def sys (atom nil))

(use-fixtures :each (fn [test-fn]
                      (ts/start! sys
                                 {:lock-1 (component/using
                                           (lockjaw.core/create {:name "lock-1"})
                                           [:db-conn])
                                  :lock-2 (component/using
                                           (lockjaw.core/create {:name "lock-1"})
                                           {:db-conn :db-conn-2})})
                      (test-fn)
                      (ts/stop! sys)))

(deftest component-usage
  (testing "it generates a int lock id"
    (is (number? (:lock-id (:lock-1 @sys))))
    (is (number? (:lock-id (:lock-2 @sys))))
    (is (=
         (:lock-id (:lock-2 @sys))
         (:lock-id (:lock-1 @sys))))
    (is (= 3379800295
           (:lock-id (:lock-1 @sys)))))
  (testing "lock-1 gets a lock, lock-2 doesnt"
    (is (lock/acquire! (:lock-1 @sys)))
    (is (false? (lock/acquire! (:lock-2 @sys))))
    (is (lock/release! (:lock-1 @sys))))
  (testing "gets and releases entity locks by name"
    (is (lock/acquire! (:lock-1 @sys) {:name "foo"}))
    (is (false? (lock/acquire! (:lock-2 @sys) {:name "foo"})))
    (is (lock/release! (:lock-1 @sys) {:name "foo"}))
    (is (false? (lock/release! (:lock-2 @sys) {:name "foo"}))))
  (testing "checks if lock is acquired"
    (is (lock/acquire! (:lock-1 @sys)))
    (is (lock/acquired? (:lock-1 @sys))))
  (testing "checks if an entity lock is acquired"
    (is (lock/acquire! (:lock-1 @sys) {:name "alock"}))
    (is (lock/acquired? (:lock-1 @sys) {:name "alock"}))
    (is (false? (lock/acquired? (:lock-1 @sys) {:name "no lock"})))))

(deftest acquired?-is-session-scoped-test
  (testing "reports what this component's session holds, not what anyone holds"
    (is (lock/acquire! (:lock-1 @sys) {:name "shared"}))
    (is (lock/acquired? (:lock-1 @sys) {:name "shared"}))
    ;; lock-2 is on a separate pool, so a separate session - the advisory lock
    ;; is very much taken in Postgres, just not by it
    (is (false? (lock/acquired? (:lock-2 @sys) {:name "shared"})))
    (is (false? (lock/acquire! (:lock-2 @sys) {:name "shared"})))
    (is (lock/release! (:lock-1 @sys) {:name "shared"}))
    (is (false? (lock/acquired? (:lock-1 @sys) {:name "shared"})))))

(def ^:private held-timeout-ms 5000)

(deftest component-metadata-test
  (testing "carries an implementation for every protocol method, plus Component's lifecycle"
    ;; a misspelled symbol here wouldn't throw - Component's default Lifecycle
    ;; is a no-op on Object, so start/stop would just silently do nothing
    (is (= (into #{'com.stuartsierra.component/start
                   'com.stuartsierra.component/stop}
                 (map #(symbol "lockjaw.protocol" (name %)))
                 (keys (:sigs lock/Lockjaw)))
           (set (keys (meta (lockjaw.core/create {:name "a-lock"}))))))))

(deftest handy-macros
  (testing "nice macro ensures lock clean up"
    ;; the promise is delivered from inside the macro body, so it only fires
    ;; once the lock is actually held - waiting on it (instead of sleeping)
    ;; keeps the test free of races
    (let [held (promise)
          fut (future
                (lock/with-lock {:lock (:lock-1 @sys)}
                  (deliver held true)
                  (Thread/sleep 50)
                  ::done))]
      (is (true? (deref held held-timeout-ms false)))
      (is (= :lockjaw.operation/no-lock
             (lock/with-lock! {:lock (:lock-2 @sys)}
               ::invalid)))
      (is (= ::done
             @fut))))
  (testing "nice macro with name ensures lock clean up too"
    (let [lock-name "a-nice-lock"
          held (promise)
          fut (future
                (lock/with-lock {:lock (:lock-1 @sys) :name lock-name}
                  (deliver held true)
                  (Thread/sleep 50)
                  ::done))]
      (is (true? (deref held held-timeout-ms false)))
      (is (= :lockjaw.operation/no-lock
             (lock/with-lock! {:lock (:lock-2 @sys) :name lock-name}
               ::invalid)))
      (is (= ::done
             @fut))))
  (testing "the opts map can be built at runtime, it isn't required to be a literal"
    (let [opts {:lock (:lock-1 @sys) :name "runtime-opts"}]
      (is (= ::done (lock/with-lock! opts ::done))))))

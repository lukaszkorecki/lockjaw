(ns lockjaw.operation-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [lockjaw.operation :as operation]
   [lockjaw.test-system :as test-system]))

(def system (atom nil))

(use-fixtures :each (fn [test-fn]
                      (test-system/start! system {})
                      (test-fn)
                      (test-system/stop! system)))

(deftest lock-operations
  (testing "one conn acquires lock, other doesnt"
    (is (operation/acquire-lock (:db-conn @system) 13))
    (is (= 1
           (count (operation/all-locks (:db-conn @system)))))
    (is (not (operation/acquire-lock (:db-conn-2 @system) 13))))
  (testing "releases lock, nobody hs it"
    (is (operation/release-lock (:db-conn @system) 13))
    (is (nil? (seq (operation/all-locks (:db-conn @system)))))))

(deftest lock-acquired?-is-session-scoped-test
  (testing "reports what this session holds, not what anyone holds"
    (is (operation/acquire-lock (:db-conn @system) 77))
    (is (operation/lock-acquired? (:db-conn @system) 77))
    ;; the lock is very much taken in Postgres, but not by this session
    (is (seq (operation/all-locks (:db-conn-2 @system))))
    (is (false? (operation/lock-acquired? (:db-conn-2 @system) 77)))
    (is (operation/release-lock (:db-conn @system) 77)))
  (testing "and stops reporting it once released"
    (is (false? (operation/lock-acquired? (:db-conn @system) 77)))))

(deftest continous-acquiring-and-relesing
  (testing "it can continously acquire the lock and it's ok"
    (is (operation/acquire-lock (:db-conn @system) 27))
    (is (operation/acquire-lock (:db-conn @system) 27))
    (is (operation/acquire-lock (:db-conn @system) 27))
    (is (operation/acquire-lock (:db-conn @system) 27))
    (is (operation/lock-acquired? (:db-conn @system) 27))
    (testing "and Postgres reports it as a single lock, however many times it was taken"
      (is (= [27]
             (map :pg_locks/objid (operation/all-locks (:db-conn @system))))))
    (operation/release-lock (:db-conn @system) 27)
    (testing "advisory locks stack, so one release isn't enough to let it go"
      (is (operation/lock-acquired? (:db-conn @system) 27))
      (is (= [27]
             (map :pg_locks/objid (operation/all-locks (:db-conn @system))))))
    (operation/release-all-locks! (:db-conn @system))
    (is (false? (operation/lock-acquired? (:db-conn @system) 27)))
    (is (= []
           (map :pg_locks/objid (operation/all-locks (:db-conn @system)))))))

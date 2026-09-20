(ns lockjaw.mock-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [lockjaw.mock :as mock]
   [lockjaw.protocol :as lock]))

(defn- call-every-protocol-method
  "Calls every method of the Lockjaw protocol on a-lock and returns a map of
  method name -> returned value.

  Driven off the protocol itself, so a method added to Lockjaw but not to the
  mock fails here instead of throwing AbstractMethodError in someone's tests."
  [a-lock]
  (into {}
        (map (fn [[method-name {:keys [arglists]}]]
               (let [f @(requiring-resolve (symbol "lockjaw.protocol" (name method-name)))
                     lock-name-args (repeat (dec (count (first arglists))) "a-lock-name")]
                 [method-name (apply f a-lock lock-name-args)])))
        (:sigs lock/Lockjaw)))

(deftest always-acquiring-mock-test
  (testing "every protocol method reports the lock as held"
    (is (= {:acquire! true
            :acquire-by-name! true
            :acquired? true
            :acquired-by-name? true
            :release! true
            :release-by-name! true
            :release-all! true}
           (call-every-protocol-method (mock/create {:always-acquire true}))))))

(deftest never-acquiring-mock-test
  (testing "every protocol method reports the lock as unavailable"
    (is (= {:acquire! false
            :acquire-by-name! false
            :acquired? false
            :acquired-by-name? false
            :release! false
            :release-by-name! false
            :release-all! false}
           (call-every-protocol-method (mock/create {:always-acquire false}))))))

(deftest default-mock-test
  (testing "acquires the lock unless told otherwise"
    (is (true? (lock/acquire! (mock/create {}))))
    (is (true? (lock/acquired? (mock/create {}))))))

(deftest mock-works-with-locking-macros-test
  (testing "the macros run the body when the mock acquires the lock"
    (is (= ::done (lock/with-lock (mock/create {:always-acquire true}) ::done)))
    (is (= ::done (lock/with-lock! (mock/create {:always-acquire true}) ::done)))
    (is (= ::done (lock/with-named-lock (mock/create {:always-acquire true}) "a-name" ::done)))
    (is (= ::done (lock/with-named-lock! (mock/create {:always-acquire true}) "a-name" ::done))))
  (testing "and bail out when it doesn't"
    (is (= :lockjaw.operation/no-lock (lock/with-lock (mock/create {:always-acquire false}) ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-lock! (mock/create {:always-acquire false}) ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-named-lock (mock/create {:always-acquire false}) "a-name" ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-named-lock! (mock/create {:always-acquire false}) "a-name" ::done)))))

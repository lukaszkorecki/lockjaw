(ns lockjaw.mock-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.stuartsierra.component :as component]
   [lockjaw.mock :as mock]
   [lockjaw.protocol :as lock]))

(defn- call-every-arity
  "Calls every arity of every method of the Lockjaw protocol on a-lock and
  returns a map of [method-name arity] -> returned value.

  Driven off the protocol itself, so a method or arity added to Lockjaw but not
  to the mock fails here instead of throwing AbstractMethodError in someone
  else's test suite."
  [a-lock]
  (into {}
        (mapcat (fn methods-of [[method-name {:keys [arglists]}]]
                  (let [f @(requiring-resolve (symbol "lockjaw.protocol" (name method-name)))]
                    (map (fn call-arity [arglist]
                           (let [opts-args (repeat (dec (count arglist)) {:name "a-lock-name"})]
                             [[method-name (count arglist)] (apply f a-lock opts-args)]))
                         arglists))))
        (:sigs lock/Lockjaw)))

(deftest always-acquiring-mock-test
  (testing "every protocol method reports the lock as held"
    (is (= {[:acquire! 1] true
            [:acquire! 2] true
            [:acquired? 1] true
            [:acquired? 2] true
            [:release! 1] true
            [:release! 2] true
            [:release-all! 1] true}
           (call-every-arity (mock/create {:always-acquire true}))))))

(deftest never-acquiring-mock-test
  (testing "every protocol method reports the lock as unavailable"
    (is (= {[:acquire! 1] false
            [:acquire! 2] false
            [:acquired? 1] false
            [:acquired? 2] false
            [:release! 1] false
            [:release! 2] false
            [:release-all! 1] false}
           (call-every-arity (mock/create {:always-acquire false}))))))

(deftest default-mock-test
  (testing "acquires the lock unless told otherwise"
    (is (true? (lock/acquire! (mock/create {}))))
    (is (true? (lock/acquired? (mock/create {}))))))

(deftest mock-works-with-locking-macros-test
  (testing "the macros run the body when the mock acquires the lock"
    (is (= ::done (lock/with-lock {:lock (mock/create {:always-acquire true})} ::done)))
    (is (= ::done (lock/with-lock! {:lock (mock/create {:always-acquire true})} ::done)))
    (is (= ::done (lock/with-lock {:lock (mock/create {:always-acquire true}) :name "a-name"} ::done)))
    (is (= ::done (lock/with-lock! {:lock (mock/create {:always-acquire true}) :name "a-name"} ::done))))
  (testing "and bail out when it doesn't"
    (is (= :lockjaw.operation/no-lock (lock/with-lock {:lock (mock/create {:always-acquire false})} ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-lock! {:lock (mock/create {:always-acquire false})} ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-lock {:lock (mock/create {:always-acquire false}) :name "a-name"} ::done)))
    (is (= :lockjaw.operation/no-lock (lock/with-lock! {:lock (mock/create {:always-acquire false}) :name "a-name"} ::done)))))

(deftest mock-metadata-test
  (testing "carries an implementation for every protocol method, plus Component's lifecycle"
    (is (= (into #{'com.stuartsierra.component/start
                   'com.stuartsierra.component/stop}
                 (map #(symbol "lockjaw.protocol" (name %)))
                 (keys (:sigs lock/Lockjaw)))
           (set (keys (meta (mock/create {})))))))
  (testing "is a plain map"
    (is (map? (mock/create {})))))

(deftest mock-in-a-component-system-test
  (testing "drops into a Component system in place of the real lock"
    (let [sys (component/start
               (component/map->SystemMap
                {:a-lock (mock/create {:always-acquire true})}))]
      (is (true? (lock/acquire! (:a-lock sys))))
      (is (= ::done (lock/with-lock! {:lock (:a-lock sys)} ::done)))
      (is (some? (component/stop sys))))))

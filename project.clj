(defproject org.clojars.lukaszkorecki/lockjaw "1.0.0"
  :description "Postgres Advisory Locks as a Component"
  :url "https://github.com/lukaszkorecki/lockjaw"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"
            :year 2018
            :key "mit"}
  :deploy-repositories {"clojars" {:sign-releases false
                                   :username :env/clojars_username
                                   :password :env/clojars_password}}

  ;; Component is deliberately *not* here - both it and lockjaw's own protocol
  ;; are :extend-via-metadata true, so the lock component is a plain map and
  ;; Component is only needed by applications that put it in a system.
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [org.clojure/tools.logging "1.3.1"]]
  :profiles {:dev
             {:resource-paths ["dev-resources"]
              :dependencies [[ch.qos.logback/logback-classic "1.6.3"]
                             ;; used to wire up the test system
                             [com.stuartsierra/component "1.2.0"]
                             ;; the PG driver and a connection pool, used to
                             ;; build the test system
                             [com.zaxxer/HikariCP "7.1.0"]
                             [org.postgresql/postgresql "42.7.13"]]}})

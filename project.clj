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

  :dependencies [[org.clojure/clojure "1.12.6"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [com.stuartsierra/component "1.2.0"]]
  :profiles {:dev
             {:resource-paths ["dev-resources"]
              :dependencies [[ch.qos.logback/logback-classic "1.6.3"]
                             ;; the PG driver and a connection pool, used to
                             ;; build the test system
                             [com.zaxxer/HikariCP "7.1.0"]
                             [org.postgresql/postgresql "42.7.13"]
                             [org.clojure/tools.logging "1.3.1"]]}})

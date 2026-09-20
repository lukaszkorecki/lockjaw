# lockjaw

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.lukaszkorecki/lockjaw.svg)](https://clojars.org/org.clojars.lukaszkorecki/lockjaw)
[![Test](https://github.com/lukaszkorecki/lockjaw/actions/workflows/test.yml/badge.svg)](https://github.com/lukaszkorecki/lockjaw/actions/workflows/test.yml)

<img src="https://vignette.wikia.nocookie.net/marveldatabase/images/5/51/Lockjaw_Vol_1_1_Textless.jpg/revision/latest/scale-to-width-down/670?cb=20171122020841" align="right" height=210 />

Locks, backed by Postgres, usable as a Component

## About

Lockjaw started at [EnjoyHQ](https://enjoyhq.com) (fka NomNom Insights) as
[`nomnom/lockjaw`](https://github.com/nomnom-insights/nomnom.lockjaw).
This fork takes over maintenance of the library - it is released to
Clojars under a new group id, `org.clojars.lukaszkorecki/lockjaw`, and
picks up where `nomnom/lockjaw` 0.3.1 left off.

## Installation

Leiningen/Boot:

```clojure
[org.clojars.lukaszkorecki/lockjaw "1.0.0"]
```

deps.edn:

```clojure
org.clojars.lukaszkorecki/lockjaw {:mvn/version "1.0.0"}
```

## Intro

Lockjaw is a simple [Component](https://github.com/stuartsierra/component) which uses [Postgres' advisory locks](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS) which are managed by Postgres itself and are really lightweight.
They are not meant to be used for row level locking, but for implementing concurrency control primitives in applications.

Intended usage is to ensure that at any given time, only one instance of *something* is doing the work, usually it's for ensuring that only one scheduler at a time is queueing up jobs for periodical processing. See [how it integrates with Eternity](https://github.com/nomnom-insights/nomnom.eternity#with-lock-eternitymiddlewarewith-lock)

### Locking advice

Ideally, you do not do much work while holding the lock. E.g. ensuring that your application pushed only 1 job to a queue or called an endpoint once. Relying on performing long running tasks while holding the lock is not advised. Use it as a coordination mechanism, not the business logic.

## How to use it?

The usage boils down to:

- creating a lock instance with a lock name `(def lock-component (lockjaw.core/create {:name "delete-account"}))`
- `(lockjaw.protocol/acquire! lock-component)` - acquire the lock
- `(lockjaw.protocol/release! lock-component)` - release the lock (usually not needed, but just in case)

`acquire!`, `acquired?` and `release!` all take an optional opts map as a second argument. Without it they work on the component's own lock, with `{:name "..."}` they work on an entity lock - see ["Dynamic" locks](#dynamic-locks) below.

Internally we use a lock ID which is an integer. The lock is established **per connection** (session), meaning the following is true:

- if the current connection holds the lock for given ID, acquiring it again will still hold the lock and return true
- if the current connection doesn't hold the lock for given ID, attempting to acquire it will return false, *unless the lock was released in the meantime*
- if the connection is stopped, in a clean way (system/component stop) or the JVM process exits (crash, or restart) - **the lock is released**

It's important that you use the right connection type, especially when using tools like pgBouncer, as they might mess with the advisory locks and when they are (not) acquired. [See here for more details](https://electron0zero.xyz/blog/til-connection-pooling-and-pgbouncer).

### Lock IDs

On Postgres level lock ids are just integers, and Lockjaw makes it easier to create them - we create an id out of a provided `name` configuration option.
We use the CRC algorithm for ensuring that a given string always produces the same integer. Inspired by [Zencoder's Locker library](https://github.com/zencoder/locker/blob/master/lib/locker/advisory.rb#L97-L101).

### "Dynamic" locks

While you can set the lock name while creating the component, you might need to dynamically create locks to ensure that only a single user account is being processed at a given time. In that case you cannot create a component for each user ID, so pass the name at call time instead - every method takes an optional opts map:

```clojure
(lock/acquire! a-lock {:name (str "user:" (:id user))})
(lock/release! a-lock {:name (str "user:" (:id user))})
```

Same caveats apply as to 'default' locks: do not hold them for too long, and use them as a coordination mechanism instead.

### Checking whether you hold a lock

`acquired?` asks Postgres whether **this connection's session** holds the lock:

```sql
SELECT objid FROM pg_locks
 WHERE locktype = 'advisory' AND objid = ? AND pid = pg_backend_pid()
```

The `pid = pg_backend_pid()` part is what makes it mean "I hold this" rather than "somebody, somewhere holds this". The component keeps no state of its own, so the answer stays right even when the connection pool retires a connection underneath you - the lock really is gone at that point, and `acquired?` says so.

Note that advisory locks stack: taking the same lock twice needs two releases before Postgres lets it go. `pg_locks` reports it as a single lock either way, so if you need to know how deep you are, that's yours to track. Better still, don't nest them.

If you want to know which *instance* of your service holds a lock, Postgres can tell you directly - set `ApplicationName` on your JDBC connection and join `pg_stat_activity`:

```sql
SELECT l.objid, a.application_name, a.pid
  FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
 WHERE l.locktype = 'advisory'
```

## Usage

```clojure
(require '[com.stuartsierra.component :as component]
         '[lockjaw.core]
         '[lockjaw.protocol :as lock])

(def a-lock
  (component/start
   (component/using
    ;; unique id, per service, in 99% of the cases service name is ok
    (lockjaw.core/create {:name "some-service"})
    [:db-conn]))) ;; assumes a connection pool is here, can be any other JDBC Postgres driver though!

;; explicitly:
(if (lock/acquire! a-lock)
  (try
    (log/info "doing some work, exclusively")
    (do-work)
    (finally ;; release when done
      (lock/release! a-lock)))
  (log/warn "someone else is doing work"))

  ;;; and with a simple macro:

(lock/with-lock {:lock a-lock}
  (do-some-work))

;; if the lock is NOT acquired, it will return right away with :lockjaw.operation/no-lock keyword


;;; 'dynamic' locking - same macro, name the entity you're locking on

(lock/with-lock! {:lock (:a-lock component) :name "delete-account:1"}
  (do-delete component {:account-id 1}))
```

The macros take a map as their first argument: `:lock` is the component and anything
else is passed through as opts. Keeping the lock inside that map is what lets the body
follow without any ambiguity about where the opts end - and the map is an ordinary
expression, so it can be built at runtime.

`:db-conn` can be anything `next.jdbc` accepts as a datasource - see `test/lockjaw/test_system.clj` for a minimal, HikariCP-backed connection pool component.

### Using it without Component

Lockjaw does **not** depend on Component. The lock is a plain map that carries its
behaviour as metadata, and both `lockjaw.protocol/Lockjaw` and Component's own
`Lifecycle` are `:extend-via-metadata true` - so a Component system picks the lock up
automatically when it's on your classpath, and when it isn't, you just call the
lifecycle functions yourself:

```clojure
(def a-lock
  (lockjaw.core/start
   (assoc (lockjaw.core/create {:name "some-service"}) :db-conn a-datasource)))

(lock/with-lock! {:lock a-lock}
  (do-some-work))

(lockjaw.core/stop a-lock)
```

The only runtime dependencies are `next.jdbc` and `org.clojure/tools.logging`.

## Mock component

Lockjaw ships with a mock component, which doesn't depend on Postgres and will always
acquire the lock. It can also be configured to never acquire it:

```clojure

(let [always-lock (lockjaw.mock/create {:always-acquire true})
      never-lock (lockjaw.mock/create {:always-acquire false})]
  (lock/acquire! always-lock) ;; => true
  (lock/acquire! never-lock) ;; => false

  ;; you can also name the entity you're locking on:
  (lock/acquire! always-lock {:name "who?"}) ;; => true

  ;; ...and the rest of the protocol answers with the same flag:
  (lock/acquired? always-lock) ;; => true
  (lock/acquired? never-lock {:name "who?"}) ;; => false
  (lock/release-all! always-lock)) ;; => true
```

Like the real thing, the mock is a plain map carrying its behaviour as metadata, so it
drops into a Component system wherever the Postgres-backed lock would go - its
`start`/`stop` simply do nothing.

# Testing

Tests need a running Postgres instance - there's a `docker-compose.yml` for that:

```sh
docker compose up -d
lein test
docker compose down
```

It listens on **port 6001**, not the default 5432, so it won't clash with a Postgres
you're running for another project.

Connection details are read from the environment, and default to what compose sets up:

| variable | default |
| --- | --- |
| `POSTGRES_USER` | `lockjaw` |
| `POSTGRES_PASSWORD` | `password` |
| `POSTGRES_HOST` | `127.0.0.1` |
| `POSTGRES_PORT` | `6001` |
| `POSTGRES_DB` | `lockjaw_test` |

> Ensure no other database connections are currently holding advisory locks when running tests.

# Change log

- 2026-09-20 - 1.0.0 - first release of the fork, published as `org.clojars.lukaszkorecki/lockjaw`. Updates dependencies (Clojure 1.12, next.jdbc), drops the `nomnom/utility-belt.sql` test dependency, moves CI to GitHub Actions on JVM 25.
  - **no longer depends on Component** - the lock is a plain map extending both `lockjaw.protocol/Lockjaw` and Component's `Lifecycle` via metadata, so it still drops into a Component system unchanged, but works without one. `lockjaw.core/create` returns a map rather than a `Lockjaw` record, so anything reaching for `lockjaw.core.Lockjaw`, `->Lockjaw` or `map->Lockjaw` needs updating - calling it through the protocol is unaffected.
  - **slimmer protocol** - `acquire!`, `acquired?` and `release!` each take an optional opts map, so `acquire-by-name!`, `acquired-by-name?` and `release-by-name!` are gone: `(acquire! lock)` for the component's own lock, `(acquire! lock {:name "user-123"})` for an entity lock.
  - the four locking macros collapse to `with-lock` and `with-lock!`, which now take `{:lock a-lock :name "optional"}` as their first argument.
  - **the global `lockjaw.operation/registry` atom is gone**, along with the acquire counts it tracked. Postgres is the only thing that knows which locks are held, so there's no local state to go stale when a pooled connection is retired.
  - `acquired?` now filters `pg_locks` by `pg_backend_pid()`. It used to return true when *any* session held the lock, anywhere - it now means "this connection's session holds it".
  - the mock now implements the whole `Lockjaw` protocol - `acquired?`, `acquired-by-name?` and `release-all!` used to throw `AbstractMethodError` - and is built on the same metadata plumbing, so it's a map too and can stand in for the real lock inside a Component system. `lockjaw.mock.LockjawMock` no longer exists.

<details>
<summary>Pre-fork releases, as <code>nomnom/lockjaw</code></summary>

- 2022-02-08 - 0.3.1 - adds "acquired?" functions to check if a lock was already acquired. Updates dependencies (next.jdbc, logback-classic, tools.logging)
- 2021-12-08 - 0.3.0 - "dynamic" locks, updated dependencies
- 2021-11-09 - 0.2.1-SNAPSHOT, updates dependencies, includes `next.jdbc`, allows passing lock-name when asking for lock.
- *unreleased* - 0.2.0-SNAPSHOT, switches to `next.jdbc`
- 2019-10-24 - 0.1.2, Initial public offering

</details>

# Authors

<sup>In alphabetical order</sup>

- [Afonso Tsukamoto](https://github.com/AfonsoTsukamoto)
- [Łukasz Korecki](https://github.com/lukaszkorecki)
- [Marketa Adamova](https://github.com/MarketaAdamova)

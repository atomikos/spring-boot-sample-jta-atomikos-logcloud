# Crash one node mid-commit — and watch another node finish it

A Spring Boot 4 shop that saves an order in **PostgreSQL** and sends an order
message to **ActiveMQ Artemis**, in one XA transaction coordinated by
**Atomikos ExtremeTransactions**. Run two nodes, kill one at the worst possible
moment — after both resources *prepared*, before the commit — and the other node
finishes the transaction. No restart of the crashed node, no local disk.

That works because of **LogCloud**: the Atomikos transaction log lives in
PostgreSQL instead of in a `transaction-logs/` folder on the crashed machine.
Every node shares it, and one of them leads recovery for all.

This is the sequel to the open-source
[spring-boot-xa-crash-recovery-demo](https://github.com/atomikos/spring-boot-xa-crash-recovery-demo),
which shows the same crash recovered by *restarting the same node with its disk*.
Here, the node never comes back — the situation of a crashed Kubernetes pod.

## About the dependencies

Every `com.atomikos` dependency in this project's `pom.xml` (not only `transactions-logcloud`) resolves from the ExtremeTransactions distribution at the version pinned in the POM, including the base JTA and Spring Boot integration artifacts.
None of these coordinates, at this version, are published to Maven Central. The free trial link below is required to build the whole project, not only its LogCloud-specific part.
Latest stable version: <!--VERSION-->6.0.120<!--/VERSION-->

## Run it

You need Java 17+, Docker (Maven comes with the wrapper, `./mvnw`), and an
[ExtremeTransactions free trial](https://www.atomikos.com/Main/ExtremeTransactionsFreeTrial?Source=github)
(it gives you access to the Maven repository with the `com.atomikos` artifacts).

Start two nodes, each in its own terminal. The first one also starts PostgreSQL
and Artemis (`compose.yaml`) and creates the tables, so start the second one
once the first is up:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

On Windows, use `.\mvnw` instead of `./mvnw`, and keep the quotes: without them,
PowerShell splits the argument at the dot.

Open **http://localhost:8080/**. The page shows both nodes (which one is up,
which one is the recovery leader) and the shared state in PostgreSQL: orders and
shipments, in-doubt transactions, and the LogCloud log itself. It reads through
any node that is still up, so it keeps working when one dies. (More nodes:
start them on other ports and open `http://localhost:8080/?nodes=8080,8081,8082`.)

Each order is a row in PostgreSQL plus a message on the `orders` queue; the
`ShippingListener` on whichever node gets the message turns it into a shipment.
Every order should be shipped exactly once.

## The demo

1. **Place an order** on either node. It is stored and shipped; the table shows
   which node placed it and which node shipped it.

2. On the node that is the **👑 recovery leader**, click **💣 Crash after prepare**.
   That node halts in the middle of the commit: PostgreSQL and Artemis have both
   *prepared*, the commit decision is logged, but PostgreSQL has not got its
   commit yet. The page now shows:

   | | |
   |---|---|
   | Orders | the new order is **not there** — it is prepared, not committed |
   | In-doubt transactions | **one**, straight from `pg_prepared_xacts`, holding its locks |
   | LogCloud log | the transaction, **`COMMITTING`** — the decision survived the crash, in PostgreSQL |
   | Recovery leader | still the dead node, but its lease is running out |

3. **Wait** — do not restart anything. Within about 10 seconds the dead node's
   lease expires, the surviving node becomes the recovery leader, reads the
   `COMMITTING` decision from the shared log, and commits the prepared branches.
   The order appears — *placed by* the crashed node, *shipped by* the survivor —
   and nothing is left in doubt.

Crash the other node (the one that is *not* the leader) and the same happens
without a takeover: the leader is alive and commits the crashed node's
transaction on its next recovery pass, also within about 10 seconds.

Prefer the terminal?

```bash
curl -X POST "localhost:8080/orders?product=book"                       # place an order
curl -X POST "localhost:8080/orders?product=book&crash=after-prepare"   # crash node 8080 mid-commit
curl localhost:8081/status                                              # what node 8081 sees
```

The crash point is exact, not a random `kill -9` (which almost always misses the
window): [`CrashSwitch`](src/main/java/com/atomikos/demo/CrashSwitch.java) and
[`CrashAfterPrepareConfiguration`](src/main/java/com/atomikos/demo/CrashAfterPrepareConfiguration.java)
wrap the PostgreSQL `XAConnection` to halt the JVM right before the phase-2
commit, for that one request only. Demo plumbing — a real application needs none of it.

## Kill a real container

The crash above is the app halting itself. To kill a node from the outside — as
an orchestrator kills a pod, taking its local disk with it — run the two nodes as
containers instead (stop the `./mvnw` nodes first: same ports, same database):

```bash
./mvnw package -DskipTests                  # the image is built from this jar
docker compose --profile nodes up --build -d
```

The nodes are `node-a` (http://localhost:8080/) and `node-b` (:8081). In a
container, the page also offers **⏸ Freeze after prepare**: the node stops
dead (`SIGSTOP`, the whole JVM) at the same point as the crash — both resources
prepared, the decision logged, nothing committed — and waits for you:

```bash
docker kill node-a
```

With LogCloud, you don't even have to be quick: a frozen node is a hung node, so
its lease runs out and the other node commits its transaction about 15 seconds
after the freeze, killed or not. Freeze the follower instead and the leader
finishes it within about half a minute.

## And without LogCloud?

The same two containers, each with its transaction log in a file on its own disk
(`/app/transaction-logs`) — what you get with the open-source edition:

```bash
docker compose --profile nodes down -v      # never mix the two modes in one database
TRANSACTION_LOG=file docker compose --profile nodes up -d
```

(On Windows PowerShell: `$env:TRANSACTION_LOG="file"; docker compose --profile nodes up -d`.)

Each node now has its own transaction manager name (`node-a`, `node-b`), as it
must with file logs. Freeze `node-a` after the prepare and `docker kill node-a`:

- **The other node can do nothing.** The commit decision is on `node-a`'s disk.
  The transaction stays in `pg_prepared_xacts`, holding its locks, and the order
  never appears — for as long as `node-a` is gone.
- **`node-a` comes back with its disk** (`docker start node-a`): it reads its log
  and commits the transaction within about 10 seconds.
- **`node-a` comes back without its disk** — a rescheduled pod
  (`docker rm node-a`, then `docker compose --profile nodes up -d node-a`): it has
  no record of the decision, and about 5 minutes later it rolls the prepared
  branches back. An order whose commit had been decided is gone — no order, no
  shipment.

Switch back with `docker compose --profile nodes down -v` and start without
`TRANSACTION_LOG`. The page shows which mode is running.

## How it's wired

The business code is one `@Transactional` method
([`OrderService`](src/main/java/com/atomikos/demo/OrderService.java)):

```java
@Transactional
public long placeOrder(String product) {
    PurchaseOrder order = orders.save(new PurchaseOrder(product, node)); // 1. PostgreSQL
    jms.convertAndSend("orders", order.getId());                         // 2. Artemis
    return order.getId();
}
```

With `transactions-spring-boot4-starter` on the classpath, that is one XA
transaction across PostgreSQL and Artemis. Adding `transactions-logcloud` moves
the transaction log from local files into the database.

What makes the nodes one cluster ([`application.yml`](src/main/resources/application.yml)):

- **The same `atomikos.properties.transaction-manager-unique-name`** (`logcloud`) on
  every node: they form one LogCloud *recovery domain* and share one log.
  The `LEADER` table elects the one node that runs recovery for the whole domain.
- **The same resource names** (`shopDB`, `shopJMS`) on every node: a node can only
  recover the prepared branches of a resource it knows by the same name.
- **The LogCloud datasource** is the application's own PostgreSQL
  ([`LogCloudConfiguration`](src/main/java/com/atomikos/demo/LogCloudConfiguration.java)),
  so no second database is needed. [`schema.sql`](src/main/resources/schema.sql)
  creates its tables; every node runs it at startup, so it is idempotent.
- **`ddl-auto: update`**, never `create-drop`: a node that stops must not drop the
  tables under the nodes that keep running.
- PostgreSQL needs `max_prepared_transactions > 0` for XA (it is 0 by default);
  [`compose.yaml`](compose.yaml) sets it.

The timeouts are shortened so that the takeover happens within seconds during a
demo (`default-jta-timeout` and `recovery.delay` of 5 seconds; with these
settings the leader lease lasted 5 to 10 seconds in our runs); production would
use its real values.

`LogCloudConfiguration` exists because the `LogCloudDataSourceConfiguration` that
ships in `transactions-spring-boot-logcloud` 6.0.120 is compiled against the
pre-Boot 4 package of `DataSourceProperties`. It does the same thing on Boot 4.

## Tests

```bash
./mvnw test
```

Testcontainers starts PostgreSQL and Artemis (the tests are skipped without
Docker). The crash switch throws instead of halting there, since a halt would kill
the test runner too, so the tests check recovery within one node:

- an order is stored and shipped;
- a crash after the prepare leaves an in-doubt transaction in `pg_prepared_xacts`
  and a `COMMITTING` decision in the LogCloud log in PostgreSQL — not in a local
  file — and Atomikos then completes it.

## Notes

- Without a licence, ExtremeTransactions logs a `NoSubscriptionFoundException`
  at startup; the trial licence removes it.
- A `COMMITTING` row also appears briefly for every transaction that completes
  normally: the decision is written before phase 2 and cleaned up shortly after.
  Only a row that stays while its node is down is a transaction left halfway.
- **A hung node and Artemis.** The nodes connect to Artemis with
  `connectionTTL=3000`, so the broker drops a frozen node after about 3 seconds —
  well before its lease expires and another node recovers its transactions. With
  a TTL close to the lease time (we tried 10 seconds), Artemis 2.40 and 2.44
  intermittently failed to roll back the frozen node's session
  (`AMQ222104 ... Cannot find tx with id`) while the other node was recovering,
  and the committed order's message stayed stuck in "delivering", never shipped.
  A node that dies (rather than hangs) closes its connection at once and was
  never affected.

## Reset

```bash
docker compose --profile nodes down -v    # stops the nodes, PostgreSQL and Artemis, and deletes their data, including the LogCloud log
```

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

You need Java 17+, Maven, Docker, and an
[ExtremeTransactions free trial](https://www.atomikos.com/Main/ExtremeTransactionsFreeTrial?Source=github)
(it gives you access to the Maven repository with the `com.atomikos` artifacts).

Start two nodes, each in its own terminal. The first one also starts PostgreSQL
and Artemis (`compose.yaml`):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

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
mvn test
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

## Reset

```bash
docker compose down -v    # stops PostgreSQL and Artemis and deletes their data, including the LogCloud log
```

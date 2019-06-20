# spring-boot-sample-jta-atomikos-logcloud
Sample project showing how easy it is to do XA with clustered, cloud-native transaction logging and recovery.

## Highlights

This example is based on the original Spring Boot / Atomikos example, but changed as follows:

* Changed the H2 database from in-memory to persistent storage.
* Updated the Atomikos version to 5.0.x with the LogCloud capabilities.
* Added the transactions-logcloud dependency in the POM, so the LogCloud is activated automatically and overrides file-based logging.
* Modified application.properties to configure the LogCloud to use the application's datasource for logging and recovery (so no 2 datasources are needed).
* Added schema.sql and data.sql to initialize the LogCloud tables.

## How To Run

* You need an <a href="https://www.atomikos.com/Main/ExtremeTransactionsFreeTrial?Source=github">ExtremeTransactions 5.0.x free trial</a> to run this demo.
* Run the SampleAtomikosApplication (a Spring Boot app).
* Check the DBMS log by running **org.h2.tools.Console** (in the h2 jar) and connect to the database in your workspace (e.g., jdbc:h2:/path/to/spring-boot-sample-jta-atomikos-logcloud/logcloud)

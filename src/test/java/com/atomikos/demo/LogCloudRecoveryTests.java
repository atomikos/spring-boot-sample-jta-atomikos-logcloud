/*
 * Copyright 2026 Atomikos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.atomikos.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against real PostgreSQL and Artemis containers (skipped without Docker).
 * The crash switch throws instead of halting the JVM, since a halt would kill
 * the test runner too - so this checks recovery within one node; the README
 * shows another node doing it.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LogCloudRecoveryTests {

	private static final String LOCAL_LOG_DIR = "target/transaction-logs";

	// Started once, and stopped by Testcontainers when the JVM exits - not after the
	// class (@Container), which would stop Artemis under the still-cached Spring
	// context, whose JMS listener then blocks shutdown trying to reconnect.

	// XA (two-phase commit) needs prepared transactions, disabled by default
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
		.withCommand("postgres", "-c", "max_prepared_transactions=100");

	static ArtemisContainer artemis = new ArtemisContainer("apache/activemq-artemis:2.40.0-alpine");

	@DynamicPropertySource
	static void connectToContainers(DynamicPropertyRegistry registry) {
		if (!postgres.isRunning()) {
			postgres.start();
			artemis.start();
		}
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.artemis.broker-url", artemis::getBrokerUrl);
		registry.add("spring.artemis.user", artemis::getUser);
		registry.add("spring.artemis.password", artemis::getPassword);
		// where a file-based transaction log would go - LogCloud must leave it empty
		registry.add("atomikos.properties.log-base-dir", () -> LOCAL_LOG_DIR);
	}

	@Autowired
	OrderService orderService;

	@Autowired
	OrderRepository orders;

	@Autowired
	ShipmentRepository shipments;

	@Autowired
	CrashSwitch crashSwitch;

	@BeforeEach
	void cleanUp() {
		this.shipments.deleteAll();
		this.orders.deleteAll();
	}

	@Test
	void anOrderIsStoredAndShipped() {
		long id = this.orderService.placeOrder("book");

		assertThat(this.orders.findById(id)).isPresent();
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(this.shipments.findAll()).extracting(Shipment::getOrderId).containsExactly(id));
	}

	/**
	 * The "crash" fires once, inside the phase-2 commit to PostgreSQL. At that
	 * moment the order must be in doubt - prepared but invisible - and the commit
	 * decision must be in the LogCloud log in PostgreSQL. Atomikos then completes
	 * the commit, and the order and its shipment appear.
	 */
	@Test
	void aCrashAfterThePrepareIsLoggedInPostgresAndCompleted() {
		AtomicBoolean crashed = new AtomicBoolean();
		AtomicLong inDoubtAtCrash = new AtomicLong(-1);
		AtomicLong ordersAtCrash = new AtomicLong(-1);
		AtomicLong committingAtCrash = new AtomicLong(-1);
		this.crashSwitch.crashWith(() -> {
			if (crashed.compareAndSet(false, true)) { // a real crash happens once
				inDoubtAtCrash.set(countInPostgres("SELECT count(*) FROM pg_prepared_xacts"));
				ordersAtCrash.set(countInPostgres("SELECT count(*) FROM purchase_order"));
				// the decision for exactly this transaction, not one left over from an earlier test
				committingAtCrash.set(countInPostgres("SELECT count(*) FROM coordinator WHERE state = 'COMMITTING' AND id = '"
						+ coordinatorIdOf(singleInDoubtGid()) + "'"));
				throw new IllegalStateException("simulated crash");
			}
		});

		long id = this.crashSwitch.placeOrderAndCrash(() -> this.orderService.placeOrder("guitar"));

		assertThat(crashed).as("the crash switch fired in phase 2").isTrue();
		assertThat(inDoubtAtCrash).as("prepared transactions at the crash").hasValue(1);
		assertThat(ordersAtCrash).as("committed orders at the crash").hasValue(0);
		assertThat(committingAtCrash).as("this transaction's commit decision in the LogCloud log at the crash").hasValue(1);
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(this.orders.findById(id)).isPresent();
			assertThat(this.shipments.findAll()).extracting(Shipment::getOrderId).containsExactly(id);
			assertThat(countInPostgres("SELECT count(*) FROM pg_prepared_xacts")).isZero();
		});
		assertThat(new File(LOCAL_LOG_DIR).listFiles((dir, name) -> name.endsWith(".log")))
			.as("file-based transaction logs").isNullOrEmpty();
	}

	private static String singleInDoubtGid() {
		try (Connection connection = postgres.createConnection("");
				ResultSet result = connection.createStatement().executeQuery("SELECT gid FROM pg_prepared_xacts")) {
			result.next();
			return result.getString(1);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// PostgreSQL's gid is formatId_base64(gtrid)_base64(bqual); Atomikos' gtrid is the coordinator id
	private static String coordinatorIdOf(String gid) {
		return new String(Base64.getDecoder().decode(gid.split("_")[1]), StandardCharsets.US_ASCII);
	}

	// straight to PostgreSQL, outside Atomikos and the transaction being committed
	private static long countInPostgres(String sql) {
		try (Connection connection = postgres.createConnection("");
				ResultSet result = connection.createStatement().executeQuery(sql)) {
			result.next();
			return result.getLong(1);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

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

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the demo page ({@code index.html}) shows, straight from PostgreSQL:
 * orders and shipments, in-doubt (prepared) transactions, and the LogCloud
 * transaction log that every node shares. Demo plumbing only.
 */
@RestController
public class StatusController {

	private static final String ORDERS = """
			SELECT coalesce(o.id, s.order_id) AS "order",
			       CASE WHEN o.id IS NULL THEN '❌ no order' ELSE '✅ ' || o.product END AS product,
			       o.placed_by AS "placedBy",
			       CASE WHEN s.order_id IS NULL THEN '❌ not shipped' ELSE '✅ shipped' END AS shipment,
			       s.shipped_by AS "shippedBy"
			FROM purchase_order o FULL JOIN shipment s ON s.order_id = o.id ORDER BY 1""";

	private static final String IN_DOUBT = "SELECT gid, prepared FROM pg_prepared_xacts ORDER BY prepared";

	// the LogCloud transaction log: decisions that are not finished yet
	private static final String LOG = """
			SELECT id, state, owning_recovery_domain_name AS domain
			FROM coordinator ORDER BY id""";

	// the node that currently runs recovery for the whole domain
	private static final String LEADER = """
			SELECT active_jvm_id AS "activeJvmId",
			       (expiry - (extract(epoch FROM clock_timestamp()) * 1000)::bigint) / 1000 AS "expiresInSeconds"
			FROM leader WHERE owning_recovery_domain_name = 'logcloud'""";

	// how LogCloud identifies this JVM in LEADER.ACTIVE_JVM_ID (pid@host)
	private static final String JVM_ID = ManagementFactory.getRuntimeMXBean().getName();

	// "logcloud" (shared, in PostgreSQL) or "file" (on this node's own disk)
	private static final String TRANSACTION_LOG = ClassUtils.isPresent(LogCloudConfiguration.LOGCLOUD_LOG_FACTORY,
			StatusController.class.getClassLoader()) ? "logcloud" : "file";

	private final JdbcTemplate jdbc;

	private final int node;

	private final String transactionManager;

	public StatusController(JdbcTemplate jdbc, @Value("${server.port}") int node,
			@Value("${atomikos.properties.transaction-manager-unique-name}") String transactionManager) {
		this.jdbc = jdbc;
		this.node = node;
		this.transactionManager = transactionManager;
	}

	// the page on one node polls every node, so allow cross-origin reads
	@CrossOrigin
	@GetMapping("/status")
	@Transactional(readOnly = true)
	public Map<String, Object> status() {
		return Map.of("node", this.node,
				// the container name when running in Docker (compose.yaml), else empty
				"name", Objects.requireNonNullElse(System.getenv("NODE_NAME"), ""),
				"jvmId", JVM_ID,
				"transactionLog", TRANSACTION_LOG,
				"transactionManager", this.transactionManager,
				"canFreeze", CrashSwitch.canFreeze(),
				"orders", this.jdbc.queryForList(ORDERS),
				"inDoubt", this.jdbc.queryForList(IN_DOUBT),
				"log", this.jdbc.queryForList(LOG),
				"leader", this.jdbc.queryForList(LEADER));
	}

}

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

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.atomikos.springboot.autoconfigure.jdbc.logcloud.LogCloudDataSourceConfiguration;

/**
 * Registers the LogCloud datasource (from transactions-spring-boot-logcloud, which
 * picks its Spring Boot 4 variant here), which
 * reuses the application's datasource settings, so no second database is needed.
 * <p>
 * Only when LogCloud is on the classpath: without the transactions-logcloud jar,
 * Atomikos falls back to its file-based log (the {@code TRANSACTION_LOG=file} mode).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = LogCloudConfiguration.LOGCLOUD_LOG_FACTORY)
@Import(LogCloudDataSourceConfiguration.class)
class LogCloudConfiguration {

	// what Atomikos finds through ServiceLoader to keep its log in the database
	static final String LOGCLOUD_LOG_FACTORY = "com.atomikos.logcloud.oltp.JdbcOltpLogFactory";

}

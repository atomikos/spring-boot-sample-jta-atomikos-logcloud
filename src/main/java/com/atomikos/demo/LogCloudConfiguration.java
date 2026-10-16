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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.weaving.LoadTimeWeaverAware;

import com.atomikos.springboot.autoconfigure.jdbc.logcloud.LogCloudDataSourceBean;

/**
 * Spring Boot 4 replacement for Atomikos' LogCloudDataSourceConfiguration, which is
 * compiled against the pre-Boot 4 location of {@link DataSourceProperties}.
 * Registers a LogCloud datasource that reuses the application's datasource settings.
 * <p>
 * Only when LogCloud is on the classpath: without the transactions-logcloud jar,
 * Atomikos falls back to its file-based log (the {@code TRANSACTION_LOG=file} mode).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = LogCloudConfiguration.LOGCLOUD_LOG_FACTORY)
class LogCloudConfiguration {

	// what Atomikos finds through ServiceLoader to keep its log in the database
	static final String LOGCLOUD_LOG_FACTORY = "com.atomikos.logcloud.oltp.JdbcOltpLogFactory";

	// LoadTimeWeaverAware beans are initialized before regular singletons, so the
	// LogCloud datasource is registered before the Atomikos transaction service starts.
	@Bean
	LoadTimeWeaverAware logCloudDataSource(DataSourceProperties properties,
			@Value("${spring.jta.atomikos.logcloud.min-pool-size:1}") int minPoolSize,
			@Value("${spring.jta.atomikos.logcloud.max-pool-size:1}") int maxPoolSize) throws Exception {
		LogCloudDataSourceBean dataSource = new LogCloudDataSourceBean();
		dataSource.setUniqueResourceName(com.atomikos.icatch.config.Configuration.getConfigProperties()
			.getProperty("com.atomikos.icatch.logcloud_datasource_name"));
		dataSource.setUrl(properties.getUrl());
		dataSource.setUser(properties.determineUsername());
		dataSource.setPassword(properties.getPassword());
		dataSource.setDriverClassName(properties.determineDriverClassName());
		dataSource.setIgnoreJtaTransactions(true);
		dataSource.setMinPoolSize(minPoolSize);
		dataSource.setMaxPoolSize(maxPoolSize);
		dataSource.init();
		return dataSource;
	}

}

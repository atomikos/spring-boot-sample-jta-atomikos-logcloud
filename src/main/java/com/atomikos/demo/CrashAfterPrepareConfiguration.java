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

import java.sql.SQLException;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.postgresql.core.BaseConnection;
import org.postgresql.xa.PGXAConnection;
import org.postgresql.xa.PGXADataSource;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.atomikos.spring.AtomikosXADataSourceWrapper;

/**
 * Demo plumbing for {@code ?crash=after-prepare}: hands Atomikos the PostgreSQL
 * {@link XADataSource} as usual, but its connections call {@link CrashSwitch}
 * right before the phase-2 commit. Not something a real application needs.
 */
@Configuration(proxyBeanMethods = false)
class CrashAfterPrepareConfiguration {

	@Bean
	XADataSourceWrapper xaDataSourceWrapper(CrashSwitch crashSwitch) {
		AtomikosXADataSourceWrapper atomikos = new AtomikosXADataSourceWrapper();
		return (postgres) -> atomikos.wrapDataSource(new CrashingXADataSource((PGXADataSource) postgres, crashSwitch));
	}

	private static class CrashingXADataSource extends PGXADataSource {

		private final transient CrashSwitch crashSwitch;

		// the same database and settings as the one Spring Boot configured
		CrashingXADataSource(PGXADataSource postgres, CrashSwitch crashSwitch) {
			setURL(postgres.getURL());
			setUser(postgres.getUser());
			setPassword(postgres.getPassword());
			this.crashSwitch = crashSwitch;
		}

		@Override
		public XAConnection getXAConnection(String user, String password) throws SQLException {
			BaseConnection connection = (BaseConnection) getConnection(user, password);
			return new PGXAConnection(connection) {

				@Override
				public void commit(Xid xid, boolean onePhase) throws XAException {
					if (!onePhase) {
						CrashingXADataSource.this.crashSwitch.beforePhase2Commit(); // two-phase commit, after a successful prepare
					}
					super.commit(xid, onePhase);
				}

			};
		}

	}

}

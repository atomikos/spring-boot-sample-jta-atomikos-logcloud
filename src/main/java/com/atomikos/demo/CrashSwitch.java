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

import java.io.IOException;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Halts the JVM in the middle of one commit: after PostgreSQL and Artemis have
 * both prepared and the commit decision is in the LogCloud log, but before
 * PostgreSQL got its commit. A random {@code kill -9} almost never hits that
 * window.
 * <p>
 * {@code POST /orders?crash=after-prepare} arms it for that one request only.
 * Other requests, and other nodes recovering the transaction, never crash.
 * Demo plumbing only - a real application needs none of it.
 */
@Component
public class CrashSwitch extends OncePerRequestFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(CrashSwitch.class);

	// whether the order being placed on this thread should crash
	private static final ThreadLocal<Boolean> ARMED = new ThreadLocal<>();

	private Runnable crash = () -> Runtime.getRuntime().halt(1);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("after-prepare".equals(request.getParameter("crash"))) {
			arm();
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			ARMED.remove();
		}
	}

	void beforePhase2Commit() {
		if (Boolean.TRUE.equals(ARMED.get())) {
			LOGGER.error("💥 CRASH! PostgreSQL and Artemis both PREPARED, commit decision logged in LogCloud, "
					+ "PostgreSQL NOT committed yet - halting the JVM now");
			System.out.flush();
			this.crash.run();
		}
	}

	/** For tests: place an order that crashes after the prepare. */
	long placeOrderAndCrash(LongSupplier placeOrder) {
		arm();
		try {
			return placeOrder.getAsLong();
		}
		finally {
			ARMED.remove();
		}
	}

	/** For tests: do something other than halting the JVM, which would kill the test runner too. */
	void crashWith(Runnable crash) {
		this.crash = crash;
	}

	private static void arm() {
		LOGGER.warn("💣 this order will crash after the prepare");
		ARMED.set(true);
	}

}

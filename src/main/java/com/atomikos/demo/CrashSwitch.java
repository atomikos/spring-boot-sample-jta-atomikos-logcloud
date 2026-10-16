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
 * {@code POST /orders?freeze=after-prepare} stops that request at the same point
 * without halting, so that the node can be killed from outside (for example with
 * {@code docker kill}). Other requests, and other nodes recovering the
 * transaction, never crash or freeze. Demo plumbing only - a real application
 * needs none of it.
 */
@Component
public class CrashSwitch extends OncePerRequestFilter {

	private enum Action {
		CRASH, FREEZE
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(CrashSwitch.class);

	// what the order being placed on this thread should do after the prepare, if anything
	private static final ThreadLocal<Action> ARMED = new ThreadLocal<>();

	private Runnable crash = () -> Runtime.getRuntime().halt(1);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if ("after-prepare".equals(request.getParameter("crash"))) {
			arm(Action.CRASH);
		}
		else if ("after-prepare".equals(request.getParameter("freeze"))) {
			arm(Action.FREEZE);
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			ARMED.remove();
		}
	}

	void beforePhase2Commit() {
		Action action = ARMED.get();
		if (action == Action.CRASH) {
			LOGGER.error("💥 CRASH! PostgreSQL and Artemis both PREPARED, commit decision logged, "
					+ "PostgreSQL NOT committed yet - halting the JVM now");
			System.out.flush();
			this.crash.run();
		}
		else if (action == Action.FREEZE) {
			LOGGER.error("⏸ FROZEN! PostgreSQL and Artemis both PREPARED, commit decision logged, "
					+ "PostgreSQL NOT committed yet - kill this node now (docker kill)");
			freeze();
		}
	}

	/** For tests: place an order that crashes after the prepare. */
	long placeOrderAndCrash(LongSupplier placeOrder) {
		arm(Action.CRASH);
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

	private static void arm(Action action) {
		LOGGER.warn(action == Action.CRASH ? "💣 this order will crash after the prepare"
				: "⏸ this order will freeze after the prepare");
		ARMED.set(action);
	}

	/**
	 * Stops the whole JVM (SIGSTOP), as a hung node would: no recovery thread,
	 * no lease renewal - nothing runs until the node is killed from outside.
	 * Freezing only this thread is not enough: the node's own recovery would
	 * finish the frozen commit within seconds. Needs a Unix process that is not
	 * PID 1 of its container, hence {@code init: true} in compose.yaml.
	 */
	static boolean canFreeze() {
		return System.getenv("NODE_NAME") != null && ProcessHandle.current().pid() != 1;
	}

	private static void freeze() {
		if (!canFreeze()) {
			throw new IllegalStateException("freeze needs a node running in Docker (see compose.yaml)");
		}
		try {
			System.out.flush();
			new ProcessBuilder("kill", "-STOP", String.valueOf(ProcessHandle.current().pid())).start().waitFor();
			Thread.sleep(Long.MAX_VALUE); // in case the JVM is ever resumed instead of killed
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not freeze the JVM", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("frozen order interrupted", ex);
		}
	}

}

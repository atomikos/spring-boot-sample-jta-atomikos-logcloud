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
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places an order: a row in PostgreSQL plus a message on the orders queue, in
 * one XA transaction - both happen, or neither does, even across a crash.
 */
@Service
public class OrderService {

	private final OrderRepository orders;

	private final JmsTemplate jms;

	private final int node;

	public OrderService(OrderRepository orders, JmsTemplate jms, @Value("${server.port}") int node) {
		this.orders = orders;
		this.jms = jms;
		this.node = node;
	}

	@Transactional
	public long placeOrder(String product) {
		PurchaseOrder order = this.orders.save(new PurchaseOrder(product, this.node)); // 1. PostgreSQL
		this.jms.convertAndSend("orders", order.getId());                              // 2. Artemis
		return order.getId();
	}

}

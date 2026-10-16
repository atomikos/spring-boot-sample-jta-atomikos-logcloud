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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * The other side of the queue: ships every order it hears about. All nodes
 * listen, so whichever node gets the message ships the order.
 */
@Component
public class ShippingListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(ShippingListener.class);

	private final ShipmentRepository shipments;

	private final int node;

	public ShippingListener(ShipmentRepository shipments, @Value("${server.port}") int node) {
		this.shipments = shipments;
		this.node = node;
	}

	@JmsListener(destination = "orders")
	public void onOrderPlaced(Long orderId) {
		this.shipments.save(new Shipment(orderId, this.node));
		LOGGER.info("📦 shipped order #{}", orderId);
	}

}

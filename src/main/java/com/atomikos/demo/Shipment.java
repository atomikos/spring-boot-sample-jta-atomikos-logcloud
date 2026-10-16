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

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

/**
 * A shipment, created when the order message arrives. Every order should have
 * exactly one.
 */
@Entity
public class Shipment {

	@Id
	@GeneratedValue
	private Long id;

	private Long orderId;

	// the port of the node that shipped the order
	private int shippedBy;

	protected Shipment() {
	}

	public Shipment(Long orderId, int shippedBy) {
		this.orderId = orderId;
		this.shippedBy = shippedBy;
	}

	public Long getOrderId() {
		return this.orderId;
	}

	public int getShippedBy() {
		return this.shippedBy;
	}

}

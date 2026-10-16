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
 * An order, stored in PostgreSQL.
 */
@Entity
public class PurchaseOrder {

	@Id
	@GeneratedValue
	private Long id;

	private String product;

	// the port of the node that placed the order
	private int placedBy;

	protected PurchaseOrder() {
	}

	public PurchaseOrder(String product, int placedBy) {
		this.product = product;
		this.placedBy = placedBy;
	}

	public Long getId() {
		return this.id;
	}

	public String getProduct() {
		return this.product;
	}

	public int getPlacedBy() {
		return this.placedBy;
	}

}

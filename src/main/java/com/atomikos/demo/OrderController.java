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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code curl -X POST localhost:8080/orders?product=book}, or
 * {@code ...&crash=after-prepare} to crash this node in the middle of the commit
 * (see {@link CrashSwitch}).
 */
@RestController
public class OrderController {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	// the page on one node places orders on every node
	@CrossOrigin
	@PostMapping("/orders")
	public String placeOrder(@RequestParam(defaultValue = "book") String product) {
		long id = this.orderService.placeOrder(product);
		LOGGER.info("🛒 placed order #{} ({})", id, product);
		return "placed order #" + id + "\n";
	}

}

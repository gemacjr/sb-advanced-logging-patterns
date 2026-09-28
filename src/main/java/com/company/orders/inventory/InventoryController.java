package com.company.orders.inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for a separate inventory microservice. It is reached over HTTP, so its
 * log lines only share the caller's requestId/traceId because the headers propagated.
 */
@RestController
@RequestMapping("/internal/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);
    private static final int LOW_STOCK_THRESHOLD = 3;

    private final Map<String, Integer> stock = new ConcurrentHashMap<>(Map.of(
            "SKU-LAPTOP", 10,
            "SKU-PHONE", 4,
            "SKU-RARE", 0));

    public record Reservation(String orderId, String productId, int quantity) {
    }

    @PostMapping("/reservations")
    public synchronized ResponseEntity<Void> reserve(@RequestBody Reservation reservation) {
        log.info("Reserving inventory productId={} quantity={}", reservation.productId(), reservation.quantity());

        int available = stock.getOrDefault(reservation.productId(), 0);
        if (available < reservation.quantity()) {
            log.warn("Insufficient stock productId={} requested={} available={}",
                    reservation.productId(), reservation.quantity(), available);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        int remaining = available - reservation.quantity();
        stock.put(reservation.productId(), remaining);
        if (remaining < LOW_STOCK_THRESHOLD) {
            log.warn("Inventory running low for {} remaining={}", reservation.productId(), remaining);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, Integer> stock() {
        return stock;
    }
}

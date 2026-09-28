package com.company.orders.order;

public class InventoryException extends RuntimeException {

    public InventoryException(String message, Throwable cause) {
        super(message, cause);
    }
}

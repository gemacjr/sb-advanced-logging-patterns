package com.company.orders.order;

public record OrderResult(String orderId, Status status, String paymentId, String reason) {

    public enum Status { CREATED, FAILED_ROLLED_BACK }

    static OrderResult created(String orderId, String paymentId) {
        return new OrderResult(orderId, Status.CREATED, paymentId, null);
    }

    static OrderResult rolledBack(String orderId, String paymentId, String reason) {
        return new OrderResult(orderId, Status.FAILED_ROLLED_BACK, paymentId, reason);
    }
}

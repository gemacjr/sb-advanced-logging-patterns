package com.company.orders.logging;

/**
 * The "backpack" of keys carried on every log line of a request.
 * Keeping them in one place stops typos like "requestID" vs "requestId"
 * from silently splitting your Kibana queries in two.
 */
public final class MdcKeys {

    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String ORDER_ID = "orderId";
    public static final String PAYMENT_ID = "paymentId";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String USER_ID_HEADER = "X-User-Id";

    private MdcKeys() {
    }
}

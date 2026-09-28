package com.company.orders.order;

import com.company.orders.logging.MdcKeys;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Recreates the article's 2 AM incident: payment succeeds, inventory fails, rollback triggers.
 * Every line carries requestId + userId + orderId + paymentId, so filtering on one requestId
 * in Kibana tells the whole story.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final PaymentService paymentService;
    private final InventoryClient inventoryClient;
    private final NotificationService notificationService;

    public OrderService(PaymentService paymentService, InventoryClient inventoryClient,
                        NotificationService notificationService) {
        this.paymentService = paymentService;
        this.inventoryClient = inventoryClient;
        this.notificationService = notificationService;
    }

    public OrderResult placeOrder(OrderRequest request) {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        // putCloseable removes the key when the block exits, even on exceptions
        try (var ignored = MDC.putCloseable(MdcKeys.ORDER_ID, orderId)) {
            log.info("Order received {}", request);

            String paymentId = paymentService.charge(request.amount(), request);
            try {
                inventoryClient.reserve(orderId, request.productId(), request.quantity());
            } catch (InventoryException ex) {
                // Exception as the LAST argument, not ex.getMessage(): keeps the full stack trace
                log.error("Inventory status=FAILED, rollback triggered. productId={} quantity={}",
                        request.productId(), request.quantity(), ex);
                paymentService.refund(paymentId, "INVENTORY_UNAVAILABLE");
                log.info("Rollback completed=true");
                return OrderResult.rolledBack(orderId, paymentId, ex.getMessage());
            }

            notificationService.sendConfirmation(orderId);
            log.info("Order {} created successfully", orderId);
            return OrderResult.created(orderId, paymentId);
        } finally {
            MDC.remove(MdcKeys.PAYMENT_ID);
        }
    }
}

package com.company.orders.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Simulated notification sender. A failure here must not fail the order: WARN, not ERROR. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void sendConfirmation(String orderId) {
        try {
            if (orderId.hashCode() % 5 == 0) {
                throw new IllegalStateException("SMTP relay timeout");
            }
            log.info("Notification status=SENT channel=EMAIL");
        } catch (RuntimeException ex) {
            // Degraded but not broken: pass the exception as the last argument to keep the stack trace
            log.warn("Notification status=FAILED order {} will not be retried", orderId, ex);
        }
    }
}

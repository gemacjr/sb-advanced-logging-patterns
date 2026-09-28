package com.company.orders.order;

import com.company.orders.logging.MdcKeys;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Simulated payment gateway. Demonstrates TRACE/DEBUG/INFO usage. */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public String charge(BigDecimal amount, OrderRequest request) {
        log.trace("Entering payment validator");
        log.debug("Charging card {} amount {}", request.maskedCard(), amount);

        String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        // From here on every log line in this request carries paymentId as well
        MDC.put(MdcKeys.PAYMENT_ID, paymentId);
        log.info("Payment status=SUCCESS amount={}", amount);
        return paymentId;
    }

    public void refund(String paymentId, String reason) {
        log.warn("Refunding payment {} reason={}", paymentId, reason);
        log.info("Payment status=REFUNDED");
    }
}

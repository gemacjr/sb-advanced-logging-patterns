package com.company.orders.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record OrderRequest(
        @NotBlank String productId,
        @Positive int quantity,
        @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "\\d{13,19}") String cardNumber) {

    /**
     * Rule 1: never log card numbers. A record's generated toString() prints every
     * component, so one careless {@code log.debug("{}", request)} would leak the PAN.
     */
    @Override
    public String toString() {
        return "OrderRequest[productId=%s, quantity=%d, amount=%s, card=%s]"
                .formatted(productId, quantity, amount, maskedCard());
    }

    public String maskedCard() {
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }
}

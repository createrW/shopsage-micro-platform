package com.shopsage.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the payment-service. Shared web error handling and observability defaults arrive through
 * shopsage-common's auto-configuration, so component scanning stays limited to {@code com.shopsage.payment}.
 */
@SpringBootApplication
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
